package com.github.demidko.glock

import com.github.kotlintelegrambot.network.Response
import com.github.kotlintelegrambot.types.TelegramBotResult
import com.github.kotlintelegrambot.types.TelegramBotResult.Error.HttpError
import com.github.kotlintelegrambot.types.TelegramBotResult.Error.InvalidResponse
import com.github.kotlintelegrambot.types.TelegramBotResult.Error.TelegramApi
import com.github.kotlintelegrambot.types.TelegramBotResult.Error.Unknown
import com.github.kotlintelegrambot.types.TelegramBotResult.Success
import com.google.gson.JsonParseException
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory.create
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST
import java.io.IOException

class SenderChatApi(apiKey: String, apiUrl: String = "https://api.telegram.org/") {

  private val service = Retrofit.Builder()
    .baseUrl("${apiUrl}bot$apiKey/")
    .addConverterFactory(create())
    .build()
    .create(Service::class.java)

  fun ban(chatId: Long, senderChatId: Long): TelegramBotResult<Boolean> {
    return execute(service.ban(chatId, senderChatId))
  }

  fun unban(chatId: Long, senderChatId: Long): TelegramBotResult<Boolean> {
    return execute(service.unban(chatId, senderChatId))
  }

  private fun execute(call: Call<Response<Boolean>>): TelegramBotResult<Boolean> {
    return try {
      val response = call.execute()
      val body = response.body()
      when {
        !response.isSuccessful -> HttpError(response.code(), response.errorBody()?.use { it.string() })
        body == null -> InvalidResponse(response.code(), response.message(), null)
        !body.ok -> TelegramApi(body.errorCode ?: response.code(), body.errorDescription ?: "Sender chat request failed")
        body.result != true -> InvalidResponse(response.code(), response.message(), body)
        else -> Success(true)
      }
    } catch (e: IOException) {
      Unknown(e)
    } catch (e: JsonParseException) {
      Unknown(e)
    }
  }

  private interface Service {
    @FormUrlEncoded
    @POST("banChatSenderChat")
    fun ban(@Field("chat_id") chatId: Long, @Field("sender_chat_id") senderChatId: Long): Call<Response<Boolean>>

    @FormUrlEncoded
    @POST("unbanChatSenderChat")
    fun unban(@Field("chat_id") chatId: Long, @Field("sender_chat_id") senderChatId: Long): Call<Response<Boolean>>
  }
}
