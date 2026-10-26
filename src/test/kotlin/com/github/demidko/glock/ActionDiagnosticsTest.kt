package com.github.demidko.glock

import com.github.kotlintelegrambot.entities.Chat
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.entities.User
import com.github.kotlintelegrambot.types.TelegramBotResult.Error.HttpError
import com.github.kotlintelegrambot.types.TelegramBotResult.Error.Unknown
import com.github.kotlintelegrambot.types.TelegramBotResult.Success
import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.net.InetSocketAddress
import java.util.Collections.synchronizedList
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class ActionDiagnosticsTest {
  private val logger = Logger.getLogger(ActionDiagnostics::class.java.name)
  private val records = synchronizedList(mutableListOf<LogRecord>())
  private var previousLevel: Level? = null
  private var previousParentHandlers = true
  private val handler = object : Handler() {
    override fun publish(record: LogRecord) { records.add(record) }
    override fun flush() = Unit
    override fun close() = Unit
  }
  private val chat = Chat(-10042, "supergroup", title = "Gunfighter lounge", username = "guns_chat")
  private val initiator = Message(
    100, from = User(22, false, "Alice", lastName = "Shooter", username = "alice_shooter"),
    date = 2_000_000_000, chat = chat, text = "/heal 1234", messageThreadId = 7
  )
  private val target = Message(
    99, from = User(11, false, "Bob", username = "bob_target"),
    date = 2_000_000_000, chat = chat, text = "PRIVATE_TARGET_BODY_NOT_NEEDED", messageThreadId = 7
  )

  @BeforeEach
  fun captureLogs() {
    previousLevel = logger.level
    previousParentHandlers = logger.useParentHandlers
    logger.level = Level.ALL
    logger.useParentHandlers = false
    handler.level = Level.ALL
    logger.addHandler(handler)
  }

  @AfterEach
  fun restoreLogs() {
    logger.removeHandler(handler)
    logger.level = previousLevel
    logger.useParentHandlers = previousParentHandlers
  }

  @Test
  fun `failure records command actor target chat topic and local call stack`() {
    ActionDiagnostics.withContext("heal", initiator, target) {
      ActionDiagnostics.error("restrictChatMember", "HTTP 400: USER_IS_AN_ADMINISTRATOR")
    }

    val record = records.single()
    assertThat(record.message).contains("restrictChatMember")
    assertThat(record.message).contains("USER_IS_AN_ADMINISTRATOR")
    assertThat(record.message).contains("/heal 1234")
    assertThat(record.message).contains("alice_shooter")
    assertThat(record.message).contains("Alice")
    assertThat(record.message).contains("bob_target")
    assertThat(record.message).contains("Gunfighter lounge")
    assertThat(record.message).contains("guns_chat")
    assertThat(record.message).contains("-10042")
    assertThat(record.message).contains("message_thread_id")
    assertThat(record.message).contains("7")
    assertThat(record.message).doesNotContain("PRIVATE_TARGET_BODY_NOT_NEEDED")
    assertThat(record.thrown).isNotNull()
    assertThat(record.thrown.stackTrace.any { it.className == javaClass.name }).isTrue()
  }

  @Test
  fun `source channel is recorded as actor rather than its fake user`() {
    val channel = Chat(-100200, "channel", title = "News publisher", username = "news_channel")
    val sentAsChannel = initiator.copy(senderChat = channel, from = User(136817688, true, "Channel_Bot"))

    ActionDiagnostics.withContext("shoot", sentAsChannel, target) {
      ActionDiagnostics.error("restrictChatMember", "HTTP 400")
    }

    assertThat(records.single().message).contains("news_channel")
    assertThat(records.single().message).contains("News publisher")
    assertThat(records.single().message).contains("-100200")
  }

  @Test
  fun `original exception stack and causes survive while token URLs are redacted`() {
    val token = "123456:SECRET_TEST_TOKEN"
    val cause = IOException("Request to https://api.telegram.org/bot$token/getChat failed")
    cause.stackTrace = arrayOf(StackTraceElement("fixture.Network", "call", "Network.kt", 12))
    val failure = IOException("Transport failed", cause)
    failure.stackTrace = arrayOf(StackTraceElement("fixture.Client", "execute", "Client.kt", 34))

    ActionDiagnostics.error("getChat", "Connection https://api.telegram.org/bot$token/getChat", failure)

    val record = records.single()
    val stack = StringWriter().also { record.thrown.printStackTrace(PrintWriter(it)) }.toString()
    assertThat(record.message + stack).doesNotContain(token)
    assertThat(stack).contains("Transport failed")
    assertThat(stack).contains("fixture.Client.execute(Client.kt:34)")
    assertThat(stack).contains("fixture.Network.call(Network.kt:12)")
    assertThat(stack).contains("IOException")
  }

  @Test
  fun `context is cleared after normal completion and exceptions`() {
    ActionDiagnostics.withContext("heal", initiator, target) {
      ActionDiagnostics.error("inside", "known failure")
    }
    ActionDiagnostics.error("outside", "next operation")
    assertThat(records[0].message).contains("alice_shooter")
    assertThat(records[1].message).doesNotContain("alice_shooter")
    assertThat(records[1].message).doesNotContain("/heal 1234")

    assertThrows<IllegalStateException> {
      ActionDiagnostics.withContext("heal", initiator, target) { error("aborted action") }
    }
    assertThat(records.last().message).contains("alice_shooter")
    assertThat(records.last().message).contains("Unhandled exception")
    assertThat(records.last().thrown.toString()).contains("aborted action")
    ActionDiagnostics.error("after exception", "next operation")
    assertThat(records.last().message).doesNotContain("alice_shooter")
    assertThat(records.last().message).doesNotContain("/heal 1234")
  }

  @Test
  fun `nested scopes restore the outer action context`() {
    val inner = initiator.copy(text = "/shoot", from = User(33, false, "Inner", username = "inner_actor"))
    ActionDiagnostics.withContext("heal", initiator, target) {
      ActionDiagnostics.withContext("shoot", inner, target) {
        ActionDiagnostics.error("inner", "failure")
      }
      ActionDiagnostics.error("outer", "failure")
    }

    assertThat(records[0].message).contains("inner_actor")
    assertThat(records[1].message).contains("alice_shooter")
    assertThat(records[1].message).doesNotContain("inner_actor")
  }

  @Test
  fun `member lookup HTTP errors retain server reason and originating context`() {
    val body = """{"ok":false,"error_code":400,"description":"Bad Request: participant not found"}"""
    withServer(400, body) { url ->
      ActionDiagnostics.withContext("shoot", initiator.copy(text = "/shoot"), target) {
        assertThat(UserPermissionsApi("test-token", url).getMember(chat.id, 11)).isEqualTo(HttpError(400, body))
      }
    }

    val record = records.single()
    assertThat(record.message).contains("getChatMember")
    assertThat(record.message).contains("participant not found")
    assertThat(record.message).contains("400")
    assertThat(record.message).contains("-10042")
    assertThat(record.message).contains("11")
    assertThat(record.message).contains("alice_shooter")
    assertThat(record.thrown).isNotNull()
  }

  @Test
  fun `channel ban HTTP errors have context and stack while success stays silent`() {
    val body = """{"ok":false,"error_code":400,"description":"Bad Request: not enough rights"}"""
    withServer(400, body) { url ->
      ActionDiagnostics.withContext("statuette", initiator.copy(text = "/statuette"), target) {
        assertThat(SenderChatApi("test-token", url).ban(chat.id, -100200)).isEqualTo(HttpError(400, body))
      }
    }
    assertThat(records.single().message).contains("banChatSenderChat")
    assertThat(records.single().message).contains("not enough rights")
    assertThat(records.single().message).contains("/statuette")
    assertThat(records.single().thrown).isNotNull()

    records.clear()
    withServer(200, """{"ok":true,"result":true}""") { url ->
      assertThat(SenderChatApi("test-token", url).unban(chat.id, -100200)).isEqualTo(Success(true))
    }
    assertThat(records).isEmpty()
  }

  @Test
  fun `malformed Telegram response records the parsing exception and call context`() {
    withServer(200, "not json") { url ->
      ActionDiagnostics.withContext("heal", initiator, target) {
        assertThat(UserPermissionsApi("test-token", url).restrict(chat.id, 11, MemberPermissions(), 0))
          .isInstanceOf(Unknown::class.java)
      }
    }

    val record = records.single()
    assertThat(record.message).contains("restrictChatMember")
    assertThat(record.message).contains("/heal 1234")
    assertThat(record.thrown).isNotNull()
    val stack = StringWriter().also { record.thrown.printStackTrace(PrintWriter(it)) }.toString()
    assertThat(stack).contains("Json")
    assertThat(record.message + stack).doesNotContain("/bottest-token/")
  }

  private fun withServer(status: Int, response: String, action: (String) -> Unit) {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      exchange.use {
        it.requestBody.readBytes()
        val bytes = response.toByteArray()
        it.responseHeaders.set("Content-Type", "application/json")
        it.sendResponseHeaders(status, bytes.size.toLong())
        it.responseBody.write(bytes)
      }
    }
    server.start()
    try {
      action("http://127.0.0.1:${server.address.port}/")
    } finally {
      server.stop(0)
    }
  }
}
