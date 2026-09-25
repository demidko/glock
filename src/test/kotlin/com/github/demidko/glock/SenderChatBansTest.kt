package com.github.demidko.glock

import com.github.kotlintelegrambot.types.TelegramBotResult.Error.HttpError
import com.github.kotlintelegrambot.types.TelegramBotResult.Error.InvalidResponse
import com.github.kotlintelegrambot.types.TelegramBotResult.Error.Unknown
import com.github.kotlintelegrambot.types.TelegramBotResult.Success
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant.ofEpochSecond
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors.newFixedThreadPool
import java.util.concurrent.TimeUnit.SECONDS

class SenderChatBansTest {
  private val api = mockk<SenderChatApi>()
  private val clock = mockk<Clock>()
  private val storage = MemoryStorage()
  private var now = 1_000L
  private val chatId = -10042L
  private val senderChatId = -100101L

  @BeforeEach
  fun configure() {
    every { clock.instant() } answers { ofEpochSecond(now) }
    every { api.ban(any(), any()) } returns Success(true)
    every { api.unban(any(), any()) } returns Success(true)
  }

  @Test
  fun `ban is saved before Telegram receives it`() {
    every { api.ban(chatId, senderChatId) } answers {
      assertThat(storage.saved.values).containsExactly(1_300L)
      Success(true)
    }

    assertThat(bans().ban(chatId, senderChatId, 300)).isTrue()

    verify(exactly = 1) { api.ban(chatId, senderChatId) }
  }

  @Test
  fun `ban expires at its deadline and is removed only once`() {
    val bans = bans()
    bans.ban(chatId, senderChatId, 300)

    now = 1_299
    bans.unbanExpired()
    verify(exactly = 0) { api.unban(any(), any()) }
    now = 1_300
    bans.unbanExpired()
    bans.unbanExpired()

    verify(exactly = 1) { api.unban(chatId, senderChatId) }
    assertThat(storage.saved).isEmpty()
  }

  @Test
  fun `repeated hits extend the remaining duration`() {
    val bans = bans()
    bans.ban(chatId, senderChatId, 300)
    now = 1_100
    bans.ban(chatId, senderChatId, 300)
    now = 1_599
    bans.unbanExpired()
    verify(exactly = 0) { api.unban(any(), any()) }
    now = 1_600
    bans.unbanExpired()

    verify(exactly = 1) { api.unban(chatId, senderChatId) }
  }

  @Test
  fun `new hit after a missed deadline starts from the current time`() {
    val bans = bans()
    bans.ban(chatId, senderChatId, 300)
    now = 2_000
    bans.ban(chatId, senderChatId, 300)

    assertThat(storage.saved.values).containsExactly(2_300L)
  }

  @Test
  fun `restart recovers all chats without waiting for new messages`() {
    bans().apply {
      ban(chatId, senderChatId, 300)
      ban(chatId - 1, senderChatId, 600)
    }
    now = 1_600
    val writesBeforeCleanup = storage.writes

    bans().unbanExpired()

    verify(exactly = 1) { api.unban(chatId, senderChatId) }
    verify(exactly = 1) { api.unban(chatId - 1, senderChatId) }
    assertThat(storage.saved).isEmpty()
    assertThat(storage.writes).isEqualTo(writesBeforeCleanup + 1)
  }

  @Test
  fun `storage latency does not consume the active ban duration`() {
    storage.onSave = {
      now += 10
      storage.onSave = {}
    }
    val bans = bans()
    bans.ban(chatId, senderChatId, 300)
    bans.unbanExpired()
    assertThat(storage.saved.values).containsExactly(1_310L)

    now = 1_309
    bans.unbanExpired()
    verify(exactly = 0) { api.unban(any(), any()) }
    now = 1_310
    bans.unbanExpired()

    verify(exactly = 1) { api.unban(chatId, senderChatId) }
  }

  @Test
  fun `healing removes the persisted deadline`() {
    val bans = bans()
    bans.ban(chatId, senderChatId, 300)

    assertThat(bans.unban(chatId, senderChatId)).isTrue()
    now = 2_000
    bans().unbanExpired()

    verify(exactly = 1) { api.unban(chatId, senderChatId) }
    assertThat(storage.saved).isEmpty()
  }

  @Test
  fun `failed unban is retried after restart`() {
    bans().ban(chatId, senderChatId, 300)
    every { api.unban(chatId, senderChatId) } returns HttpError(503, "Unavailable") andThen Success(true)
    now = 1_300

    bans().unbanExpired()
    assertThat(storage.saved).isNotEmpty()
    bans().unbanExpired()

    verify(exactly = 2) { api.unban(chatId, senderChatId) }
    assertThat(storage.saved).isEmpty()
  }

  @Test
  fun `failed persistence prevents the ban`() {
    storage.failWrites = true

    assertThat(bans().ban(chatId, senderChatId, 300)).isFalse()

    verify(exactly = 0) { api.ban(any(), any()) }
  }

  @Test
  fun `failed persistence after unban keeps recovery possible`() {
    val bans = bans()
    bans.ban(chatId, senderChatId, 300)
    storage.failWrites = true
    bans.unban(chatId, senderChatId)
    assertThat(storage.saved).isNotEmpty()
    storage.failWrites = false
    now = 1_300

    bans().unbanExpired()

    assertThat(storage.saved).isEmpty()
  }

  @Test
  fun `new shot does not resurrect healed time when saving the heal failed`() {
    val bans = bans()
    bans.ban(chatId, senderChatId, 300)
    now = 1_100
    storage.failWrites = true
    bans.unban(chatId, senderChatId)
    storage.failWrites = false
    now = 1_150

    assertThat(bans.ban(chatId, senderChatId, 300)).isTrue()

    assertThat(storage.saved.values).containsExactly(1_450L)
  }

  @Test
  fun `failed heal persistence is retried without repeating a successful unban`() {
    val bans = bans()
    bans.ban(chatId, senderChatId, 300)
    storage.failWrites = true
    bans.unban(chatId, senderChatId)
    storage.failWrites = false

    bans.unbanExpired()

    assertThat(storage.saved).isEmpty()
    verify(exactly = 1) { api.unban(chatId, senderChatId) }
  }

  @Test
  fun `rejected ban does not schedule an unban`() {
    every { api.ban(chatId, senderChatId) } returns HttpError(400, "Not enough rights")

    assertThat(bans().ban(chatId, senderChatId, 300)).isFalse()
    now = 2_000
    bans().unbanExpired()

    verify(exactly = 0) { api.unban(any(), any()) }
    assertThat(storage.saved).isEmpty()
  }

  @Test
  fun `rejected extension preserves the previous deadline`() {
    val bans = bans()
    bans.ban(chatId, senderChatId, 300)
    every { api.ban(chatId, senderChatId) } returns HttpError(400, "Not enough rights")
    now = 1_100

    assertThat(bans.ban(chatId, senderChatId, 300)).isFalse()

    assertThat(storage.saved.values).containsExactly(1_300L)
  }

  @Test
  fun `timeout retains recovery deadline because Telegram may have applied the ban`() {
    every { api.ban(chatId, senderChatId) } returns Unknown(IOException("Timed out"))

    assertThat(bans().ban(chatId, senderChatId, 300)).isFalse()
    now = 1_300
    bans().unbanExpired()

    verify(exactly = 1) { api.unban(chatId, senderChatId) }
  }

  @Test
  fun `unreadable successful response retains recovery deadline`() {
    every { api.ban(chatId, senderChatId) } returns InvalidResponse(200, "OK", null)

    assertThat(bans().ban(chatId, senderChatId, 300)).isFalse()
    now = 1_300
    bans().unbanExpired()

    verify(exactly = 1) { api.unban(chatId, senderChatId) }
  }

  @Test
  fun `concurrent hits preserve both durations`() {
    val bans = bans()
    val start = CountDownLatch(1)
    newFixedThreadPool(2).use { executor ->
      val hits = (1..2).map {
        executor.submit(Callable {
          start.await()
          bans.ban(chatId, senderChatId, 300)
        })
      }
      start.countDown()
      hits.forEach { assertThat(it.get(5, SECONDS)).isTrue() }
    }

    assertThat(storage.saved.values).containsExactly(1_600L)
  }

  private fun bans() = SenderChatBans(api, storage, clock)

  private class MemoryStorage : BanStorage {
    var saved: Map<String, Long> = emptyMap()
    var failWrites = false
    var writes = 0
    var onSave: () -> Unit = {}

    override fun load() = saved

    override fun save(deadlines: Map<String, Long>) {
      check(!failWrites) { "Storage unavailable" }
      onSave()
      writes++
      saved = deadlines.toMap()
    }
  }
}
