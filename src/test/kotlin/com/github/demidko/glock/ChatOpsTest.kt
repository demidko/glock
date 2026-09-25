package com.github.demidko.glock

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.Chat
import com.github.kotlintelegrambot.entities.ChatId.Companion.fromId
import com.github.kotlintelegrambot.entities.ChatMember
import com.github.kotlintelegrambot.entities.ChatPermissions
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.entities.User
import com.github.kotlintelegrambot.network.Response
import com.github.kotlintelegrambot.types.TelegramBotResult.Success
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Response.success
import java.time.Duration.ofSeconds
import java.time.ZoneOffset.UTC

class ChatOpsTest {

  private val chat = Chat(id = -10042, type = "supergroup")
  private val chatId = fromId(chat.id)
  private val channel = Chat(id = -100101, type = "channel")
  private val otherChannel = Chat(id = -100102, type = "channel")
  private val user = User(id = 11, isBot = false, firstName = "User")
  private val fakeUser = User(id = 136817688, isBot = true, firstName = "Channel_Bot")
  private val restrictions = ChatPermissions(canSendMessages = false)
  private val duration = ofSeconds(300)
  private val bot = mockk<Bot>()
  private val senderChatBans = mockk<SenderChatBans>()
  private val chatOps = ChatOps(
    bot,
    chatId,
    restrictions,
    duration,
    0,
    UTC,
    senderChatBans
  )

  @BeforeEach
  fun setUp() {
    every {
      bot.sendMessage(chatId, any(), replyToMessageId = any(), disableNotification = true)
    } returns Success(message(999))
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
    assertUserShot(message(1, from = user).copy(forwardFromChat = channel))
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
    val permissions = ChatPermissions(
      canSendMessages = true,
      canSendMediaMessages = true,
      canSendPolls = true,
      canSendOtherMessages = true,
      canAddWebPagePreviews = true,
      canChangeInfo = true,
      canInviteUsers = true,
      canPinMessages = true
    )
    every {
      bot.restrictChatMember(chatId, user.id, permissions)
    } returns (success<Response<Boolean>?>(Response(result = true, ok = true)) to null)

    chatOps.heal(replyingTo(target), listOf("0"))

    verify(exactly = 1) { bot.restrictChatMember(chatId, user.id, permissions) }
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
    verify(exactly = 1) {
      bot.sendMessage(
        chatId,
        match { it in setOf("💥", "🗯️", "⚡️") },
        replyToMessageId = otherMessage.messageId,
        disableNotification = true
      )
    }
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
    verify(exactly = 1) {
      bot.sendMessage(chatId, "💥", replyToMessageId = target.messageId, disableNotification = true)
    }
    verifyNoUserRestriction()
  }

  private fun assertChannelShot(from: User?) {
    val target = message(1, from = from, senderChat = channel)
    every { senderChatBans.ban(chat.id, channel.id, duration.seconds) } returns true

    chatOps.shoot(replyingTo(target))

    verify(exactly = 1) { senderChatBans.ban(chat.id, channel.id, duration.seconds) }
    verify(exactly = 1) {
      bot.sendMessage(chatId, "💥", replyToMessageId = target.messageId, disableNotification = true)
    }
    verifyNoUserRestriction()
  }

  private fun assertUserShot(target: Message) {
    val previousUntilDate = 2_000_000_000
    val untilDate = previousUntilDate + duration.seconds
    every {
      bot.getChatMember(chatId, user.id)
    } returns Success(ChatMember(user, "restricted", untilDate = previousUntilDate))
    every {
      bot.restrictChatMember(chatId, user.id, restrictions, untilDate)
    } returns (success<Response<Boolean>?>(Response(result = true, ok = true)) to null)

    chatOps.shoot(replyingTo(target))

    verify(exactly = 1) { bot.restrictChatMember(chatId, user.id, restrictions, untilDate) }
    verify(exactly = 1) {
      bot.sendMessage(chatId, "💥", replyToMessageId = target.messageId, disableNotification = true)
    }
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
        replyToMessageId = target.messageId,
        disableNotification = true
      )
    }
  }

  private fun verifyNoUserRestriction() {
    verify(exactly = 0) { bot.getChatMember(any(), any()) }
    verify(exactly = 0) { bot.restrictChatMember(any(), any(), any(), any()) }
  }

  private fun replyingTo(target: Message): Message {
    return message(100, from = user).copy(replyToMessage = target)
  }

  private fun message(id: Long, from: User? = null, senderChat: Chat? = null): Message {
    return Message(messageId = id, from = from, senderChat = senderChat, date = 1, chat = chat)
  }
}
