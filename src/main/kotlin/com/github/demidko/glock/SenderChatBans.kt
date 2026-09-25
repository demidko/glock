package com.github.demidko.glock

import com.github.kotlintelegrambot.types.TelegramBotResult
import com.github.kotlintelegrambot.types.TelegramBotResult.Error.HttpError
import com.github.kotlintelegrambot.types.TelegramBotResult.Error.TelegramApi
import com.github.kotlintelegrambot.types.TelegramBotResult.Error.Unknown
import java.io.IOException
import java.lang.System.Logger.Level.WARNING
import java.lang.System.getLogger
import java.lang.Thread.currentThread
import java.time.Clock
import java.time.Clock.systemUTC
import java.util.concurrent.ExecutionException

class SenderChatBans(
  private val api: SenderChatApi,
  private val storage: BanStorage,
  private val clock: Clock = systemUTC()
) {
  private val logger = getLogger(SenderChatBans::class.java.name)
  private var deadlines = storage.load()
  private var needsSave = false

  @Synchronized
  fun ban(chatId: Long, senderChatId: Long, durationSeconds: Long): Boolean {
    val target = "$chatId:$senderChatId"
    val previous = deadlines
    val until = maxOf(deadlines[target] ?: 0, clock.instant().epochSecond) + durationSeconds
    // Record before Telegram receives the ban: a timeout may hide a successful request.
    if (!save(deadlines + (target to until))) {
      return false
    }
    val result = api.ban(chatId, senderChatId)
    if (succeeded(result, "ban", target)) {
      val appliedUntil = maxOf(previous[target] ?: 0, clock.instant().epochSecond) + durationSeconds
      if (appliedUntil != until) {
        deadlines = deadlines + (target to appliedUntil)
        needsSave = true
      }
      return true
    }
    if (result is TelegramApi || result is HttpError && result.httpCode in 400..499) {
      deadlines = previous
      needsSave = !save(previous)
    }
    return false
  }

  @Synchronized
  fun unban(chatId: Long, senderChatId: Long): Boolean {
    val target = "$chatId:$senderChatId"
    if (!succeeded(api.unban(chatId, senderChatId), "unban", target)) {
      return false
    }
    if (target in deadlines) {
      deadlines = deadlines - target
      needsSave = !save(deadlines)
    }
    return true
  }

  @Synchronized
  fun unbanExpired() {
    val expired = deadlines.filterValues { it <= clock.instant().epochSecond }
    for (target in expired.keys) {
      val (chatId, senderChatId) = target.split(':', limit = 2)
      if (succeeded(api.unban(chatId.toLong(), senderChatId.toLong()), "unban", target)) {
        deadlines = deadlines - target
        needsSave = true
      }
    }
    if (needsSave) {
      save(deadlines)
    }
  }

  private fun succeeded(result: TelegramBotResult<Boolean>, action: String, target: String): Boolean {
    return result.fold(
      ifSuccess = { it },
      ifError = {
        val message = "Failed to $action sender chat $target"
        if (it is Unknown) {
          logger.log(WARNING, message, it.exception)
        } else {
          logger.log(WARNING, "$message: $it")
        }
        false
      }
    )
  }

  private fun save(updated: Map<String, Long>): Boolean {
    return try {
      storage.save(updated)
      deadlines = updated
      needsSave = false
      true
    } catch (e: IOException) {
      logger.log(WARNING, "Failed to save sender chat bans", e)
      false
    } catch (e: ExecutionException) {
      logger.log(WARNING, "Failed to save sender chat bans", e)
      false
    } catch (e: IllegalStateException) {
      logger.log(WARNING, "Failed to save sender chat bans", e)
      false
    } catch (e: InterruptedException) {
      currentThread().interrupt()
      logger.log(WARNING, "Interrupted while saving sender chat bans", e)
      false
    }
  }
}
