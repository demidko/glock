package com.github.demidko.glock

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.Chat
import com.github.kotlintelegrambot.entities.ChatId.Companion.fromId
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.entities.MessageOrigin
import com.github.kotlintelegrambot.entities.LinkPreviewOptions
import com.github.kotlintelegrambot.entities.ReplyParameters
import com.github.kotlintelegrambot.entities.ParseMode.HTML
import com.github.kotlintelegrambot.entities.User
import com.github.kotlintelegrambot.types.TelegramBotResult.Success
import com.github.kotlintelegrambot.types.TelegramBotResult.Error.Unknown
import com.google.common.truth.Truth.assertThat
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.lang.Thread.sleep
import java.time.Duration.ofSeconds
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset.UTC
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicLong

class ChatOpsTest {

  private val chat = Chat(id = -10042, type = "supergroup")
  private val chatId = fromId(chat.id)
  private val channel = Chat(id = -100101, type = "channel", username = "channel_one")
  private val otherChannel = Chat(id = -100102, type = "channel", username = "channel_two")
  private val user = User(id = 11, isBot = false, firstName = "User")
  private val gunfighter = User(id = 22, isBot = false, firstName = "Gunfighter", username = "gunfighter")
  private val fakeUser = User(id = 136817688, isBot = true, firstName = "Channel_Bot")
  private val duration = ofSeconds(300)
  private val gunfighterLink = "<a href=\"tg://user?id=22\">@gunfighter</a>"
  private val userLink = "<a href=\"tg://user?id=11\">User</a>"
  private val channelOneLink = "<a href=\"https://t.me/channel_one\">@channel_one</a>"
  private val channelTwoLink = "<a href=\"https://t.me/channel_two\">@channel_two</a>"
  private val bot = mockk<Bot>()
  private val senderChatBans = mockk<SenderChatBans>()
  private val userRestrictions = mockk<UserRestrictions>()
  private val chatOps = ChatOps(
    bot,
    chatId,
    duration,
    0,
    UTC,
    senderChatBans,
    userRestrictions
  )

  @BeforeEach
  fun setUp() {
    every { senderChatBans.remainingSeconds(chat.id, any()) } returns duration.seconds
    every { userRestrictions.remainingSeconds(chat.id, any()) } returns duration.seconds
    every {
      bot.sendMessage(chatId, any(), replyParameters = any(), disableNotification = true)
    } returns Success(message(999))
    every {
      bot.sendMessage(
        chatId,
        any(),
        parseMode = HTML,
        linkPreviewOptions = LinkPreviewOptions(isDisabled = true),
        disableNotification = true,
        messageThreadId = any()
      )
    } returns Success(message(1000))
  }

  @Test
  fun `shoot bans sender channel instead of its fake user`() {
    assertChannelShot(fakeUser)
  }

  @Test
  fun `shoot bans sender channel when from is absent`() {
    assertChannelShot(null)
  }

  @Test
  fun `shoot keeps extending an ordinary user's restriction`() {
    assertUserShot(message(1, from = user))
  }

  @Test
  fun `shoot targets user who forwarded a channel message`() {
    val target = message(1, from = user).copy(
      forwardOrigin = MessageOrigin.Channel(date = 1, chat = channel, messageId = 1)
    )
    assertUserShot(target)
  }

  @Test
  fun `shoot ignores anonymous administrator without falling back to fake user`() {
    val target = message(1, from = fakeUser, senderChat = chat)

    chatOps.shoot(replyingTo(target))

    verify { bot wasNot Called }
    verify { senderChatBans wasNot Called }
  }

  @Test
  fun `failed channel ban does not produce an explosion reply`() {
    val target = message(1, from = fakeUser, senderChat = channel)
    every { senderChatBans.ban(chat.id, channel.id, duration.seconds) } returns false

    chatOps.shoot(replyingTo(target))

    verify(exactly = 1) { senderChatBans.ban(chat.id, channel.id, duration.seconds) }
    verify { bot wasNot Called }
  }

  @Test
  fun `heal unbans sender channel instead of its fake user`() {
    assertChannelHealed(fakeUser)
  }

  @Test
  fun `heal unbans sender channel when from is absent`() {
    assertChannelHealed(null)
  }

  @Test
  fun `failed channel unban does not produce a healing reply`() {
    val target = message(1, from = fakeUser, senderChat = channel)
    every { senderChatBans.unban(chat.id, channel.id) } returns false

    chatOps.heal(replyingTo(target), listOf("0"))

    verify(exactly = 1) { senderChatBans.unban(chat.id, channel.id) }
    verify { bot wasNot Called }
  }

  @Test
  fun `invalid healing code does not unban a channel`() {
    val target = message(1, from = fakeUser, senderChat = channel)

    chatOps.heal(replyingTo(target), listOf("1"))

    verify { bot wasNot Called }
    verify { senderChatBans wasNot Called }
  }

  @Test
  fun `heal ignores anonymous administrator without falling back to fake user`() {
    val target = message(1, from = fakeUser, senderChat = chat)

    chatOps.heal(replyingTo(target), listOf("0"))

    verify { bot wasNot Called }
    verify { senderChatBans wasNot Called }
  }

  @Test
  fun `heal restores an ordinary user's permissions`() {
    val target = message(1, from = user)
    every { userRestrictions.heal(chat.id, user.id) } returns true

    chatOps.heal(replyingTo(target), listOf("0"))

    verify(exactly = 1) { userRestrictions.heal(chat.id, user.id) }
    verifyHealingReply(target)
    verify { senderChatBans wasNot Called }
  }

  @Test
  fun `buckshot distinguishes channels sharing a fake user and excludes own channel`() {
    val ownMessage = message(1, from = fakeUser, senderChat = channel)
    val otherMessage = message(2, from = fakeUser, senderChat = otherChannel)
    chatOps.tryProcessStatuette(ownMessage)
    chatOps.tryProcessStatuette(otherMessage)
    every { senderChatBans.ban(chat.id, otherChannel.id, duration.seconds) } returns true

    chatOps.buckshot(message(3, from = fakeUser, senderChat = channel))

    verify(exactly = 1) { senderChatBans.ban(chat.id, otherChannel.id, duration.seconds) }
    verify(exactly = 0) { senderChatBans.ban(chat.id, channel.id, any()) }
    verifyBanLog("🤐5m $channelTwoLink 💥💥🔫 $channelOneLink")
    verifyNoUserRestriction()
  }

  @Test
  fun `buckshot works for a channel shooter without from`() {
    val target = message(1, senderChat = otherChannel)
    chatOps.tryProcessStatuette(target)
    every { senderChatBans.ban(chat.id, otherChannel.id, duration.seconds) } returns true

    chatOps.buckshot(message(2, senderChat = channel))

    verify(exactly = 1) { senderChatBans.ban(chat.id, otherChannel.id, duration.seconds) }
    verifyNoUserRestriction()
  }

  @Test
  fun `buckshot does not hit own channel when it is the only recent sender`() {
    chatOps.tryProcessStatuette(message(1, from = fakeUser, senderChat = channel))

    chatOps.buckshot(message(2, from = fakeUser, senderChat = channel))

    verify { bot wasNot Called }
    verify { senderChatBans wasNot Called }
  }

  @Test
  fun `statuette bans a channel that sends the next message`() {
    val target = message(2, senderChat = channel)
    every { bot.deleteMessage(chatId, 999) } returns Success(true)
    every { senderChatBans.ban(chat.id, channel.id, duration.seconds) } returns true

    chatOps.statuette(message(1, from = user))
    chatOps.tryProcessStatuette(target)

    verify(exactly = 1) { bot.deleteMessage(chatId, 999) }
    verify(exactly = 1) { senderChatBans.ban(chat.id, channel.id, duration.seconds) }
    verifyBanLog("🤐5m $channelOneLink 💥🗿 $userLink")
    verifyNoUserRestriction()
  }

  @Test
  fun `statuette survives a failed user mute and hits the next eligible message`() {
    every { userRestrictions.mute(chat.id, user.id, duration.seconds) } returns false andThen true
    every { bot.deleteMessage(chatId, 999) } returns Success(true)
    chatOps.statuette(message(1, from = gunfighter))

    chatOps.tryProcessStatuette(message(2, from = user))

    verify(exactly = 0) { bot.deleteMessage(chatId, 999) }
    verifyNoBanLog()
    chatOps.tryProcessStatuette(message(3, from = user))
    verify(exactly = 2) { userRestrictions.mute(chat.id, user.id, duration.seconds) }
    verify(exactly = 1) { bot.deleteMessage(chatId, 999) }
  }

  @Test
  fun `statuette survives a failed channel ban`() {
    every { senderChatBans.ban(chat.id, channel.id, duration.seconds) } returns false andThen true
    every { bot.deleteMessage(chatId, 999) } returns Success(true)
    chatOps.statuette(message(1, from = gunfighter))

    chatOps.tryProcessStatuette(message(2, senderChat = channel))

    verify(exactly = 0) { bot.deleteMessage(chatId, 999) }
    chatOps.tryProcessStatuette(message(3, senderChat = channel))
    verify(exactly = 2) { senderChatBans.ban(chat.id, channel.id, duration.seconds) }
    verify(exactly = 1) { bot.deleteMessage(chatId, 999) }
  }

  @Test
  fun `statuette ignores anonymous administrators and absent senders`() {
    every { bot.deleteMessage(chatId, 999) } returns Success(true)
    allowUserRestriction()
    chatOps.statuette(message(1, from = gunfighter))

    chatOps.tryProcessStatuette(message(2, from = fakeUser, senderChat = chat))
    chatOps.tryProcessStatuette(message(3))

    verify(exactly = 0) { bot.deleteMessage(chatId, 999) }
    verify { userRestrictions wasNot Called }
    chatOps.tryProcessStatuette(message(4, from = user))
    verify(exactly = 1) { userRestrictions.mute(chat.id, user.id, duration.seconds) }
    verify(exactly = 1) { bot.deleteMessage(chatId, 999) }
  }

  @Test
  fun `successful statuette does not hit twice when its message cannot be deleted`() {
    every { bot.deleteMessage(chatId, 999) } returns Unknown(IOException("Deletion unavailable"))
    allowUserRestriction()
    chatOps.statuette(message(1, from = gunfighter))

    chatOps.tryProcessStatuette(message(2, from = user))
    chatOps.tryProcessStatuette(message(3, from = user))

    verify(exactly = 1) { userRestrictions.mute(chat.id, user.id, duration.seconds) }
    verify(exactly = 1) { bot.deleteMessage(chatId, 999) }
  }

  @Test
  fun `statuette skips bot and membership service messages`() {
    every { bot.deleteMessage(chatId, 999) } returns Success(true)
    allowUserRestriction()
    chatOps.statuette(message(1, from = gunfighter))

    chatOps.tryProcessStatuette(message(2, from = fakeUser))
    chatOps.tryProcessStatuette(message(3, from = user).copy(newChatMembers = listOf(user)))
    chatOps.tryProcessStatuette(message(4, from = user).copy(leftChatMember = user))

    verify { userRestrictions wasNot Called }
    verify(exactly = 0) { bot.deleteMessage(chatId, 999) }
    chatOps.tryProcessStatuette(message(5, from = user))
    verify(exactly = 1) { userRestrictions.mute(chat.id, user.id, duration.seconds) }
    verify(exactly = 1) { bot.deleteMessage(chatId, 999) }
  }

  @Test
  fun `healing validates the sent minute in the configured zone after queue delay`() {
    val localChatOps = ChatOps(bot, chatId, duration, 7, ZoneId.of("America/New_York"), senderChatBans, userRestrictions)
    val target = message(1, from = user)
    every { userRestrictions.heal(chat.id, user.id) } returns true
    val sentCommands = listOf(
      Instant.parse("2020-01-02T15:04:00Z").epochSecond to 728L,
      Instant.parse("2020-01-02T17:06:00Z").epochSecond to 882L
    )

    for ((sentAt, code) in sentCommands) {
      localChatOps.heal(replyingTo(target).copy(date = sentAt), listOf(code.toString()))
    }

    verify(exactly = 2) { userRestrictions.heal(chat.id, user.id) }
  }

  @Test
  fun `healing still rejects a code for the wrong timezone`() {
    val localChatOps = ChatOps(bot, chatId, duration, 7, ZoneId.of("America/New_York"), senderChatBans, userRestrictions)
    val command = replyingTo(message(1, from = user)).copy(date = Instant.parse("2020-01-02T15:04:00Z").epochSecond)

    localChatOps.heal(command, listOf((154L * 7).toString()))

    verify { userRestrictions wasNot Called }
  }

  @Test
  fun `namesakes link to distinct user IDs and names cannot inject HTML`() {
    val shooter = gunfighter.copy(firstName = "Alex <&> \"one\"", username = null)
    val victim = user.copy(firstName = "Alex <&> \"one\"")
    allowUserRestriction(victim)

    chatOps.shoot(message(100, from = shooter).copy(replyToMessage = message(1, from = victim)))

    verifyBanLog(
      "🤐5m <a href=\"tg://user?id=11\">Alex &lt;&amp;&gt; &quot;one&quot;</a> 💥🔫 " +
        "<a href=\"tg://user?id=22\">Alex &lt;&amp;&gt; &quot;one&quot;</a>"
    )
  }

  @Test
  fun `user without a name or username is linked by numeric ID`() {
    val victim = user.copy(firstName = "", lastName = " ")
    allowUserRestriction(victim)

    chatOps.shoot(replyingTo(message(1, from = victim)))

    verifyBanLog("🤐5m <a href=\"tg://user?id=11\">11</a> 💥🔫 $gunfighterLink")
  }

  @Test
  fun `channel without username uses its escaped title and ID instead of the fake sender`() {
    val privateChannel = channel.copy(username = null, title = "Private <channel>")
    every { senderChatBans.ban(chat.id, channel.id, duration.seconds) } returns true

    chatOps.shoot(replyingTo(message(1, from = fakeUser, senderChat = privateChannel)))

    verifyBanLog("🤐5m Private &lt;channel&gt; (-100101) 💥🔫 $gunfighterLink")
    verifyNoUserRestriction()
  }

  @Test
  fun `log stays in the topic where the target was hit`() {
    val target = message(1, senderChat = channel).copy(messageThreadId = 77)
    every { senderChatBans.ban(chat.id, channel.id, duration.seconds) } returns true

    chatOps.shoot(replyingTo(target))

    verifyBanLog("🤐5m $channelOneLink 💥🔫 $gunfighterLink", threadId = 77)
  }

  @Test
  fun `user hit log includes seconds in the total remaining duration and shows the addition`() {
    allowUserRestriction()
    every { userRestrictions.remainingSeconds(chat.id, user.id) } returns 330

    chatOps.shoot(replyingTo(message(1, from = user)))

    verifyBanLog("🤐5m 30s (+5m) $userLink 💥🔫 $gunfighterLink")
  }

  @Test
  fun `shooting your own message leaves nobody at the grip`() {
    allowUserRestriction(gunfighter)

    chatOps.shoot(replyingTo(message(1, from = gunfighter)))

    verifyBanLog("🤐5m $gunfighterLink 💥🔫")
  }

  @Test
  fun `channel hit log shows total remaining duration instead of added duration`() {
    every { senderChatBans.ban(chat.id, channel.id, duration.seconds) } returns true
    every { senderChatBans.remainingSeconds(chat.id, channel.id) } returns 630

    chatOps.shoot(replyingTo(message(1, senderChat = channel)))

    verifyBanLog("🤐10m 30s (+5m) $channelOneLink 💥🔫 $gunfighterLink")
  }

  @Test
  fun `successful hit log survives temporary message cleanup`() {
    every { senderChatBans.ban(chat.id, channel.id, duration.seconds) } returns true
    every { bot.deleteMessage(chatId, any()) } returns Success(true)

    chatOps.shoot(replyingTo(message(1, senderChat = channel)))
    sleep(4_000)
    chatOps.cleanTempMessages()

    verify(exactly = 1) { bot.deleteMessage(chatId, 100) }
    verify(exactly = 0) { bot.deleteMessage(chatId, 1000) }
  }

  @Test
  fun `failed user restriction does not produce a ban log`() {
    every { userRestrictions.mute(chat.id, user.id, duration.seconds) } returns false

    chatOps.shoot(replyingTo(message(1, from = user)))

    verifyNoBanLog()
    verify(exactly = 0) { userRestrictions.remainingSeconds(any(), any()) }
  }

  @Test
  fun `failed user healing does not produce a healing reply`() {
    every { userRestrictions.heal(chat.id, user.id) } returns false

    chatOps.heal(replyingTo(message(1, from = user)), listOf("0"))

    verify(exactly = 1) { userRestrictions.heal(chat.id, user.id) }
    verify { bot wasNot Called }
  }

  @Test
  fun `invalid healing code does not restore a user`() {
    chatOps.heal(replyingTo(message(1, from = user)), listOf("1"))

    verify { userRestrictions wasNot Called }
    verify { bot wasNot Called }
  }

  @Test
  fun `failure to send the log does not repeat the ban`() {
    every { senderChatBans.ban(chat.id, channel.id, duration.seconds) } returns true
    every {
      bot.sendMessage(
        chatId, any(), parseMode = HTML,
        linkPreviewOptions = LinkPreviewOptions(isDisabled = true), disableNotification = true
      )
    } returns Unknown(IOException("Connection lost"))

    chatOps.shoot(replyingTo(message(1, senderChat = channel)))

    verify(exactly = 1) { senderChatBans.ban(chat.id, channel.id, duration.seconds) }
  }

  @Test
  fun `multiple buckshot hits on one target log one volley with the total remaining duration`() {
    val durations = mutableListOf<Long>()
    val logs = mutableListOf<String>()
    var remaining = 0L
    chatOps.tryProcessStatuette(message(1, senderChat = otherChannel))
    chatOps.tryProcessStatuette(message(2, senderChat = otherChannel))
    every { senderChatBans.ban(chat.id, otherChannel.id, capture(durations)) } answers {
      remaining += arg<Long>(2)
      true
    }
    every { senderChatBans.remainingSeconds(chat.id, otherChannel.id) } answers { remaining }
    every {
      bot.sendMessage(
        chatId, capture(logs), parseMode = HTML,
        linkPreviewOptions = LinkPreviewOptions(isDisabled = true), disableNotification = true
      )
    } returns Success(message(1000))

    chatOps.buckshot(message(3, senderChat = channel))

    assertThat(durations).hasSize(2)
    val total = durations.sum()
    val seconds = if (total % 60 > 0) " ${total % 60}s" else ""
    assertThat(logs).containsExactly("🤐${total / 60}m$seconds $channelTwoLink 💥💥🔫 $channelOneLink")
  }

  @Test
  fun `buckshot volley logs each victim once with its own duration in the shooter's topic`() {
    chatOps.tryProcessStatuette(message(1, from = user))
    chatOps.tryProcessStatuette(message(2, senderChat = otherChannel))
    every { userRestrictions.mute(chat.id, user.id, any()) } returns true
    every { senderChatBans.ban(chat.id, otherChannel.id, any()) } returns true
    val logs = mutableListOf<String>()
    every {
      bot.sendMessage(
        chatId, capture(logs), parseMode = HTML,
        linkPreviewOptions = LinkPreviewOptions(isDisabled = true), disableNotification = true, messageThreadId = 5
      )
    } returns Success(message(1000))

    chatOps.buckshot(message(3, from = gunfighter).copy(messageThreadId = 5))

    val suffix = " 💥💥🔫 $gunfighterLink"
    val log = logs.single()
    assertThat(log).endsWith(suffix)
    val victims = log.removeSuffix(suffix).split(" · ")
    assertThat(victims).containsNoDuplicates()
    val victim = Regex("🤐5m( \\(\\+\\d+m( \\d+s)?\\))? (${Regex.escape(userLink)}|${Regex.escape(channelTwoLink)})")
    for (entry in victims) {
      assertThat(entry).matches(victim.pattern)
    }
  }

  @Test
  fun `queued shots preserve both additions to an ordinary user's restriction`() {
    val initialRemaining = 300L
    val remaining = AtomicLong(initialRemaining)
    val firstRead = CountDownLatch(1)
    val releaseFirstRead = CountDownLatch(1)
    val completed = CountDownLatch(2)
    every { userRestrictions.mute(chat.id, user.id, duration.seconds) } answers {
      val previous = remaining.get()
      firstRead.countDown()
      check(releaseFirstRead.await(5, SECONDS))
      remaining.set(previous + arg<Long>(2))
      true
    }
    every { userRestrictions.remainingSeconds(chat.id, user.id) } answers { remaining.get() }

    ChatTaskDispatcher().use { dispatcher ->
      try {
        dispatcher.execute(chat.id) {
          try { chatOps.shoot(replyingTo(message(1, from = user))) }
          finally { completed.countDown() }
        }
        assertThat(firstRead.await(5, SECONDS)).isTrue()
        dispatcher.execute(chat.id) {
          try { chatOps.shoot(replyingTo(message(2, from = user))) }
          finally { completed.countDown() }
        }
      } finally {
        releaseFirstRead.countDown()
      }
      assertThat(completed.await(5, SECONDS)).isTrue()
    }

    assertThat(remaining.get()).isEqualTo(initialRemaining + 2 * duration.seconds)
    verify(exactly = 2) { userRestrictions.mute(chat.id, user.id, duration.seconds) }
  }

  @Test
  fun `queued statuette command hits the next message rather than its placement message`() {
    val placement = message(1, from = gunfighter)
    val target = message(2, from = user)
    allowUserRestriction()
    every { bot.deleteMessage(chatId, 999) } returns Success(true)
    val completed = CountDownLatch(1)

    ChatTaskDispatcher().use { dispatcher ->
      // The generic message handler is registered before command handlers in GlockBot.
      dispatcher.execute(chat.id) { chatOps.tryProcessStatuette(placement) }
      dispatcher.execute(chat.id) { chatOps.statuette(placement) }
      dispatcher.execute(chat.id) {
        try { chatOps.tryProcessStatuette(target) }
        finally { completed.countDown() }
      }
      assertThat(completed.await(5, SECONDS)).isTrue()
    }

    verify(exactly = 1) { userRestrictions.mute(chat.id, user.id, duration.seconds) }
    verify(exactly = 0) { userRestrictions.mute(chat.id, gunfighter.id, any()) }
    verify(exactly = 1) { bot.deleteMessage(chatId, 999) }
  }

  private fun allowUserRestriction(victim: User = user) {
    every { userRestrictions.mute(chat.id, victim.id, duration.seconds) } returns true
  }

  private fun verifyNoBanLog() {
    verify(exactly = 0) {
      bot.sendMessage(
        chatId, any(), parseMode = HTML,
        linkPreviewOptions = LinkPreviewOptions(isDisabled = true), disableNotification = true
      )
    }
  }

  private fun assertChannelShot(from: User?) {
    val target = message(1, from = from, senderChat = channel)
    every { senderChatBans.ban(chat.id, channel.id, duration.seconds) } returns true

    chatOps.shoot(replyingTo(target))

    verify(exactly = 1) { senderChatBans.ban(chat.id, channel.id, duration.seconds) }
    verifyBanLog("🤐5m $channelOneLink 💥🔫 $gunfighterLink")
    verifyNoUserRestriction()
  }

  private fun assertUserShot(target: Message) {
    allowUserRestriction()
    every { userRestrictions.remainingSeconds(chat.id, user.id) } returns 600

    chatOps.shoot(replyingTo(target))

    verify(exactly = 1) { userRestrictions.mute(chat.id, user.id, duration.seconds) }
    verifyBanLog("🤐10m (+5m) $userLink 💥🔫 $gunfighterLink")
    verify { senderChatBans wasNot Called }
  }

  private fun assertChannelHealed(from: User?) {
    val target = message(1, from = from, senderChat = channel)
    every { senderChatBans.unban(chat.id, channel.id) } returns true

    chatOps.heal(replyingTo(target), listOf("0"))

    verify(exactly = 1) { senderChatBans.unban(chat.id, channel.id) }
    verifyHealingReply(target)
    verifyNoUserRestriction()
  }

  private fun verifyHealingReply(target: Message) {
    verify(exactly = 1) {
      bot.sendMessage(
        chatId,
        match { it in setOf("💊", "💉", "🚑") },
        replyParameters = ReplyParameters(target.messageId),
        disableNotification = true
      )
    }
  }

  private fun verifyNoUserRestriction() {
    verify { userRestrictions wasNot Called }
  }

  private fun verifyBanLog(text: String, threadId: Long? = null) {
    verify(exactly = 1) {
      bot.sendMessage(
        chatId,
        text,
        parseMode = HTML,
        linkPreviewOptions = LinkPreviewOptions(isDisabled = true),
        disableNotification = true,
        messageThreadId = threadId
      )
    }
  }

  private fun replyingTo(target: Message): Message {
    return message(100, from = gunfighter).copy(replyToMessage = target)
  }

  private fun message(id: Long, from: User? = null, senderChat: Chat? = null): Message {
    return Message(messageId = id, from = from, senderChat = senderChat, date = 1, chat = chat)
  }
}
