package com.github.demidko.glock

import com.github.kotlintelegrambot.types.TelegramBotResult
import com.github.kotlintelegrambot.types.TelegramBotResult.Error.HttpError
import com.github.kotlintelegrambot.types.TelegramBotResult.Error.InvalidResponse
import com.github.kotlintelegrambot.types.TelegramBotResult.Error.TelegramApi
import com.github.kotlintelegrambot.types.TelegramBotResult.Error.Unknown
import com.github.kotlintelegrambot.types.TelegramBotResult.Success
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.annotations.SerializedName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST
import java.io.IOException
import java.util.concurrent.TimeUnit.SECONDS

@Serializable
data class MemberPermissions(
  @SerializedName("can_send_messages") val canSendMessages: Boolean = true,
  @SerializedName("can_send_audios") val canSendAudios: Boolean = true,
  @SerializedName("can_send_documents") val canSendDocuments: Boolean = true,
  @SerializedName("can_send_photos") val canSendPhotos: Boolean = true,
  @SerializedName("can_send_videos") val canSendVideos: Boolean = true,
  @SerializedName("can_send_video_notes") val canSendVideoNotes: Boolean = true,
  @SerializedName("can_send_voice_notes") val canSendVoiceNotes: Boolean = true,
  @SerializedName("can_send_polls") val canSendPolls: Boolean = true,
  @SerializedName("can_send_other_messages") val canSendOtherMessages: Boolean = true,
  @SerializedName("can_add_web_page_previews") val canAddWebPagePreviews: Boolean = true,
  @SerializedName("can_react_to_messages") val canReactToMessages: Boolean = true,
  @SerializedName("can_edit_tag") val canEditTag: Boolean = true,
  @SerializedName("can_change_info") val canChangeInfo: Boolean = true,
  @SerializedName("can_invite_users") val canInviteUsers: Boolean = true,
  @SerializedName("can_pin_messages") val canPinMessages: Boolean = true,
  @SerializedName("can_manage_topics") val canManageTopics: Boolean = true
)

@Serializable
data class UserMemberState(
  val status: String,
  val isMember: Boolean,
  val permissions: MemberPermissions? = null,
  val untilDate: Long = 0,
  val tag: String? = null
)

/** The pinned Telegram client omits newer permissions, so use the complete Bot API schema here. */
class UserPermissionsApi(apiKey: String, apiUrl: String = "https://api.telegram.org/") {
  private val gson = Gson()
  private val permissionNames = gson.toJsonTree(MemberPermissions()).asJsonObject.keySet().toSet()
  private val service = Retrofit.Builder()
    .baseUrl("${apiUrl}bot$apiKey/")
    .client(
      OkHttpClient.Builder()
        .callTimeout(10, SECONDS)
        .connectTimeout(10, SECONDS)
        .readTimeout(10, SECONDS)
        .writeTimeout(10, SECONDS)
        .build()
    )
    .addConverterFactory(GsonConverterFactory.create(gson))
    .build()
    .create(Service::class.java)

  fun getMember(chatId: Long, userId: Long): TelegramBotResult<UserMemberState> {
    return execute(service.getMember(chatId, userId), "getChatMember", "chat_id=$chatId user_id=$userId") { result ->
      val member = result.objectValue()
      val status = member.requiredString("status")
      val tag = member.optionalString("tag")
      when (status) {
        "restricted" -> UserMemberState(
          status, member.requiredBoolean("is_member"), member.permissions(), member.requiredLong("until_date"), tag
        )
        "member", "administrator", "creator" -> UserMemberState(status, true, tag = tag)
        "left", "kicked" -> UserMemberState(status, false, tag = tag)
        else -> throw JsonParseException("Unknown chat member status")
      }
    }
  }

  fun getPermissions(chatId: Long): TelegramBotResult<MemberPermissions> {
    return execute(service.getChat(chatId), "getChat", "chat_id=$chatId") { result ->
      result.objectValue().get("permissions").objectValue().permissions()
    }
  }

  fun restrict(
    chatId: Long,
    userId: Long,
    permissions: MemberPermissions,
    untilDate: Long
  ): TelegramBotResult<Boolean> {
    val requestedPermissions = gson.toJson(permissions)
    return execute(
      service.restrict(chatId, userId, requestedPermissions, true, untilDate), "restrictChatMember",
      "chat_id=$chatId user_id=$userId until_date=$untilDate independent=true permissions=$requestedPermissions"
    ) { result ->
      if (!result.isJsonPrimitive || !result.asJsonPrimitive.isBoolean || !result.asBoolean) {
        throw JsonParseException("Restriction was not confirmed")
      }
      true
    }
  }

  private fun <T : Any> execute(
    call: Call<JsonObject>, operation: String, request: String, parse: (JsonElement) -> T
  ): TelegramBotResult<T> {
    return try {
      val response = call.execute()
      val body = response.body()
      when {
        !response.isSuccessful -> {
          val errorBody = response.errorBody()?.use { it.string() }
          ActionDiagnostics.error(operation, "$request HTTP ${response.code()} response=$errorBody")
          HttpError(response.code(), errorBody)
        }
        body == null -> {
          ActionDiagnostics.error(operation, "$request HTTP ${response.code()} empty response")
          InvalidResponse(response.code(), response.message(), null)
        }
        else -> try {
          if (!body.requiredBoolean("ok")) {
            val code = body.requiredLong("error_code").toInt()
            val description = body.requiredString("description")
            ActionDiagnostics.error(operation, "$request HTTP ${response.code()} Telegram $code: $description")
            TelegramApi(code, description)
          } else {
            val result = body.get("result") ?: throw JsonParseException("Missing result")
            Success(parse(result))
          }
        } catch (e: JsonParseException) {
          ActionDiagnostics.error(operation, "$request HTTP ${response.code()} invalid response", e)
          InvalidResponse(response.code(), e.message ?: "Invalid Telegram response", null)
        }
      }
    } catch (e: IOException) {
      ActionDiagnostics.error(operation, "$request request outcome unknown", e)
      Unknown(e)
    } catch (e: JsonParseException) {
      ActionDiagnostics.error(operation, "$request unreadable response; request outcome unknown", e)
      Unknown(e)
    }
  }

  private fun JsonObject.permissions(): MemberPermissions {
    // Missing fields must not be read as false and silently overwrite the user's existing rights.
    if (keySet().any { it.startsWith("can_") && it !in permissionNames && it != "can_send_media_messages" }) {
      throw JsonParseException("Unsupported chat permission")
    }
    return MemberPermissions(
      canSendMessages = requiredBoolean("can_send_messages"),
      canSendAudios = requiredBoolean("can_send_audios"),
      canSendDocuments = requiredBoolean("can_send_documents"),
      canSendPhotos = requiredBoolean("can_send_photos"),
      canSendVideos = requiredBoolean("can_send_videos"),
      canSendVideoNotes = requiredBoolean("can_send_video_notes"),
      canSendVoiceNotes = requiredBoolean("can_send_voice_notes"),
      canSendPolls = requiredBoolean("can_send_polls"),
      canSendOtherMessages = requiredBoolean("can_send_other_messages"),
      canAddWebPagePreviews = requiredBoolean("can_add_web_page_previews"),
      canReactToMessages = requiredBoolean("can_react_to_messages"),
      canEditTag = requiredBoolean("can_edit_tag"),
      canChangeInfo = requiredBoolean("can_change_info"),
      canInviteUsers = requiredBoolean("can_invite_users"),
      canPinMessages = requiredBoolean("can_pin_messages"),
      canManageTopics = requiredBoolean("can_manage_topics")
    )
  }

  private fun JsonElement?.objectValue(): JsonObject {
    if (this == null || !isJsonObject) throw JsonParseException("Expected JSON object")
    return asJsonObject
  }

  private fun JsonObject.requiredBoolean(name: String): Boolean {
    val value = get(name)
    if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) {
      throw JsonParseException("Missing or invalid $name")
    }
    return value.asBoolean
  }

  private fun JsonObject.requiredString(name: String): String {
    val value = get(name)
    if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
      throw JsonParseException("Missing or invalid $name")
    }
    return value.asString
  }

  private fun JsonObject.optionalString(name: String): String? {
    return if (!has(name)) null else requiredString(name)
  }

  private fun JsonObject.requiredLong(name: String): Long {
    val value = get(name)
    if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
      throw JsonParseException("Missing or invalid $name")
    }
    return value.asString.toLongOrNull() ?: throw JsonParseException("Invalid integer $name")
  }

  private interface Service {
    @FormUrlEncoded
    @POST("getChatMember")
    fun getMember(@Field("chat_id") chatId: Long, @Field("user_id") userId: Long): Call<JsonObject>

    @FormUrlEncoded
    @POST("getChat")
    fun getChat(@Field("chat_id") chatId: Long): Call<JsonObject>

    @FormUrlEncoded
    @POST("restrictChatMember")
    fun restrict(
      @Field("chat_id") chatId: Long,
      @Field("user_id") userId: Long,
      @Field("permissions") permissions: String,
      @Field("use_independent_chat_permissions") independent: Boolean,
      @Field("until_date") untilDate: Long
    ): Call<JsonObject>
  }
}
