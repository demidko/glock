package com.github.demidko.glock

import com.github.kotlintelegrambot.types.TelegramBotResult.Error.HttpError
import com.github.kotlintelegrambot.types.TelegramBotResult.Error.InvalidResponse
import com.github.kotlintelegrambot.types.TelegramBotResult.Error.TelegramApi
import com.github.kotlintelegrambot.types.TelegramBotResult.Error.Unknown
import com.github.kotlintelegrambot.types.TelegramBotResult.Success
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.LinkedBlockingQueue

class UserPermissionsApiTest {
  private lateinit var server: HttpServer
  private lateinit var api: UserPermissionsApi
  private val requests = LinkedBlockingQueue<Pair<String, Map<String, String>>>()
  @Volatile private var status = 200
  @Volatile private var response = """{"ok":true,"result":true}"""

  @BeforeEach
  fun startServer() {
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      exchange.use {
        val fields = it.requestBody.bufferedReader().readText().split('&').associate { field ->
          val pair = field.split('=', limit = 2)
          URLDecoder.decode(pair[0], UTF_8) to URLDecoder.decode(pair[1], UTF_8)
        }
        requests.add(it.requestURI.path to fields)
        val bytes = response.toByteArray(UTF_8)
        it.responseHeaders.set("Content-Type", "application/json")
        it.sendResponseHeaders(status, bytes.size.toLong())
        it.responseBody.write(bytes)
      }
    }
    server.start()
    api = UserPermissionsApi("test-token", "http://127.0.0.1:${server.address.port}/")
  }

  @AfterEach
  fun stopServer() {
    server.stop(0)
  }

  @Test
  fun `restriction sends every permission independently while preserving reactions and tags`() {
    val permissions = MemberPermissions(
      canSendMessages = false,
      canSendAudios = false,
      canSendDocuments = false,
      canSendPhotos = false,
      canSendVideos = false,
      canSendVideoNotes = false,
      canSendVoiceNotes = false,
      canSendPolls = false,
      canSendOtherMessages = false,
      canAddWebPagePreviews = false,
      canChangeInfo = false,
      canInviteUsers = false,
      canPinMessages = false
    )

    assertThat(api.restrict(-1001234567890, 1234567890123, permissions, 2000000300)).isEqualTo(Success(true))

    val (path, fields) = requests.remove()
    assertThat(path).isEqualTo("/bottest-token/restrictChatMember")
    assertThat(fields.filterKeys { it != "permissions" }).containsExactly(
      "chat_id", "-1001234567890",
      "user_id", "1234567890123",
      "use_independent_chat_permissions", "true",
      "until_date", "2000000300"
    )
    val sent = JsonParser.parseString(fields.getValue("permissions")).asJsonObject
    assertThat(sent.keySet()).containsExactlyElementsIn(permissionFields)
    for (field in permissionFields) {
      val preserved = field in setOf("can_react_to_messages", "can_edit_tag", "can_manage_topics")
      assertThat(sent[field].asJsonPrimitive.isBoolean).isTrue()
      assertThat(sent[field].asBoolean).isEqualTo(preserved)
    }
    assertThat(sent.has("can_send_media_messages")).isFalse()
  }

  @Test
  fun `lifting restrictions sends all modern permissions and zero deadline`() {
    assertThat(api.restrict(-1, 2, MemberPermissions(), 0)).isEqualTo(Success(true))

    val fields = requests.remove().second
    val sent = JsonParser.parseString(fields.getValue("permissions")).asJsonObject
    assertThat(sent.keySet()).containsExactlyElementsIn(permissionFields)
    assertThat(sent.entrySet().all { it.value.asBoolean }).isTrue()
    assertThat(fields["until_date"]).isEqualTo("0")
    assertThat(fields["use_independent_chat_permissions"]).isEqualTo("true")
  }

  @Test
  fun `reading a restricted member preserves independent rights tag and permanent deadline`() {
    val member = permissionJson().apply {
      addProperty("status", "restricted")
      addProperty("is_member", true)
      addProperty("until_date", 0)
      addProperty("tag", "Разработчик")
      addProperty("can_send_messages", false)
      addProperty("can_react_to_messages", true)
      addProperty("can_pin_messages", false)
      addProperty("can_edit_tag", true)
      addProperty("can_manage_topics", false)
    }
    response = """{"ok":true,"result":$member}"""

    assertThat(api.getMember(-123, 456)).isEqualTo(
      Success(
        UserMemberState(
          "restricted", true,
          MemberPermissions(canSendMessages = false, canPinMessages = false, canManageTopics = false),
          0, "Разработчик"
        )
      )
    )
    assertThat(requests.remove()).isEqualTo(
      "/bottest-token/getChatMember" to mapOf("chat_id" to "-123", "user_id" to "456")
    )
  }

  @Test
  fun `ordinary member does not interpret subscription deadline as restriction`() {
    response = """{"ok":true,"result":{"status":"member","until_date":2000000000,"tag":"Owner"}}"""

    assertThat(api.getMember(-1, 2)).isEqualTo(Success(UserMemberState("member", true, tag = "Owner")))
  }

  @Test
  fun `administrator and absent member statuses cannot be confused with regular users`() {
    for (memberStatus in listOf("creator", "administrator", "left", "kicked")) {
      response = """{"ok":true,"result":{"status":"$memberStatus"}}"""

      assertThat(api.getMember(-1, 2)).isEqualTo(
        Success(UserMemberState(memberStatus, memberStatus in setOf("creator", "administrator")))
      )
    }
  }

  @Test
  fun `chat defaults are read with independent permissions`() {
    val permissions = permissionJson().apply {
      addProperty("can_invite_users", false)
      addProperty("can_pin_messages", false)
      addProperty("can_react_to_messages", true)
      addProperty("can_edit_tag", false)
    }
    response = """{"ok":true,"result":{"permissions":$permissions}}"""

    assertThat(api.getPermissions(-1)).isEqualTo(
      Success(MemberPermissions(canInviteUsers = false, canPinMessages = false, canEditTag = false))
    )
    assertThat(requests.remove()).isEqualTo("/bottest-token/getChat" to mapOf("chat_id" to "-1"))
  }

  @Test
  fun `missing new permissions never silently become false`() {
    for (missing in listOf("can_react_to_messages", "can_edit_tag", "can_send_audios")) {
      val member = permissionJson().apply {
        addProperty("status", "restricted")
        addProperty("is_member", true)
        addProperty("until_date", 2000000300)
        remove(missing)
      }
      response = """{"ok":true,"result":$member}"""
      assertThat(api.getMember(-1, 2)).isInstanceOf(InvalidResponse::class.java)
      response = """{"ok":true,"result":{"permissions":$member}}"""
      assertThat(api.getPermissions(-1)).isInstanceOf(InvalidResponse::class.java)
    }
  }

  @Test
  fun `invalid or absent permission data fails closed`() {
    for (result in listOf("null", "{}", """{"permissions":{}}""", """{"permissions":false}""")) {
      response = """{"ok":true,"result":$result}"""
      assertThat(api.getPermissions(-1)).isInstanceOf(InvalidResponse::class.java)
    }
    val permissions = permissionJson().apply { addProperty("can_react_to_messages", "true") }
    response = """{"ok":true,"result":{"permissions":$permissions}}"""
    assertThat(api.getPermissions(-1)).isInstanceOf(InvalidResponse::class.java)
  }

  @Test
  fun `future permissions require an explicit schema update instead of being discarded`() {
    val permissions = permissionJson().apply { addProperty("can_send_future_content", true) }
    response = """{"ok":true,"result":{"permissions":$permissions}}"""

    assertThat(api.getPermissions(-1)).isInstanceOf(InvalidResponse::class.java)
  }

  @Test
  fun `legacy aggregate media flag does not override independent media rights`() {
    val permissions = permissionJson().apply {
      addProperty("can_send_media_messages", true)
      addProperty("can_send_photos", false)
    }
    response = """{"ok":true,"result":{"permissions":$permissions}}"""

    assertThat(api.getPermissions(-1)).isEqualTo(Success(MemberPermissions(canSendPhotos = false)))
  }

  @Test
  fun `unknown member status or missing restricted metadata fails closed`() {
    for (result in listOf("{}", """{"status":"new-status"}""", """{"status":"restricted"}""")) {
      response = """{"ok":true,"result":$result}"""
      assertThat(api.getMember(-1, 2)).isInstanceOf(InvalidResponse::class.java)
    }
  }

  @Test
  fun `HTTP error is not successful restoration`() {
    status = 403
    response = """{"ok":false,"error_code":403,"description":"Forbidden"}"""

    assertThat(api.restrict(-1, 2, MemberPermissions(), 0)).isEqualTo(HttpError(403, response))
  }

  @Test
  fun `Telegram error in successful HTTP response is not successful restoration`() {
    response = """{"ok":false,"error_code":400,"description":"Not enough rights"}"""

    assertThat(api.restrict(-1, 2, MemberPermissions(), 0)).isEqualTo(TelegramApi(400, "Not enough rights"))
  }

  @Test
  fun `missing false and non-boolean success results are rejected`() {
    for (body in listOf(
      """{"ok":true}""", """{"ok":true,"result":false}""", """{"ok":true,"result":"true"}""",
      """{"ok":true,"result":null}""", """{"result":true}"""
    )) {
      response = body
      assertThat(api.restrict(-1, 2, MemberPermissions(), 0)).isInstanceOf(InvalidResponse::class.java)
    }
  }

  @Test
  fun `unreadable JSON leaves request outcome unknown`() {
    response = "not json"

    assertThat(api.restrict(-1, 2, MemberPermissions(), 0)).isInstanceOf(Unknown::class.java)
  }

  private fun permissionJson() = JsonObject().apply {
    permissionFields.forEach { addProperty(it, true) }
  }

  private val permissionFields = listOf(
    "can_send_messages", "can_send_audios", "can_send_documents", "can_send_photos", "can_send_videos",
    "can_send_video_notes", "can_send_voice_notes", "can_send_polls", "can_send_other_messages",
    "can_add_web_page_previews", "can_react_to_messages", "can_edit_tag", "can_change_info", "can_invite_users",
    "can_pin_messages", "can_manage_topics"
  )
}
