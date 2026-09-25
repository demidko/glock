package com.github.demidko.glock

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.Chat
import com.github.kotlintelegrambot.entities.ChatFullInfo
import com.github.kotlintelegrambot.entities.ChatId.Companion.fromId
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.entities.TelegramFile
import com.github.kotlintelegrambot.entities.TelegramFile.ByByteArray
import com.github.kotlintelegrambot.entities.files.Document
import com.github.kotlintelegrambot.entities.gifts.AcceptedGiftTypes
import com.github.kotlintelegrambot.network.Response
import com.github.kotlintelegrambot.types.TelegramBotResult.Success
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.RateLimiter
import com.google.common.util.concurrent.RateLimiter.create
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import retrofit2.Response.success
import java.io.IOException
import java.util.concurrent.ExecutionException

@Suppress("UnstableApiUsage")
class TelegramBanStorageTest {
  private val channelId = fromId(-100900)
  private val telegram = TelegramFiles()
  private val opened = mutableListOf<TelegramBanStorage>()

  @BeforeEach
  fun disableApiDelay() {
    val limiter = mockk<RateLimiter>()
    every { limiter.acquire() } returns 0.0
    mockkStatic(RateLimiter::class)
    every { create(any<Double>()) } returns limiter
  }

  @AfterEach
  fun closeStorage() {
    telegram.failUploads = false
    telegram.unavailableFiles.clear()
    try {
      opened.forEach(TelegramBanStorage::close)
    } finally {
      unmockkStatic(RateLimiter::class)
    }
  }

  @Test
  fun `new channel starts with no deadlines`() {
    assertThat(openStorage().load()).isEmpty()
  }

  @Test
  fun `empty persisted index remains a valid empty storage`() {
    openStorage().close()

    assertThat(openStorage().load()).isEmpty()
  }

  @Test
  fun `latest snapshot survives closing and reopening the published storage`() {
    val storage = openStorage()
    val first = mapOf("-10:-20" to 1000L)
    val second = mapOf("-10:-20" to 1300L, "-10:-30" to 1600L)

    storage.save(first)
    assertThat(storage.load()).isEqualTo(first)

    storage.save(second)
    storage.close()
    assertThat(openStorage().load()).isEqualTo(second)
  }

  @Test
  fun `empty snapshot removes persisted deadlines`() {
    val storage = openStorage()
    storage.save(mapOf("-10:-20" to 1000L))

    storage.save(emptyMap())
    storage.close()

    assertThat(openStorage().load()).isEmpty()
  }

  @Test
  fun `unreadable snapshot fails instead of restoring no deadlines`() {
    val deadlines = mapOf("-10:-20" to 1000L)
    openStorage().apply {
      save(deadlines)
      close()
    }
    val restored = openStorage()
    telegram.unavailableFiles.add(telegram.snapshotFileId())

    assertThrows<IllegalStateException> { restored.load() }

    telegram.unavailableFiles.clear()
    assertThat(restored.load()).isEqualTo(deadlines)
  }

  @Test
  fun `failed upload preserves the previous snapshot and can be retried`() {
    val storage = openStorage()
    val first = mapOf("-10:-20" to 1000L)
    val second = mapOf("-10:-20" to 1300L)
    storage.save(first)
    telegram.failUploads = true

    assertThrows<ExecutionException> { storage.save(second) }
    assertThat(storage.load()).isEqualTo(first)

    telegram.failUploads = false
    storage.save(second)
    storage.close()
    assertThat(openStorage().load()).isEqualTo(second)
  }

  private fun openStorage(): TelegramBanStorage {
    return TelegramBanStorage(telegram.bot, channelId).also(opened::add)
  }

  private inner class TelegramFiles {
    val bot = mockk<Bot>()
    var description: String? = null
    var failUploads = false
    val unavailableFiles = mutableSetOf<String>()
    private val files = mutableMapOf<String, ByteArray>()

    init {
      every { bot.getChat(channelId) } answers {
        Success(
          ChatFullInfo(
            id = -100900,
            type = "channel",
            accentColorId = 0,
            maxReactionCount = 0,
            acceptedGiftTypes = AcceptedGiftTypes(false, false, false, false),
            description = description
          )
        )
      }
      every { bot.downloadFileBytes(any()) } answers {
        val fileId = firstArg<String>()
        if (fileId in unavailableFiles) null else files[fileId]?.copyOf()
      }
      every { bot.sendDocument(channelId, any<TelegramFile>()) } answers {
        if (failUploads) {
          return@answers null to IOException("Upload failed")
        }
        val fileId = "file-${files.size + 1}"
        files[fileId] = secondArg<ByByteArray>().fileBytes.copyOf()
        val message = Message(
          messageId = files.size.toLong(),
          date = 0,
          chat = Chat(id = -100900, type = "channel"),
          document = Document(fileId = fileId, fileUniqueId = fileId)
        )
        success<Response<Message>?>(Response(result = message, ok = true)) to null
      }
      every { bot.setChatDescription(channelId, any()) } answers {
        description = secondArg<String>()
        success<Response<Boolean>?>(Response(result = true, ok = true)) to null
      }
    }

    fun snapshotFileId(): String {
      val index = files.getValue(checkNotNull(description))
      return Cbor.decodeFromByteArray<Map<String, String>>(index).values.single()
    }
  }
}
