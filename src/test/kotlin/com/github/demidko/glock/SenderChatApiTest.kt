package com.github.demidko.glock

import com.github.kotlintelegrambot.types.TelegramBotResult.Error.HttpError
import com.github.kotlintelegrambot.types.TelegramBotResult.Error.InvalidResponse
import com.github.kotlintelegrambot.types.TelegramBotResult.Error.TelegramApi
import com.github.kotlintelegrambot.types.TelegramBotResult.Error.Unknown
import com.github.kotlintelegrambot.types.TelegramBotResult.Success
import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpServer.create
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue

class SenderChatApiTest {
  private lateinit var server: HttpServer
  private lateinit var api: SenderChatApi
  private val requests = LinkedBlockingQueue<Triple<String, String, String>>()
  @Volatile private var status = 200
  @Volatile private var response = """{"ok":true,"result":true}"""

  @BeforeEach
  fun startServer() {
    server = create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      exchange.use {
        requests.add(Triple(it.requestMethod, it.requestURI.path, it.requestBody.bufferedReader().readText()))
        val bytes = response.toByteArray()
        it.responseHeaders.set("Content-Type", "application/json")
        it.sendResponseHeaders(status, bytes.size.toLong())
        it.responseBody.write(bytes)
      }
    }
    server.start()
    api = SenderChatApi("test-token", "http://127.0.0.1:${server.address.port}/")
  }

  @AfterEach
  fun stopServer() {
    server.stop(0)
  }

  @Test
  fun `ban sends channel identifiers to the sender chat endpoint`() {
    assertThat(api.ban(-1001234567890, -1009876543210)).isEqualTo(Success(true))
    assertThat(requests.poll()).isEqualTo(
      Triple("POST", "/bottest-token/banChatSenderChat", "chat_id=-1001234567890&sender_chat_id=-1009876543210")
    )
  }

  @Test
  fun `unban uses the separate sender chat endpoint`() {
    assertThat(api.unban(-1001234567890, -1009876543210)).isEqualTo(Success(true))
    assertThat(requests.poll()).isEqualTo(
      Triple("POST", "/bottest-token/unbanChatSenderChat", "chat_id=-1001234567890&sender_chat_id=-1009876543210")
    )
  }

  @Test
  fun `HTTP errors are not successful bans`() {
    status = 400
    response = """{"ok":false,"error_code":400,"description":"Not enough rights"}"""

    assertThat(api.ban(-1, -2)).isEqualTo(HttpError(400, response))
  }

  @Test
  fun `Telegram errors in successful HTTP responses are not successful bans`() {
    response = """{"ok":false,"error_code":400,"description":"Not enough rights"}"""

    assertThat(api.ban(-1, -2)).isEqualTo(TelegramApi(400, "Not enough rights"))
  }

  @Test
  fun `missing result is not a successful ban`() {
    response = """{"ok":true}"""

    assertThat(api.ban(-1, -2)).isInstanceOf(InvalidResponse::class.java)
  }

  @Test
  fun `false result is not a successful ban`() {
    response = """{"ok":true,"result":false}"""

    assertThat(api.ban(-1, -2)).isInstanceOf(InvalidResponse::class.java)
  }

  @Test
  fun `unreadable response leaves the ban outcome unknown`() {
    response = "not json"

    assertThat(api.ban(-1, -2)).isInstanceOf(Unknown::class.java)
  }
}
