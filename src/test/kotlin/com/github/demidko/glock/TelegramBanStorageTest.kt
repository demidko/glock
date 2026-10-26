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
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.decodeFromByteArray
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import retrofit2.Response.success
import java.io.IOException

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
  fun `latest snapshot survives reopening without a shutdown`() {
    val storage = openStorage()
    val first = mapOf("-10:-20" to 1000L)
    val second = mapOf("-10:-20" to 1300L, "-10:-30" to 1600L)

    storage.save(first)
    assertThat(storage.load()).isEqualTo(first)

    storage.save(second)
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

    assertThrows<IOException> { storage.save(second) }
    assertThat(storage.load()).isEqualTo(first)

    telegram.failUploads = false
    storage.save(second)
    assertThat(openStorage().load()).isEqualTo(second)
  }

  @Test
  fun `legacy CBOR index and channel bans remain readable beside user snapshots`() {
    val bans = mapOf("-10:-20" to 1300L)
    telegram.seedLegacy(bans)
    val storage = openStorage()
    val users = mapOf("-10:11" to userRecord())

    assertThat(storage.load()).isEqualTo(bans)
    storage.saveUsers(users)
    assertThat(openStorage().loadUsers()).isEqualTo(users)
    assertThat(openStorage().load()).isEqualTo(bans)

    storage.save(mapOf("-10:-30" to 1600L))
    assertThat(openStorage().loadUsers()).isEqualTo(users)
  }

  @Test
  fun `unreadable index fails closed instead of losing both registries`() {
    openStorage().save(mapOf("-10:-20" to 1000L))
    telegram.unavailableFiles.add(checkNotNull(telegram.description))

    assertThrows<IllegalStateException> { openStorage().load() }
    assertThrows<IllegalStateException> { openStorage().saveUsers(emptyMap()) }
  }

  @Test
  fun `corrupt index becomes a controlled storage failure`() {
    val storage = openStorage()
    storage.save(mapOf("-10:-20" to 1000L))
    telegram.corruptIndex()

    assertThrows<IllegalStateException> { storage.load() }
    assertThrows<IllegalStateException> { storage.saveUsers(emptyMap()) }
  }

  @Test
  fun `publication failure keeps last committed index`() {
    val storage = openStorage()
    val bans = mapOf("-10:-20" to 1000L)
    storage.save(bans)
    telegram.failPublications = true

    assertThrows<IOException> { storage.saveUsers(mapOf("-10:11" to userRecord())) }
    assertThat(openStorage().load()).isEqualTo(bans)
    assertThat(openStorage().loadUsers()).isEmpty()
  }

  @Test
  fun `ambiguous publication is reread before updating the other registry`() {
    val storage = openStorage()
    val users = mapOf("-10:11" to userRecord())
    telegram.failAfterPublication = true
    assertThrows<IOException> { storage.saveUsers(users) }
    telegram.failAfterPublication = false

    storage.save(mapOf("-10:-20" to 1000L))

    assertThat(openStorage().loadUsers()).isEqualTo(users)
    assertThat(openStorage().load()).containsEntry("-10:-20", 1000L)
  }

  private fun userRecord() = UserRestrictionRecord(
    originalState = UserMemberState("member", true, tag = "Member"),
    originalPermissions = MemberPermissions(),
    appliedPermissions = MemberPermissions(canSendMessages = false),
    untilDate = 1300L
  )

  private fun openStorage(): TelegramBanStorage {
    return TelegramBanStorage(telegram.bot, channelId).also(opened::add)
  }

  private inner class TelegramFiles {
    val bot = mockk<Bot>()
    var description: String? = null
    var failUploads = false
    var failPublications = false
    var failAfterPublication = false
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
        if (failPublications) return@answers null to IOException("Publication failed")
        description = secondArg<String>()
        if (failAfterPublication) return@answers null to IOException("Publication outcome unknown")
        success<Response<Boolean>?>(Response(result = true, ok = true)) to null
      }
    }

    fun corruptIndex() {
      files[checkNotNull(description)] = byteArrayOf(0xff.toByte())
    }

    fun seedLegacy(bans: Map<String, Long>) {
      files["legacy-value"] = Cbor.encodeToByteArray(bans)
      files["legacy-index"] = Cbor.encodeToByteArray(mapOf("sender-chat-bans" to "legacy-value"))
      description = "legacy-index"
    }

    fun snapshotFileId(): String {
      val index = files.getValue(checkNotNull(description))
      return Cbor.decodeFromByteArray<Map<String, String>>(index).values.single()
    }
  }
}
