package com.github.demidko.glock

import com.github.kotlintelegrambot.types.TelegramBotResult
import com.github.kotlintelegrambot.types.TelegramBotResult.Error.HttpError
import com.github.kotlintelegrambot.types.TelegramBotResult.Error.TelegramApi
import kotlinx.serialization.Serializable
import java.io.IOException
import java.time.Clock
import java.time.Clock.systemUTC
import java.util.concurrent.ExecutionException

interface UserRestrictionStorage {
  fun loadUsers(): Map<String, UserRestrictionRecord>
  fun saveUsers(restrictions: Map<String, UserRestrictionRecord>)
}

@Serializable
data class UserRestrictionRecord(
  val originalState: UserMemberState,
  val originalPermissions: MemberPermissions,
  val appliedPermissions: MemberPermissions,
  val untilDate: Long,
  // A timed-out extension may have left either deadline active on Telegram.
  val previousUntilDate: Long? = null
)

/** Owns only restrictions created by Glock; existing moderator restrictions are never adopted. */
class UserRestrictions(
  private val api: UserPermissionsApi,
  private val storage: UserRestrictionStorage,
  private val clock: Clock = systemUTC()
) {
  private val registryLock = Any()
  private val persistenceLock = Any()
  private val targetLocks = Array(64) { Any() }
  private var records = storage.loadUsers()
  private var needsSave = false

  fun mute(chatId: Long, userId: Long, durationSeconds: Long): Boolean = withTarget(chatId, userId) { target ->
    if (durationSeconds !in MIN_MUTE_SECONDS..MAX_MUTE_SECONDS) return@withTarget false
    val current = api.getMember(chatId, userId).getOrNull() ?: return@withTarget false
    var previous = record(target)
    if (previous != null) {
      if (owns(previous, current)) {
        // Resolve an uncertain request from the server before adding more time.
        previous = previous.copy(untilDate = current.untilDate, previousUntilDate = null)
      } else {
        forget(target)
        if (current.status != "member" || !current.isMember) return@withTarget false
        previous = null
      }
    }
    if (previous == null && (current.status != "member" || !current.isMember)) return@withTarget false
    val originalPermissions = previous?.originalPermissions
      ?: api.getPermissions(chatId).getOrNull() ?: return@withTarget false
    val now = clock.instant().epochSecond
    val remaining = maxOf(0, (previous?.untilDate ?: now) - now)
    if (durationSeconds > MAX_MUTE_SECONDS - remaining) return@withTarget false
    val until = now + remaining + durationSeconds
    val planned = UserRestrictionRecord(
      originalState = previous?.originalState ?: current,
      originalPermissions = originalPermissions,
      appliedPermissions = previous?.appliedPermissions ?: originalPermissions.muted(),
      untilDate = until,
      previousUntilDate = previous?.untilDate
    )
    // Publish ownership before the mutation: a timeout can hide a successful Telegram request.
    if (!persistPlan(target, planned)) return@withTarget false
    // Telegram turns deadlines less than 30 seconds away into permanent restrictions.
    // Leave transit margin beyond the mutation client's bounded 10-second call timeout.
    if (until - clock.instant().epochSecond !in MIN_SAFE_REMAINING_SECONDS..MAX_MUTE_SECONDS) {
      replace(target, previous)
      return@withTarget false
    }
    val result = api.restrict(chatId, userId, planned.appliedPermissions, until)
    if (succeeded(result)) {
      if (planned.previousUntilDate != null) replace(target, planned.copy(previousUntilDate = null))
      true
    } else {
      if (result is TelegramApi || result is HttpError && result.httpCode in 400..499 && result.httpCode != 408) {
        replace(target, previous)
      }
      false
    }
  }

  fun heal(chatId: Long, userId: Long): Boolean = withTarget(chatId, userId) { target ->
    // Explicit healing is absolute, including restrictions placed by moderators or older versions.
    release(chatId, userId, target)
  }

  fun remainingSeconds(chatId: Long, userId: Long): Long {
    return maxOf(0, (record("$chatId:$userId")?.untilDate ?: 0) - clock.instant().epochSecond)
  }

  fun restoreExpired() {
    val now = clock.instant().epochSecond
    val expired = synchronized(registryLock) {
      records.filterValues { minOf(it.untilDate, it.previousUntilDate ?: it.untilDate) <= now }.keys.toList()
    }
    for (target in expired) {
      val (chat, user) = target.split(':', limit = 2)
      val chatId = chat.toLong()
      val userId = user.toLong()
      withTarget(chatId, userId) {
        val saved = record(target) ?: return@withTarget
        val current = api.getMember(chatId, userId).getOrNull() ?: return@withTarget
        if (!owns(saved, current)) {
          forget(target)
        } else if (current.untilDate <= clock.instant().epochSecond) {
          release(chatId, userId, target)
        }
      }
    }
    flush()
  }

  private fun owns(saved: UserRestrictionRecord, current: UserMemberState): Boolean {
    return current.status == "restricted" && current.isMember &&
      current.permissions == saved.appliedPermissions &&
      (current.untilDate == saved.untilDate || current.untilDate == saved.previousUntilDate)
  }

  private fun release(chatId: Long, userId: Long, target: String): Boolean {
    // Lift individual restrictions and inherit today's group defaults.
    if (!succeeded(api.restrict(chatId, userId, MemberPermissions(), 0))) return false
    if (record(target) != null) forget(target)
    return true
  }

  private fun MemberPermissions.muted(): MemberPermissions = copy(
    canSendMessages = false,
    canSendAudios = false,
    canSendDocuments = false,
    canSendPhotos = false,
    canSendVideos = false,
    canSendVideoNotes = false,
    canSendVoiceNotes = false,
    canSendPolls = false,
    canSendOtherMessages = false,
    canAddWebPagePreviews = false
  )

  private fun record(target: String): UserRestrictionRecord? = synchronized(registryLock) { records[target] }

  private fun persistPlan(target: String, planned: UserRestrictionRecord): Boolean = synchronized(persistenceLock) {
    val snapshot = synchronized(registryLock) { records + (target to planned) }
    if (!save(snapshot)) {
      // A timeout may hide a published plan; maintenance must republish the authoritative state.
      synchronized(registryLock) { needsSave = true }
      return@synchronized false
    }
    synchronized(registryLock) {
      // A different target may have been healed while this snapshot was being published.
      records = records + (target to planned)
      needsSave = records != snapshot
    }
    true
  }

  private fun forget(target: String) = replace(target, null)

  private fun replace(target: String, value: UserRestrictionRecord?) = synchronized(registryLock) {
    // A confirmed Telegram result is visible immediately; maintenance publishes the journal later.
    records = if (value == null) records - target else records + (target to value)
    needsSave = true
  }

  private fun flush() = synchronized(persistenceLock) {
    val snapshot = synchronized(registryLock) { records.takeIf { needsSave } } ?: return@synchronized
    val saved = save(snapshot)
    synchronized(registryLock) {
      needsSave = !saved || records != snapshot
    }
  }

  private fun save(updated: Map<String, UserRestrictionRecord>): Boolean {
    return try {
      storage.saveUsers(updated)
      true
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      ActionDiagnostics.error("storage.saveUsers", "entries=${updated.size} targets=${updated.keys.take(20)}", e)
      false
    } catch (e: IOException) {
      ActionDiagnostics.error("storage.saveUsers", "entries=${updated.size} targets=${updated.keys.take(20)}", e)
      false
    } catch (e: ExecutionException) {
      ActionDiagnostics.error("storage.saveUsers", "entries=${updated.size} targets=${updated.keys.take(20)}", e)
      false
    } catch (e: IllegalStateException) {
      ActionDiagnostics.error("storage.saveUsers", "entries=${updated.size} targets=${updated.keys.take(20)}", e)
      false
    }
  }

  private fun succeeded(result: TelegramBotResult<Boolean>): Boolean = result.getOrNull() == true

  private inline fun <T> withTarget(chatId: Long, userId: Long, action: (String) -> T): T {
    val target = "$chatId:$userId"
    return synchronized(targetLocks[target.hashCode().and(Int.MAX_VALUE) % targetLocks.size]) { action(target) }
  }

  private companion object {
    const val MIN_MUTE_SECONDS = 60L
    const val MIN_SAFE_REMAINING_SECONDS = 45L
    const val MAX_MUTE_SECONDS = 366L * 24 * 60 * 60
  }
}
