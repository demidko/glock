package com.github.demidko.glock

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.Chat
import com.github.kotlintelegrambot.entities.ChatFullInfo
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ChatMember
import com.github.kotlintelegrambot.entities.ChatPermissions
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.entities.ParseMode
import com.github.kotlintelegrambot.entities.TelegramFile
import com.github.kotlintelegrambot.entities.Update
import com.github.kotlintelegrambot.entities.User
import com.github.kotlintelegrambot.entities.files.Document
import com.github.kotlintelegrambot.network.Response
import com.github.kotlintelegrambot.types.TelegramBotResult.Success
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import retrofit2.Response.success
import java.time.Duration.ofSeconds
import java.time.ZoneOffset.UTC
import java.util.concurrent.atomic.AtomicLong
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
  fun `real Telegram dispatcher preserves chat ordering and cross-chat progress`() {
    mockkConstructor(Bot::class)
    val chat = Chat(-10042, "supergroup")
    val otherChat = Chat(-10043, "supergroup")
    val owner = User(22, false, "Owner")
    val victim = User(11, false, "Victim")
    fun message(id: Long, from: User, text: String? = null, reply: Message? = null) =
      Message(id, from = from, date = 1, chat = chat, text = text, replyToMessage = reply)
    val deadline = AtomicLong(2_000_000_000)
    val firstRead = CountDownLatch(1)
    val releaseRead = CountDownLatch(1)
    val otherChatReplied = CountDownLatch(1)
    val targets = java.util.Collections.synchronizedList(mutableListOf<Long>())
    every { anyConstructed<Bot>().getChat(any()) } returns Success(mockk<ChatFullInfo> { every { description } returns null })
    every { anyConstructed<Bot>().getChatMember(any(), any()) } answers {
      val previous = deadline.get()
      firstRead.countDown()
      check(releaseRead.await(5, SECONDS))
      Success(ChatMember(victim, "restricted", untilDate = previous.toInt()))
    }
    every { anyConstructed<Bot>().restrictChatMember(any(), any(), any(), any()) } answers {
      targets += arg<Long>(1)
      deadline.set(arg<Long>(3))
      success<Response<Boolean>?>(Response(true, true)) to null
    }
    every {
      anyConstructed<Bot>().sendMessage(any(), any(), replyParameters = any(), disableNotification = true)
    } answers {
      if (arg<ChatId>(0) == ChatId.fromId(otherChat.id)) otherChatReplied.countDown()
      Success(message(999, owner))
    }
    every {
      anyConstructed<Bot>().sendMessage(
        any(), any(), parseMode = ParseMode.HTML, linkPreviewOptions = any(),
        disableNotification = true, messageThreadId = any()
      )
    } returns Success(message(1000, owner))
    every { anyConstructed<Bot>().deleteMessage(any(), any()) } returns Success(true)
    every { anyConstructed<Bot>().sendDocument(any(), any<TelegramFile>()) } returns
      (success<Response<Message>?>(Response(message(1001, owner).copy(document = Document("index", "index")), true)) to null)
    every { anyConstructed<Bot>().setChatDescription(any(), any()) } returns
      (success<Response<Boolean>?>(Response(true, true)) to null)
    val glock = GlockBot(
      "test-token", ChatPermissions(canSendMessages = false), ofSeconds(300), 0, UTC,
      setOf(chat.id, otherChat.id), -999
    )
    val tasks = field(glock, "chatTasks") as ChatTaskDispatcher
    try {
      deliver(glock, Update(1, message = message(1, owner, "/statuette")))
      deliver(glock, Update(2, message = message(2, victim, "target")))
      assertTrue(firstRead.await(5, SECONDS))
      deliver(glock, Update(3, message = message(3, owner, "/shoot", message(2, victim))))
      deliver(glock, Update(4, message = message(4, owner, "/heal invalid", message(2, victim))))
      deliver(glock, Update(5, message = message(5, owner, "/shoot", message(2, victim))))
      deliver(glock, Update(6, message = message(6, owner, "/help").copy(chat = otherChat)))
      assertTrue(otherChatReplied.await(5, SECONDS))
      releaseRead.countDown()
      tasks.close()
      assertEquals(listOf(11L, 11L, 11L), targets)
      assertEquals(2_000_000_900, deadline.get())
    } finally {
      releaseRead.countDown()
      tasks.close()
      // Close real storage while its Telegram calls are still mocked, including its shutdown hook.
      try {
        (field(field(glock, "senderChatBans"), "storage") as TelegramBanStorage).close()
      } finally {
        unmockkConstructor(Bot::class)
      }
    }
  }
}
