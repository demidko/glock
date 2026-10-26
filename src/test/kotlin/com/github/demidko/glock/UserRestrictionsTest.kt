package com.github.demidko.glock

import com.github.kotlintelegrambot.types.TelegramBotResult
import com.github.kotlintelegrambot.types.TelegramBotResult.Error.HttpError
import com.github.kotlintelegrambot.types.TelegramBotResult.Error.Unknown
import com.github.kotlintelegrambot.types.TelegramBotResult.Success
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant.ofEpochSecond
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors.newFixedThreadPool
import java.util.concurrent.TimeUnit.SECONDS

class UserRestrictionsTest {
  private val api = mockk<UserPermissionsApi>()
  private val clock = mockk<Clock>()
  private val storage = MemoryStorage()
  private val chatId = -10042L
  private val userId = 11L
  private val key = "$chatId:$userId"
  private var now = 1_000L
  private val original = UserMemberState("member", true, tag = "Guide")
  private var current = original
  private var defaults = MemberPermissions(canChangeInfo = false, canInviteUsers = false, canPinMessages = false)
  private var result: TelegramBotResult<Boolean> = Success(true)
  private var applyDespiteError = false
  private var onRestrict: () -> Unit = {}

  @BeforeEach
  fun configure() {
    every { clock.instant() } answers { ofEpochSecond(now) }
    every { api.getMember(chatId, userId) } answers { Success(current) }
    every { api.getPermissions(chatId) } answers { Success(defaults) }
    every { api.restrict(chatId, userId, any(), any()) } answers {
      onRestrict()
      val permissions = arg<MemberPermissions>(2)
      if (result == Success(true) || applyDespiteError) {
        current = if (permissions == MemberPermissions()) original.copy(tag = current.tag)
        else UserMemberState("restricted", true, permissions, arg(3), current.tag)
      }
      result
    }
  }

  @Test
  fun `shot preserves tag reactions and every non-message permission`() {
    val restrictions = restrictions()
    onRestrict = { assertThat(storage.saved[key]?.originalState).isEqualTo(original) }

    assertThat(restrictions.mute(chatId, userId, 300)).isTrue()

    val saved = storage.saved.getValue(key)
    assertThat(saved.originalPermissions).isEqualTo(defaults)
    assertThat(saved.appliedPermissions).isEqualTo(defaults.copy(
      canSendMessages = false, canSendAudios = false, canSendDocuments = false, canSendPhotos = false,
      canSendVideos = false, canSendVideoNotes = false, canSendVoiceNotes = false, canSendPolls = false,
      canSendOtherMessages = false, canAddWebPagePreviews = false
    ))
    assertThat(current.tag).isEqualTo("Guide")
    assertThat(restrictions.remainingSeconds(chatId, userId)).isEqualTo(300)
  }

  @Test
  fun `a shot does not grant reactions or tags that the group prohibits`() {
    defaults = defaults.copy(canReactToMessages = false, canEditTag = false, canManageTopics = false)

    restrictions().mute(chatId, userId, 300)

    assertThat(current.permissions?.canReactToMessages).isFalse()
    assertThat(current.permissions?.canEditTag).isFalse()
    assertThat(current.permissions?.canManageTopics).isFalse()
  }

  @Test
  fun `repeated shots keep original snapshot and extend remaining time`() {
    val restrictions = restrictions()
    restrictions.mute(chatId, userId, 300)
    now = 1_100

    assertThat(restrictions.mute(chatId, userId, 300)).isTrue()

    assertThat(current.untilDate).isEqualTo(1_600)
    assertThat(restrictions.remainingSeconds(chatId, userId)).isEqualTo(500)
    assertThat(storage.saved.getValue(key).originalState).isEqualTo(original)
    assertThat(storage.saved.getValue(key).previousUntilDate).isNull()
    verify(exactly = 1) { api.getPermissions(chatId) }
  }

  @Test
  fun `restart restores ownership and heal inherits current group defaults`() {
    restrictions().mute(chatId, userId, 300)
    defaults = defaults.copy(canInviteUsers = true, canSendPolls = false)

    assertThat(restrictions().heal(chatId, userId)).isTrue()

    verify(exactly = 1) { api.restrict(chatId, userId, MemberPermissions(), 0) }
    assertThat(current.status).isEqualTo("member")
    assertThat(current.tag).isEqualTo("Guide")
    assertThat(storage.saved).isEmpty()
  }

  @Test
  fun `existing timed or permanent restrictions are not adopted by a shot`() {
    val restrictions = restrictions()
    for (deadline in listOf(0L, 2_000L)) {
      current = UserMemberState("restricted", true, defaults.copy(canSendPolls = false), deadline)

      assertThat(restrictions.mute(chatId, userId, 300)).isFalse()
    }

    assertThat(storage.saved).isEmpty()
    verify(exactly = 0) { api.restrict(any(), any(), any(), any()) }
  }

  @Test
  fun `explicit healing clears unknown timed and permanent moderator restrictions`() {
    val restrictions = restrictions()
    for (deadline in listOf(0L, 2_000L)) {
      current = UserMemberState("restricted", true, defaults.copy(canSendPolls = false), deadline)

      assertThat(restrictions.heal(chatId, userId)).isTrue()
      assertThat(current.status).isEqualTo("member")
    }

    verify(exactly = 2) { api.restrict(chatId, userId, MemberPermissions(), 0) }
    verify(exactly = 0) { api.getMember(any(), any()) }
  }

  @Test
  fun `administrators and absent members cannot be shot`() {
    val restrictions = restrictions()
    for (status in listOf("administrator", "creator", "left", "kicked")) {
      current = UserMemberState(status, status !in listOf("left", "kicked"))
      assertThat(restrictions.mute(chatId, userId, 300)).isFalse()
    }
    verify(exactly = 0) { api.restrict(any(), any(), any(), any()) }
  }

  @Test
  fun `changed moderator deadline is left intact by automatic expiry`() {
    val restrictions = restrictions()
    restrictions.mute(chatId, userId, 300)
    current = current.copy(untilDate = 9_000)

    now = 2_000
    restrictions.restoreExpired()

    assertThat(current.untilDate).isEqualTo(9_000)
    assertThat(storage.saved).isEmpty()
    verify(exactly = 0) { api.restrict(chatId, userId, MemberPermissions(), 0) }
  }

  @Test
  fun `explicit healing overrides later moderator changes`() {
    val restrictions = restrictions()
    restrictions.mute(chatId, userId, 300)
    current = current.copy(untilDate = 9_000, permissions = current.permissions!!.copy(canEditTag = false))

    assertThat(restrictions.heal(chatId, userId)).isTrue()

    assertThat(current.status).isEqualTo("member")
    assertThat(storage.saved).isEmpty()
  }

  @Test
  fun `changed moderator permissions are left intact by another shot`() {
    val restrictions = restrictions()
    restrictions.mute(chatId, userId, 300)
    current = current.copy(permissions = current.permissions!!.copy(canReactToMessages = false))

    assertThat(restrictions.mute(chatId, userId, 300)).isFalse()

    assertThat(current.permissions?.canReactToMessages).isFalse()
    assertThat(storage.saved).isEmpty()
    verify(exactly = 1) { api.restrict(any(), any(), any(), any()) }
  }

  @Test
  fun `changing a tag does not prevent healing and is never overwritten`() {
    val restrictions = restrictions()
    restrictions.mute(chatId, userId, 300)
    current = current.copy(tag = "New tag")

    assertThat(restrictions.heal(chatId, userId)).isTrue()

    assertThat(current.tag).isEqualTo("New tag")
  }

  @Test
  fun `expired Telegram restriction is reconciled without a redundant write`() {
    restrictions().mute(chatId, userId, 300)
    now = 1_300
    current = original

    restrictions().restoreExpired()

    assertThat(storage.saved).isEmpty()
    verify(exactly = 0) { api.restrict(chatId, userId, MemberPermissions(), 0) }
  }

  @Test
  fun `expiry retries an unchanged restriction when Telegram still reports it`() {
    restrictions().mute(chatId, userId, 300)
    now = 1_300
    result = HttpError(503, "Unavailable")

    restrictions().restoreExpired()
    assertThat(storage.saved).isNotEmpty()
    result = Success(true)
    restrictions().restoreExpired()

    assertThat(storage.saved).isEmpty()
    verify(exactly = 2) { api.restrict(chatId, userId, MemberPermissions(), 0) }
  }

  @Test
  fun `failed heal returns false and keeps ownership for retry`() {
    val restrictions = restrictions()
    restrictions.mute(chatId, userId, 300)
    result = HttpError(400, "Missing permission")

    assertThat(restrictions.heal(chatId, userId)).isFalse()
    assertThat(storage.saved).isNotEmpty()
  }

  @Test
  fun `failed lookup cannot lead to a mutation`() {
    every { api.getMember(chatId, userId) } returns Unknown(IOException("Unavailable"))

    assertThat(restrictions().mute(chatId, userId, 300)).isFalse()

    verify(exactly = 0) { api.restrict(any(), any(), any(), any()) }
  }

  @Test
  fun `failed write-ahead persistence prevents muting`() {
    storage.failWrites = true

    assertThat(restrictions().mute(chatId, userId, 300)).isFalse()

    verify(exactly = 0) { api.restrict(any(), any(), any(), any()) }
  }

  @Test
  fun `definite rejection discards the planned first mute`() {
    result = HttpError(400, "Missing permission")

    assertThat(restrictions().mute(chatId, userId, 300)).isFalse()

    assertThat(storage.saved).isEmpty()
  }

  @Test
  fun `rejected extension keeps the original deadline even if saving rollback fails`() {
    val restrictions = restrictions()
    restrictions.mute(chatId, userId, 300)
    result = HttpError(400, "Missing permission")
    onRestrict = { storage.failWrites = true }

    assertThat(restrictions.mute(chatId, userId, 300)).isFalse()
    assertThat(restrictions.remainingSeconds(chatId, userId)).isEqualTo(300)
    storage.failWrites = false
    onRestrict = {}
    result = Success(true)
    assertThat(restrictions().heal(chatId, userId)).isTrue()
  }

  @Test
  fun `timed out first mute stays recoverable after restart`() {
    result = Unknown(IOException("Timed out"))
    applyDespiteError = true

    assertThat(restrictions().mute(chatId, userId, 300)).isFalse()
    result = Success(true)
    assertThat(restrictions().heal(chatId, userId)).isTrue()
    assertThat(storage.saved).isEmpty()
  }

  @Test
  fun `HTTP request timeout retains both possible extension deadlines`() {
    restrictions().mute(chatId, userId, 300)
    result = HttpError(408, "Request timeout")
    applyDespiteError = true

    assertThat(restrictions().mute(chatId, userId, 300)).isFalse()

    assertThat(storage.saved.getValue(key).untilDate).isEqualTo(1_600)
    assertThat(storage.saved.getValue(key).previousUntilDate).isEqualTo(1_300)
    result = Success(true)
    assertThat(restrictions().heal(chatId, userId)).isTrue()
  }

  @Test
  fun `timed out extension preserves both outcomes and next shot uses actual deadline`() {
    for (applied in listOf(false, true)) {
      storage.saved = emptyMap()
      current = original
      result = Success(true)
      restrictions().mute(chatId, userId, 300)
      result = Unknown(IOException("Timed out"))
      applyDespiteError = applied

      assertThat(restrictions().mute(chatId, userId, 300)).isFalse()
      assertThat(storage.saved.getValue(key).previousUntilDate).isEqualTo(1_300)
      result = Success(true)
      applyDespiteError = false
      assertThat(restrictions().mute(chatId, userId, 300)).isTrue()
      assertThat(current.untilDate).isEqualTo(if (applied) 1_900 else 1_600)
    }
  }

  @Test
  fun `successful heal with failed persistence does not resurrect old time`() {
    val restrictions = restrictions()
    restrictions.mute(chatId, userId, 300)
    storage.failWrites = true

    assertThat(restrictions.heal(chatId, userId)).isTrue()
    assertThat(restrictions.remainingSeconds(chatId, userId)).isEqualTo(0)
    storage.failWrites = false
    now = 1_100
    assertThat(restrictions.mute(chatId, userId, 300)).isTrue()
    assertThat(current.untilDate).isEqualTo(1_400)
  }

  @Test
  fun `failed cleanup persistence is retried without repeating a successful heal`() {
    val restrictions = restrictions()
    restrictions.mute(chatId, userId, 300)
    storage.failWrites = true
    restrictions.heal(chatId, userId)
    storage.failWrites = false

    restrictions.restoreExpired()

    assertThat(storage.saved).isEmpty()
    verify(exactly = 1) { api.restrict(chatId, userId, MemberPermissions(), 0) }
  }

  @Test
  fun `unsafe durations and cumulative overflow never reach Telegram`() {
    val restrictions = restrictions()
    for (duration in listOf(-1L, 0L, 29L, 30L, 59L, Long.MAX_VALUE)) {
      assertThat(restrictions.mute(chatId, userId, duration)).isFalse()
    }
    assertThat(restrictions.mute(chatId, userId, 366L * 24 * 60 * 60)).isTrue()
    assertThat(restrictions.mute(chatId, userId, 60)).isFalse()

    verify(exactly = 1) { api.restrict(any(), any(), any(), any()) }
  }

  @Test
  fun `one-minute mute stays finite with no or ten seconds of persistence latency`() {
    for (delay in listOf(0L, 10L)) {
      now = 1_000
      current = original
      storage.saved = emptyMap()
      storage.onSave = { now += delay }

      assertThat(restrictions().mute(chatId, userId, 60)).isTrue()

      assertThat(current.untilDate).isEqualTo(1_060)
      assertThat(current.untilDate - now).isAtLeast(45)
    }
  }

  @Test
  fun `sixteen seconds of persistence latency leaves too little transit margin`() {
    storage.onSave = { now += 16 }

    assertThat(restrictions().mute(chatId, userId, 60)).isFalse()

    verify(exactly = 0) { api.restrict(any(), any(), any(), any()) }
  }

  @Test
  fun `persistence taking the entire duration cannot create an indefinite mute`() {
    storage.onSave = { now += 300 }

    assertThat(restrictions().mute(chatId, userId, 300)).isFalse()

    verify(exactly = 0) { api.restrict(any(), any(), any(), any()) }
  }

  @Test
  fun `concurrent shots preserve both additions`() {
    val restrictions = restrictions()
    val start = CountDownLatch(1)
    newFixedThreadPool(2).use { executor ->
      val shots = (1..2).map {
        executor.submit(Callable {
          start.await()
          restrictions.mute(chatId, userId, 300)
        })
      }
      start.countDown()
      shots.forEach { assertThat(it.get(5, SECONDS)).isTrue() }
    }

    assertThat(current.untilDate).isEqualTo(1_600)
  }

  @Test
  fun `snapshot serialization preserves both possible deadlines and new permission fields`() {
    restrictions().mute(chatId, userId, 300)
    result = Unknown(IOException("Timed out"))
    restrictions().mute(chatId, userId, 300)

    val restored = Cbor.decodeFromByteArray<Map<String, UserRestrictionRecord>>(Cbor.encodeToByteArray(storage.saved))

    assertThat(restored).isEqualTo(storage.saved)
  }

  private fun restrictions() = UserRestrictions(api, storage, clock)

  private class MemoryStorage : UserRestrictionStorage {
    var saved: Map<String, UserRestrictionRecord> = emptyMap()
    var failWrites = false
    var onSave: () -> Unit = {}

    override fun loadUsers() = saved

    override fun saveUsers(restrictions: Map<String, UserRestrictionRecord>) {
      check(!failWrites) { "Storage unavailable" }
      onSave()
      saved = restrictions.toMap()
    }
  }
}
