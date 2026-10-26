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
        current = if (arg<Long>(3) == 0L) original.copy(tag = current.tag)
        else UserMemberState("restricted", true, permissions, arg(3), current.tag)
      }
      result
    }
  }

  @Test
  fun `shot preserves tag reactions and every non-message permission`() {
    val restrictions = restrictions()
    onRestrict = { assertThat(restrictions.remainingSeconds(chatId, userId)).isEqualTo(300) }

    assertThat(restrictions.mute(chatId, userId, 300)).isTrue()

    assertThat(storage.writes).isEqualTo(0)
    restrictions.restoreExpired()
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
    restrictions.restoreExpired()
    assertThat(storage.saved.getValue(key).originalState).isEqualTo(original)
    assertThat(storage.saved.getValue(key).previousUntilDate).isNull()
    verify(exactly = 1) { api.getPermissions(chatId) }
  }

  @Test
  fun `restart restores ownership and heal inherits current group defaults`() {
    shootAndPublish()
    defaults = defaults.copy(canInviteUsers = true, canSendPolls = false)

    val restarted = restrictions()
    assertThat(restarted.heal(chatId, userId)).isTrue()

    verify(exactly = 1) { api.restrict(chatId, userId, defaults, 0) }
    assertThat(current.status).isEqualTo("member")
    assertThat(current.tag).isEqualTo("Guide")
    restarted.restoreExpired()
    assertThat(storage.saved).isEmpty()
  }

  @Test
  fun `restrictions without a Glock record are overridden and stack on their deadline`() {
    val restrictions = restrictions()
    current = UserMemberState("restricted", true, defaults.copy(canSendPolls = false), 0)

    assertThat(restrictions.mute(chatId, userId, 300)).isTrue()
    assertThat(current.untilDate).isEqualTo(1_300)

    restrictions.heal(chatId, userId)
    current = UserMemberState("restricted", true, defaults.copy(canSendPolls = false), 2_000)
    assertThat(restrictions.mute(chatId, userId, 300)).isTrue()
    assertThat(current.untilDate).isEqualTo(2_300)
    assertThat(restrictions.remainingSeconds(chatId, userId)).isEqualTo(1_300)
  }

  @Test
  fun `healing sends the group defaults so the member is not left as an exception`() {
    val restrictions = restrictions()
    restrictions.mute(chatId, userId, 300)

    assertThat(restrictions.heal(chatId, userId)).isTrue()

    verify(exactly = 1) { api.restrict(chatId, userId, defaults, 0) }
    verify(exactly = 0) { api.restrict(chatId, userId, MemberPermissions(), any()) }
  }

  @Test
  fun `explicit healing clears unknown timed and permanent moderator restrictions`() {
    val restrictions = restrictions()
    for (deadline in listOf(0L, 2_000L)) {
      current = UserMemberState("restricted", true, defaults.copy(canSendPolls = false), deadline)

      assertThat(restrictions.heal(chatId, userId)).isTrue()
      assertThat(current.status).isEqualTo("member")
    }

    verify(exactly = 2) { api.restrict(chatId, userId, defaults, 0) }
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
    verify(exactly = 0) { api.restrict(chatId, userId, defaults, 0) }
  }

  @Test
  fun `explicit healing overrides later moderator changes`() {
    val restrictions = restrictions()
    restrictions.mute(chatId, userId, 300)
    current = current.copy(untilDate = 9_000, permissions = current.permissions!!.copy(canEditTag = false))

    assertThat(restrictions.heal(chatId, userId)).isTrue()

    assertThat(current.status).isEqualTo("member")
    restrictions.restoreExpired()
    assertThat(storage.saved).isEmpty()
  }

  @Test
  fun `an active Glock mute is extended without re-reading the member`() {
    val restrictions = restrictions()
    restrictions.mute(chatId, userId, 300)
    current = current.copy(permissions = current.permissions!!.copy(canReactToMessages = false))
    now = 1_100

    assertThat(restrictions.mute(chatId, userId, 300)).isTrue()

    assertThat(current.untilDate).isEqualTo(1_600)
    verify(exactly = 1) { api.getMember(chatId, userId) }
    verify(exactly = 2) { api.restrict(any(), any(), any(), any()) }
  }

  @Test
  fun `an expired record is re-read and a changed restriction is overridden`() {
    val restrictions = restrictions()
    restrictions.mute(chatId, userId, 300)
    current = current.copy(permissions = current.permissions!!.copy(canReactToMessages = false))
    now = 1_300

    assertThat(restrictions.mute(chatId, userId, 300)).isTrue()

    assertThat(current.untilDate).isEqualTo(1_600)
    verify(exactly = 2) { api.getMember(chatId, userId) }
    verify(exactly = 2) { api.restrict(any(), any(), any(), any()) }
  }

  @Test
  fun `chat permissions are read once per chat until the cache expires`() {
    val restrictions = restrictions()
    for (other in listOf(userId + 1, userId + 2)) {
      every { api.getMember(chatId, other) } returns Success(original)
      every { api.restrict(chatId, other, any(), any()) } returns Success(true)
    }
    restrictions.mute(chatId, userId, 300)
    restrictions.mute(chatId, userId + 1, 300)
    verify(exactly = 1) { api.getPermissions(chatId) }

    now = 1_301
    restrictions.mute(chatId, userId + 2, 300)

    verify(exactly = 2) { api.getPermissions(chatId) }
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
    shootAndPublish()
    now = 1_300
    current = original

    restrictions().restoreExpired()

    assertThat(storage.saved).isEmpty()
    verify(exactly = 0) { api.restrict(chatId, userId, defaults, 0) }
  }

  @Test
  fun `expiry retries an unchanged restriction when Telegram still reports it`() {
    shootAndPublish()
    now = 1_300
    result = HttpError(503, "Unavailable")

    restrictions().restoreExpired()
    assertThat(storage.saved).isNotEmpty()
    result = Success(true)
    restrictions().restoreExpired()

    assertThat(storage.saved).isEmpty()
    verify(exactly = 2) { api.restrict(chatId, userId, defaults, 0) }
  }

  @Test
  fun `failed heal returns false and keeps ownership for retry`() {
    val restrictions = restrictions()
    restrictions.mute(chatId, userId, 300)
    restrictions.restoreExpired()
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
  fun `a shot never waits for the journal and storage failures do not block it`() {
    val restrictions = restrictions()
    storage.failWrites = true

    assertThat(restrictions.mute(chatId, userId, 300)).isTrue()

    assertThat(current.untilDate).isEqualTo(1_300)
    assertThat(storage.writes).isEqualTo(0)
    restrictions.restoreExpired()
    assertThat(storage.saved).isEmpty()
    storage.failWrites = false
    restrictions.restoreExpired()
    assertThat(storage.saved.getValue(key).untilDate).isEqualTo(1_300)
    verify(exactly = 1) { api.restrict(any(), any(), any(), any()) }
  }

  @Test
  fun `one journal upload publishes every shot of a volley`() {
    val restrictions = restrictions()
    val otherUserId = userId + 1
    every { api.getMember(chatId, otherUserId) } returns Success(original)
    every { api.restrict(chatId, otherUserId, any(), any()) } returns Success(true)

    assertThat(restrictions.mute(chatId, userId, 300)).isTrue()
    assertThat(restrictions.mute(chatId, otherUserId, 300)).isTrue()
    restrictions.restoreExpired()

    assertThat(storage.writes).isEqualTo(1)
    assertThat(storage.saved.keys).containsExactly(key, "$chatId:$otherUserId")
    restrictions.restoreExpired()
    assertThat(storage.writes).isEqualTo(1)
  }

  @Test
  fun `ambiguous journal publication is republished by the next cleanup`() {
    val restrictions = restrictions()
    restrictions.mute(chatId, userId, 300)
    storage.failAfterWrite = true

    restrictions.restoreExpired()

    assertThat(storage.saved).containsKey(key)
    storage.failAfterWrite = false
    restrictions.restoreExpired()
    assertThat(storage.writes).isEqualTo(2)
    assertThat(storage.saved).containsKey(key)
  }

  @Test
  fun `definite rejection discards the planned first mute`() {
    result = HttpError(400, "Missing permission")
    val restrictions = restrictions()

    assertThat(restrictions.mute(chatId, userId, 300)).isFalse()

    restrictions.restoreExpired()
    assertThat(storage.saved).isEmpty()
  }

  @Test
  fun `rejected extension keeps the original deadline even if saving rollback fails`() {
    val restrictions = restrictions()
    restrictions.mute(chatId, userId, 300)
    restrictions.restoreExpired()
    result = HttpError(400, "Missing permission")
    onRestrict = { storage.failWrites = true }

    assertThat(restrictions.mute(chatId, userId, 300)).isFalse()
    assertThat(restrictions.remainingSeconds(chatId, userId)).isEqualTo(300)
    restrictions.restoreExpired()
    assertThat(storage.saved.getValue(key).untilDate).isEqualTo(1_300)
    assertThat(storage.saved.getValue(key).previousUntilDate).isNull()
    storage.failWrites = false
    onRestrict = {}
    result = Success(true)
    assertThat(restrictions().heal(chatId, userId)).isTrue()
  }

  @Test
  fun `timed out first mute stays recoverable after restart`() {
    result = Unknown(IOException("Timed out"))
    applyDespiteError = true

    assertThat(shootAndPublish()).isFalse()
    result = Success(true)
    val restarted = restrictions()
    assertThat(restarted.heal(chatId, userId)).isTrue()
    restarted.restoreExpired()
    assertThat(storage.saved).isEmpty()
  }

  @Test
  fun `HTTP request timeout retains both possible extension deadlines`() {
    shootAndPublish()
    result = HttpError(408, "Request timeout")
    applyDespiteError = true

    assertThat(shootAndPublish()).isFalse()

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
      shootAndPublish()
      result = Unknown(IOException("Timed out"))
      applyDespiteError = applied

      assertThat(shootAndPublish()).isFalse()
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
  fun `successful heal never attempts synchronous journal persistence`() {
    val restrictions = restrictions()
    restrictions.mute(chatId, userId, 300)
    restrictions.restoreExpired()
    val writesBeforeHeal = storage.writes
    storage.onSave = { error("Healing must not publish a journal snapshot") }

    assertThat(restrictions.heal(chatId, userId)).isTrue()

    assertThat(restrictions.remainingSeconds(chatId, userId)).isEqualTo(0)
    assertThat(storage.writes).isEqualTo(writesBeforeHeal)
    assertThat(storage.saved).containsKey(key)
  }

  @Test
  fun `restart before deferred heal persistence reconciles the already healed user`() {
    val restrictions = restrictions()
    restrictions.mute(chatId, userId, 300)
    restrictions.restoreExpired()
    restrictions.heal(chatId, userId)
    assertThat(storage.saved).containsKey(key)
    now = 1_300

    restrictions().restoreExpired()

    assertThat(storage.saved).isEmpty()
    verify(exactly = 1) { api.restrict(chatId, userId, defaults, 0) }
  }

  @Test
  fun `a blocked journal upload cannot block healing or shooting or resurrect a healed record`() {
    val restrictions = restrictions()
    restrictions.mute(chatId, userId, 300)
    val otherUserId = userId + 1
    every { api.getMember(chatId, otherUserId) } returns Success(original)
    every { api.restrict(chatId, otherUserId, any(), any()) } returns Success(true)
    val saving = CountDownLatch(1)
    val releaseSave = CountDownLatch(1)
    storage.onSave = {
      saving.countDown()
      check(releaseSave.await(5, SECONDS))
    }

    newFixedThreadPool(3).use { executor ->
      val upload = executor.submit(Callable { restrictions.restoreExpired() })
      try {
        assertThat(saving.await(5, SECONDS)).isTrue()
        val heal = executor.submit(Callable { restrictions.heal(chatId, userId) })
        val shot = executor.submit(Callable { restrictions.mute(chatId, otherUserId, 300) })
        assertThat(heal.get(1, SECONDS)).isTrue()
        assertThat(shot.get(1, SECONDS)).isTrue()
      } finally {
        releaseSave.countDown()
      }
      upload.get(5, SECONDS)
    }

    assertThat(restrictions.remainingSeconds(chatId, userId)).isEqualTo(0)
    assertThat(restrictions.remainingSeconds(chatId, otherUserId)).isEqualTo(300)
    storage.onSave = {}
    restrictions.restoreExpired()
    assertThat(storage.saved).doesNotContainKey(key)
    assertThat(storage.saved).containsKey("$chatId:$otherUserId")
  }

  @Test
  fun `failed cleanup persistence is retried without repeating a successful heal`() {
    val restrictions = restrictions()
    restrictions.mute(chatId, userId, 300)
    restrictions.restoreExpired()
    storage.failWrites = true
    restrictions.heal(chatId, userId)
    restrictions.restoreExpired()
    assertThat(storage.saved).containsKey(key)
    storage.failWrites = false

    restrictions.restoreExpired()

    assertThat(storage.saved).isEmpty()
    verify(exactly = 1) { api.restrict(chatId, userId, defaults, 0) }
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
  fun `slow member lookup moves the deadline instead of shortening the mute`() {
    for (delay in listOf(0L, 10L)) {
      now = 1_000
      current = original
      every { api.getMember(chatId, userId) } answers {
        now += delay
        Success(current)
      }

      assertThat(restrictions().mute(chatId, userId, 60)).isTrue()

      assertThat(current.untilDate).isEqualTo(1_060 + delay)
      assertThat(current.untilDate - now).isAtLeast(45)
    }
  }

  @Test
  fun `a clock jump before the request cannot create an indefinite mute`() {
    val restrictions = restrictions()
    every { clock.instant() } answers { ofEpochSecond(now).also { now += 300 } }

    assertThat(restrictions.mute(chatId, userId, 300)).isFalse()

    verify(exactly = 0) { api.restrict(any(), any(), any(), any()) }
    assertThat(restrictions.remainingSeconds(chatId, userId)).isEqualTo(0)
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
    shootAndPublish()
    result = Unknown(IOException("Timed out"))
    shootAndPublish()

    val restored = Cbor.decodeFromByteArray<Map<String, UserRestrictionRecord>>(Cbor.encodeToByteArray(storage.saved))

    assertThat(restored).isEqualTo(storage.saved)
  }

  private fun restrictions() = UserRestrictions(api, storage, clock)

  /** A shot followed by the maintenance cycle that publishes its journal. */
  private fun shootAndPublish(): Boolean = restrictions().run { mute(chatId, userId, 300).also { restoreExpired() } }

  private class MemoryStorage : UserRestrictionStorage {
    var saved: Map<String, UserRestrictionRecord> = emptyMap()
    var failWrites = false
    var failAfterWrite = false
    var onSave: () -> Unit = {}
    var writes = 0

    override fun loadUsers() = saved

    override fun saveUsers(restrictions: Map<String, UserRestrictionRecord>) {
      writes++
      check(!failWrites) { "Storage unavailable" }
      onSave()
      saved = restrictions.toMap()
      check(!failAfterWrite) { "Publication outcome unknown" }
    }
  }
}
