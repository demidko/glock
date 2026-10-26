package com.github.demidko.glock

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit.SECONDS

/** A Telegram message as Glock sees it: who sent it, where, and what it replies to. */
class Msg(val json: JsonObject) {
  val id: Long = json["message_id"].asLong
  val date: Long = json["date"].asLong
  val chat: JsonObject = json["chat"].asJsonObject
  val chatId: Long = chat["id"].asLong
  val from: JsonObject? = json["from"]?.asJsonObject
  val senderChat: JsonObject? = json["sender_chat"]?.asJsonObject
  val text: String? = json["text"]?.asString
  val threadId: Long? = json["message_thread_id"]?.asLong
  val replyTo: Msg? = json["reply_to_message"]?.let { Msg(it.asJsonObject) }
  val isChannelPost: Boolean = senderChat?.get("type")?.asString == "channel"
  val isBot: Boolean = from?.get("is_bot")?.asBoolean == true
  /** The user or channel that wrote it; anonymous administrators post as the group itself. */
  val senderId: Long? = senderChat?.get("id")?.asLong ?: from?.get("id")?.asLong
  val isService: Boolean = SERVICE_FIELDS.any(json::has)

  private companion object {
    val SERVICE_FIELDS = listOf(
      "new_chat_members", "left_chat_member", "new_chat_title", "new_chat_photo", "delete_chat_photo",
      "group_chat_created", "supergroup_chat_created", "channel_chat_created", "migrate_to_chat_id",
      "migrate_from_chat_id", "pinned_message", "forum_topic_created", "forum_topic_closed", "forum_topic_edited"
    )
  }
}

/** The handful of Bot API methods Glock needs, over one shared HTTP client. A failed call logs once and returns null. */
open class Telegram(token: String, apiUrl: String = "https://api.telegram.org") {
  private val base = "$apiUrl/bot$token/"
  private val http = OkHttpClient.Builder().callTimeout(10, SECONDS).build()
  private val polling = http.newBuilder().callTimeout(40, SECONDS).readTimeout(40, SECONDS).build()

  /** Long-polls for message updates; null means the request itself failed. */
  open fun updates(offset: Long): List<JsonObject>? =
    call(polling, "getUpdates", "offset" to offset, "timeout" to 30, "allowed_updates" to "[\"message\"]")
      ?.asJsonArray?.map { it.asJsonObject }

  /** Sends silent HTML and returns the new message id. */
  open fun send(chatId: Long, html: String, threadId: Long? = null, replyTo: Long? = null): Long? = call(
    http, "sendMessage", "chat_id" to chatId, "text" to html, "parse_mode" to "HTML", "disable_notification" to true,
    "link_preview_options" to "{\"is_disabled\":true}", "message_thread_id" to threadId,
    "reply_parameters" to replyTo?.let { "{\"message_id\":$it}" }
  )?.asJsonObject?.get("message_id")?.asLong

  open fun edit(chatId: Long, messageId: Long, html: String): Boolean = call(
    http, "editMessageText", "chat_id" to chatId, "message_id" to messageId, "text" to html, "parse_mode" to "HTML",
    "link_preview_options" to "{\"is_disabled\":true}"
  ) != null

  open fun pin(chatId: Long, messageId: Long): Boolean =
    call(http, "pinChatMessage", "chat_id" to chatId, "message_id" to messageId, "disable_notification" to true) != null

  open fun delete(chatId: Long, messageId: Long): Boolean =
    call(http, "deleteMessage", "chat_id" to chatId, "message_id" to messageId) != null

  open fun restrict(chatId: Long, userId: Long, permissions: JsonObject, until: Long): Boolean = call(
    http, "restrictChatMember", "chat_id" to chatId, "user_id" to userId, "permissions" to permissions,
    "use_independent_chat_permissions" to true, "until_date" to until
  ) != null

  open fun banChannel(chatId: Long, senderChatId: Long): Boolean =
    call(http, "banChatSenderChat", "chat_id" to chatId, "sender_chat_id" to senderChatId) != null

  open fun unbanChannel(chatId: Long, senderChatId: Long): Boolean =
    call(http, "unbanChatSenderChat", "chat_id" to chatId, "sender_chat_id" to senderChatId) != null

  /** The group's default member permissions. */
  open fun permissions(chatId: Long): JsonObject? = chat(chatId)?.get("permissions")?.asJsonObject

  /** The pinned message of a chat as id and text. */
  open fun pinned(chatId: Long): Pair<Long, String>? = chat(chatId)?.get("pinned_message")?.asJsonObject
    ?.let { it["message_id"].asLong to (it["text"]?.asString ?: "") }

  /** A cheap request that keeps the connection warm, so the first shot after a lull pays no handshake. */
  open fun ping() {
    call(http, "getMe")
  }

  private fun chat(chatId: Long): JsonObject? = call(http, "getChat", "chat_id" to chatId)?.asJsonObject

  private fun call(client: OkHttpClient, method: String, vararg params: Pair<String, Any?>): JsonElement? {
    val form = FormBody.Builder()
    for ((name, value) in params) if (value != null) form.add(name, value.toString())
    val request = Request.Builder().url(base + method).post(form.build()).build()
    return try {
      client.newCall(request).execute().use { response ->
        val body = JsonParser.parseString(response.body.string()).asJsonObject
        if (body["ok"]?.asBoolean == true) body["result"] else {
          warn("$method ${params.toMap()} -> HTTP ${response.code} ${body["description"]?.asString}")
          null
        }
      }
    } catch (e: IOException) {
      warn("$method ${params.toMap()} failed: $e")
      null
    } catch (e: RuntimeException) {
      warn("$method ${params.toMap()} returned an unreadable response: $e")
      null
    }
  }
}

internal fun warn(message: String) = System.err.println("W - $message")
