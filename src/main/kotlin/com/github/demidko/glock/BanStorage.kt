package com.github.demidko.glock

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.TelegramFile.ByByteArray
import com.google.common.util.concurrent.RateLimiter
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import java.io.Closeable
import java.io.IOException

interface BanStorage {
  fun load(): Map<String, Long>
  fun save(deadlines: Map<String, Long>)
}

/** Both registries share one index; every successful save publishes it before returning. */
@Suppress("UnstableApiUsage")
class TelegramBanStorage(private val bot: Bot, private val channel: ChatId) :
  BanStorage, UserRestrictionStorage, Closeable {
  private val limiter = RateLimiter.create(1.0)

  @Synchronized
  override fun load(): Map<String, Long> {
    val index = readIndex()
    val fileId = index[BANS_KEY] ?: return emptyMap()
    return decode(download(fileId))
  }

  @Synchronized
  override fun save(deadlines: Map<String, Long>) {
    publish(BANS_KEY, Cbor.encodeToByteArray(deadlines))
  }

  @Synchronized
  override fun loadUsers(): Map<String, UserRestrictionRecord> {
    val index = readIndex()
    val fileId = index[USERS_KEY] ?: return emptyMap()
    val snapshot = decode<UserSnapshot>(download(fileId))
    check(snapshot.version == 1) { "Unsupported user restriction snapshot version" }
    return snapshot.users
  }

  @Synchronized
  override fun saveUsers(restrictions: Map<String, UserRestrictionRecord>) {
    publish(USERS_KEY, Cbor.encodeToByteArray(UserSnapshot(users = restrictions)))
  }

  // The legacy library's CBOR index and sender-chat-bans value format remain readable.
  private fun readIndex(): Map<String, String> {
    val description = bot.getChat(channel).get().description
    if (description.isNullOrBlank()) return emptyMap()
    return decode(download(description))
  }

  private inline fun <reified T> decode(bytes: ByteArray): T {
    return try {
      Cbor.decodeFromByteArray(bytes)
    } catch (e: SerializationException) {
      throw IllegalStateException("Restriction storage contains invalid CBOR", e)
    }
  }

  private fun download(fileId: String): ByteArray {
    return checkNotNull(bot.downloadFileBytes(fileId)) { "Restriction storage file is unavailable" }
  }

  private fun publish(key: String, bytes: ByteArray) {
    // Re-read after a failed/ambiguous publication so another registry's updates are never lost.
    val index = readIndex()
    val valueFileId = upload(bytes)
    val indexFileId = upload(Cbor.encodeToByteArray(index + (key to valueFileId)))
    limiter.acquire()
    val (response, exception) = bot.setChatDescription(channel, indexFileId)
    if (exception != null) throw IOException("Failed to publish restriction storage index", exception)
    val body = response?.body()
    check(response?.isSuccessful == true && body?.ok == true && body.result == true) {
      "Telegram did not confirm publication of restriction storage index"
    }
  }

  private fun upload(bytes: ByteArray): String {
    limiter.acquire()
    val (response, exception) = bot.sendDocument(channel, ByByteArray(bytes))
    if (exception != null) throw IOException("Failed to upload restriction storage", exception)
    val body = response?.body()
    check(response?.isSuccessful == true && body?.ok == true) { "Telegram did not confirm restriction storage upload" }
    return checkNotNull(body.result?.document?.fileId) { "Restriction storage upload has no file ID" }
  }

  override fun close() = Unit

  @Serializable
  private data class UserSnapshot(val version: Int = 1, val users: Map<String, UserRestrictionRecord>)

  private companion object {
    const val BANS_KEY = "sender-chat-bans"
    const val USERS_KEY = "user-restrictions-v1"
  }
}
