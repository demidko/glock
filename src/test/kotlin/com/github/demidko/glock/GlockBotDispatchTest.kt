package com.github.demidko.glock

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.Chat
import com.github.kotlintelegrambot.entities.ChatFullInfo
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.entities.ParseMode
import com.github.kotlintelegrambot.entities.Update
import com.github.kotlintelegrambot.entities.User
import com.github.kotlintelegrambot.types.TelegramBotResult.Success
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import java.time.Duration.ofSeconds
import java.time.ZoneOffset.UTC
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.callSuspend
import kotlin.reflect.jvm.isAccessible

class GlockBotDispatchTest {
  private fun field(target: Any, name: String): Any =
    target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

  private fun deliver(glock: GlockBot, update: Update) = runBlocking {
    val dispatcher = field(field(glock, "bot"), "dispatcher")
    // Exercise the library's real filtering/registration order without starting network polling.
    dispatcher::class.declaredMemberFunctions.single { it.name == "handleUpdate" }
      .apply { isAccessible = true }.callSuspend(dispatcher, update)
  }

  @Test
  fun `a failed user cleanup does not skip channel or temporary message cleanup`() {
    mockkConstructor(Bot::class)
    try {
      every { anyConstructed<Bot>().getChat(any()) } returns
        Success(mockk<ChatFullInfo> { every { description } returns null })
      val restrictions = mockk<UserRestrictions>()
      every { restrictions.restoreExpired() } throws IllegalStateException("Temporary failure")
      val glock = GlockBot("test-token", ofSeconds(300), 0, UTC, emptySet(), -999, restrictions)
      val operations = mockk<ChatOps>()
      every { operations.cleanTempMessages() } returns Unit
      @Suppress("UNCHECKED_CAST")
      val chats = field(glock, "idToChatOps") as MutableMap<Long, ChatOps>
      chats[-10042] = operations

      glock.reconcileRestrictions()
      glock.cleanTempMessages()

      verify(exactly = 1) { restrictions.restoreExpired() }
      verify(exactly = 1) { operations.cleanTempMessages() }
    } finally {
      unmockkConstructor(Bot::class)
    }
  }

  @Test
  fun `real Telegram dispatcher runs shots of one chat concurrently`() {
    mockkConstructor(Bot::class)
    val chat = Chat(-10042, "supergroup")
    val owner = User(22, false, "Owner")
    val victim = User(11, false, "Victim")
    fun message(id: Long, from: User, text: String? = null, reply: Message? = null) =
      Message(id, from = from, date = 1, chat = chat, text = text, replyToMessage = reply)
    val userRestrictions = mockk<UserRestrictions>()
    val inFlight = CountDownLatch(2)
    val release = CountDownLatch(1)
    val reported = CountDownLatch(2)
    every { anyConstructed<Bot>().getChat(any()) } returns Success(mockk<ChatFullInfo> { every { description } returns null })
    every { userRestrictions.mute(chat.id, victim.id, 300) } answers {
      inFlight.countDown()
      check(release.await(5, SECONDS))
      true
    }
    every { userRestrictions.remainingSeconds(chat.id, victim.id) } returns 300
    every {
      anyConstructed<Bot>().sendMessage(
        any(), any(), parseMode = ParseMode.HTML, linkPreviewOptions = any(),
        disableNotification = true, messageThreadId = any()
      )
    } answers {
      reported.countDown()
      Success(message(1000, owner))
    }
    every { anyConstructed<Bot>().deleteMessage(any(), any()) } returns Success(true)
    val glock = GlockBot("test-token", ofSeconds(300), 0, UTC, setOf(chat.id), -999, userRestrictionsOverride = userRestrictions)
    try {
      deliver(glock, Update(1, message = message(2, victim, "target")))
      deliver(glock, Update(2, message = message(3, owner, "/shoot", message(2, victim))))
      deliver(glock, Update(3, message = message(4, owner, "/shoot", message(2, victim))))
      // Both shots reach the registry while neither has finished: nothing queues them behind each other.
      assertTrue(inFlight.await(5, SECONDS))
      release.countDown()
      assertTrue(reported.await(5, SECONDS))
    } finally {
      release.countDown()
      // Close real storage while its Telegram calls are still mocked.
      try {
        (field(field(glock, "senderChatBans"), "storage") as TelegramBanStorage).close()
      } finally {
        unmockkConstructor(Bot::class)
      }
    }
    verify(exactly = 2) { userRestrictions.mute(chat.id, victim.id, 300) }
  }
}
