package com.github.demidko.glock

import com.github.kotlintelegrambot.types.TelegramBotResult
import com.github.kotlintelegrambot.types.TelegramBotResult.Error.HttpError
import com.github.kotlintelegrambot.types.TelegramBotResult.Error.TelegramApi
import java.io.IOException
import java.lang.Thread.currentThread
import java.time.Clock
import java.time.Clock.systemUTC
import java.util.concurrent.ExecutionException

class SenderChatBans(
  private val api: SenderChatApi,
  private val storage: BanStorage,
  private val clock: Clock = systemUTC()
) {
  private val registryLock = Any()
  private val persistenceLock = Any()
  private val targetLocks = Array(64) { Any() }
  private var deadlines = storage.load()
  private var needsSave = false

  fun ban(chatId: Long, senderChatId: Long, durationSeconds: Long): Boolean =
    withTarget(chatId, senderChatId) { target ->
      val previous = deadline(target)
      val until = maxOf(previous ?: 0, clock.instant().epochSecond) + durationSeconds
      // Record before Telegram receives the ban: a timeout may hide a successful request.
      if (!persistPlan(target, until)) return@withTarget false
      val result = api.ban(chatId, senderChatId)
      if (succeeded(result)) {
        val appliedUntil = maxOf(previous ?: 0, clock.instant().epochSecond) + durationSeconds
        if (appliedUntil != until) replace(target, appliedUntil)
        return@withTarget true
      }
      if (result is TelegramApi || result is HttpError && result.httpCode in 400..499 && result.httpCode != 408) {
        replace(target, previous)
        // Channels have no readable individual ban state; persist a definite rejection before returning.
        flush()
      }
      false
    }

  fun unban(chatId: Long, senderChatId: Long): Boolean = withTarget(chatId, senderChatId) { target ->
    if (!succeeded(api.unban(chatId, senderChatId))) return@withTarget false
    if (deadline(target) != null) replace(target, null)
    true
  }

  fun remainingSeconds(chatId: Long, senderChatId: Long): Long {
    return ((deadline("$chatId:$senderChatId") ?: 0) - clock.instant().epochSecond).coerceAtLeast(0)
  }

  fun unbanExpired() {
    val expired = synchronized(registryLock) {
      deadlines.filterValues { it <= clock.instant().epochSecond }.keys.toList()
    }
    for (target in expired) {
      val (chatId, senderChatId) = target.split(':', limit = 2)
      withTarget(chatId.toLong(), senderChatId.toLong()) {
        val current = deadline(target) ?: return@withTarget
        if (current <= clock.instant().epochSecond &&
          succeeded(api.unban(chatId.toLong(), senderChatId.toLong()))) {
          replace(target, null)
        }
      }
    }
    flush()
  }

  private fun deadline(target: String): Long? = synchronized(registryLock) { deadlines[target] }

  private fun persistPlan(target: String, until: Long): Boolean = synchronized(persistenceLock) {
    val snapshot = synchronized(registryLock) { deadlines + (target to until) }
    val saved = save(snapshot)
    synchronized(registryLock) {
      if (saved) {
        // A different target may have been healed while this snapshot was being uploaded.
        deadlines = deadlines + (target to until)
        needsSave = deadlines != snapshot
      } else {
        // A failed publication may still have reached Telegram; repair it on the next cleanup.
        needsSave = true
      }
    }
    saved
  }

  private fun replace(target: String, until: Long?) = synchronized(registryLock) {
    deadlines = if (until == null) deadlines - target else deadlines + (target to until)
    needsSave = true
  }

  private fun flush() = synchronized(persistenceLock) {
    val snapshot = synchronized(registryLock) { if (needsSave) deadlines else null }
      ?: return@synchronized
    val saved = save(snapshot)
    synchronized(registryLock) { needsSave = !saved || deadlines != snapshot }
  }

  private inline fun <T> withTarget(chatId: Long, senderChatId: Long, action: (String) -> T): T {
    val target = "$chatId:$senderChatId"
    return synchronized(targetLocks[target.hashCode().and(Int.MAX_VALUE) % targetLocks.size]) { action(target) }
  }

  private fun succeeded(result: TelegramBotResult<Boolean>): Boolean = result.getOrNull() == true

  private fun save(updated: Map<String, Long>): Boolean {
    return try {
      storage.save(updated)
      true
    } catch (e: IOException) {
      ActionDiagnostics.error("storage.save", "entries=${updated.size} targets=${updated.keys.take(20)}", e)
      false
    } catch (e: ExecutionException) {
      ActionDiagnostics.error("storage.save", "entries=${updated.size} targets=${updated.keys.take(20)}", e)
      false
    } catch (e: IllegalStateException) {
      ActionDiagnostics.error("storage.save", "entries=${updated.size} targets=${updated.keys.take(20)}", e)
      false
    } catch (e: InterruptedException) {
      currentThread().interrupt()
      ActionDiagnostics.error("storage.save", "entries=${updated.size} targets=${updated.keys.take(20)}", e)
      false
    }
  }
}
