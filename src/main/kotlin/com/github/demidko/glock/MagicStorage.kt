package com.github.demidko.glock

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ChatId.Companion.fromChannelUsername
import com.github.kotlintelegrambot.entities.ChatId.Companion.fromId
import com.github.kotlintelegrambot.entities.TelegramFile.ByByteArray
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import java.io.Closeable
import java.lang.Runtime.getRuntime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors.newSingleThreadExecutor
import java.util.concurrent.Future

/**
 * Immutable nosql database in your Telegram channel.
 *
 * @param bot Telegram bot.
 * See [documentation](https://github.com/kotlin-telegram-bot/kotlin-telegram-bot)
 * @param channel Telegram channel.
 * Use [fromId] or [fromChannelUsername]. The [bot] must be admin of this channel.
 * Do not change the channel description or files!
 *
 */
class MagicStorage(private val bot: Bot, private val channel: ChatId) : Closeable {

  /**
   * Map: your key -> bot file id
   */
  private val keyToTelegramFileId: MutableMap<Long, String> = run {
    val fileId = bot.getChat(channel).get().description ?: return@run ConcurrentHashMap()
    val bytes = bot.downloadFileBytes(fileId) ?: return@run ConcurrentHashMap()
    val map = Cbor.decodeFromByteArray<Map<Long, String>>(bytes)
    ConcurrentHashMap(map)
  }

  /**
   * Single thread to safe execution order
   */
  private val atomicExecutor = newSingleThreadExecutor()

  /**
   * Shutdown handler to save [keyToTelegramFileId] to [channel]
   */
  private val onShutdown = Thread {
    atomicExecutor.submit {
      val map = keyToTelegramFileId.toMap()
      val telegramFile = Cbor.encodeToByteArray(map).let(::ByByteArray)
      val fileId = bot.sendDocument(channel, telegramFile).first?.body()?.result?.document?.fileId!!
      val isSuccessfully = bot.setChatDescription(channel, fileId).first?.isSuccessful!!
      check(isSuccessfully) { "Can't save file id $fileId" }
    }.get()
  }.apply(getRuntime()::addShutdownHook)

  val size get() = keyToTelegramFileId.size

  fun remove(k: Long): Future<*>? {
    return atomicExecutor.submit {
      keyToTelegramFileId.remove(k)
    }
  }

  val keys get() = keyToTelegramFileId.keys

  /**
   * Save your binary value
   * @param k key to value
   * @param v see [Telegram Bot API limits](https://core.telegram.org/bots/faq#handling-media)
   */
  fun setBinary(k: Long, v: ByteArray): Future<*>? {
    return atomicExecutor.submit {
      val telegramFile = ByByteArray(v)
      keyToTelegramFileId[k] = bot.sendDocument(channel, telegramFile).first?.body()?.result?.document?.fileId!!
    }
  }

  inline operator fun <reified V> get(k: Long): V? {
    val bytes = getBinary(k) ?: return null
    return Cbor.decodeFromByteArray<V>(bytes)
  }

  /**
   * @param k key to value
   * @param V storable value type. Should be [basic](https://kotlinlang.org/docs/basic-types.html) or annotated with [Serializable]
   * @param v see [Telegram Bot API limits](https://core.telegram.org/bots/faq#handling-media)
   */
  inline operator fun <reified V> set(k: Long, v: V): Future<*>? {
    return setBinary(k, Cbor.encodeToByteArray(v))
  }

  /**
   * Download your binary value
   * @param k your key
   * @return value bytes
   */
  fun getBinary(k: Long): ByteArray? {
    return keyToTelegramFileId[k]?.let(bot::downloadFileBytes)
  }

  override fun close() {
    onShutdown.start()
    onShutdown.join()
    getRuntime().removeShutdownHook(onShutdown)
  }
}