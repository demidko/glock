package com.github.demidko.glock

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.dispatcher.handlers.HandleCommand
import com.github.kotlintelegrambot.dispatcher.handlers.HandleMessage
import com.github.kotlintelegrambot.dispatcher.message
import com.github.kotlintelegrambot.dispatcher.text
import com.github.kotlintelegrambot.entities.Chat
import com.github.kotlintelegrambot.entities.ChatId.Companion.fromId
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.entities.User
import java.lang.Thread.startVirtualThread
import java.time.Duration
import java.time.Duration.ofDays
import java.time.Duration.ofSeconds
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

class GlockBot(
  apiKey: String,
  private val restrictionsDuration: Duration,
  private val healingConstant: Long,
  private val healingTimeZone: ZoneId,
  private val boringChatIds: Set<Long>,
  storageChannelId: Long,
  userRestrictionsOverride: UserRestrictions? = null,
) {

  init {
    require(restrictionsDuration in ofSeconds(60)..ofDays(366)) {
      "Restriction duration must be between 60 seconds and 366 days; " +
        "Telegram treats deadlines less than 30 seconds away as permanent"
    }
  }

  private val bot =
    bot {
      token = apiKey
      dispatch {
        message(handleMessage(ChatOps::tryProcessStatuette))
        command("shoot", handleCommand(ChatOps::shoot))
        command("buckshot", handleCommand(ChatOps::buckshot))
        command("statuette", handleCommand(ChatOps::statuette))
        command("heal", handleCommand(ChatOps::heal))
        command("help", handleCommand(ChatOps::help))
        command("start", handleCommand(ChatOps::help))
        text {
          if (message.chat.id !in boringChatIds) {
            println("I - ${format(message.chat)} - ${format(message.from)} - $text")
          }
        }
      }
    }

  private val storage = TelegramBanStorage(bot, fromId(storageChannelId))
  private val senderChatBans = SenderChatBans(SenderChatApi(apiKey), storage)
  private val userRestrictions = userRestrictionsOverride ?: UserRestrictions(UserPermissionsApi(apiKey), storage)

  private val idToChatOps = ConcurrentHashMap<Long, ChatOps>()

  /** Deletes expired temporary messages; separate from [reconcileRestrictions] so journal uploads never delay it. */
  fun cleanTempMessages() {
    forEachChat { operations ->
      cleanSafely("temporaryMessages") { operations.cleanTempMessages() }
    }
  }

  /** Releases expired restrictions and publishes the deferred journal. */
  fun reconcileRestrictions() {
    cleanSafely("userRestrictions") { userRestrictions.restoreExpired() }
    cleanSafely("senderChatBans") { senderChatBans.unbanExpired() }
  }

  private fun cleanSafely(operation: String, action: () -> Unit) {
    try {
      action()
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      throw e
    } catch (e: Exception) {
      ActionDiagnostics.error(operation, "Cleanup failed; other cleanup tasks can continue", e)
    }
  }

  fun startPolling() {
    bot.startPolling()
  }

  private fun getChatOps(chat: Chat): ChatOps {
    return idToChatOps.computeIfAbsent(chat.id) {
      println("I - detected new chat ${format(chat)}")
      ChatOps(
        bot,
        fromId(chat.id),
        restrictionsDuration,
        healingConstant,
        healingTimeZone,
        senderChatBans,
        userRestrictions
      )
    }
  }

  private fun forEachChat(process: (ChatOps) -> Unit) {
    val chatsCount = idToChatOps.mappingCount()
    idToChatOps.forEachValue(chatsCount, process)
  }

  private fun handleMessage(method: ChatOps.(Message) -> Unit): HandleMessage {
    return {
      dispatch(message) { method(message) }
    }
  }

  private fun handleCommand(method: ChatOps.(Message, List<String>) -> Unit): HandleCommand {
    return {
      dispatch(message) { method(message, args) }
    }
  }

  private fun handleCommand(method: ChatOps.(Message) -> Unit): HandleCommand {
    return {
      dispatch(message) { method(message) }
    }
  }

  /** Every update runs on its own thread at once; the registries serialize per target themselves. */
  private fun dispatch(message: Message, action: ChatOps.() -> Unit) {
    startVirtualThread {
      try {
        getChatOps(message.chat).action()
      } catch (failure: Throwable) {
        ActionDiagnostics.error("dispatch", "chat_id=${message.chat.id} update failed", failure)
      }
    }
  }

  private fun format(user: User?): String {
    if (user == null) {
      return ""
    }
    return buildString {
      with(user) {
        append("id ").append(id).append(' ')
        append(firstName).append(' ')
        if (lastName != null) {
          append(lastName).append(' ')
        }
        if (username != null) {
          append('@').append(username).append(' ')
        }
      }
    }.trimEnd()
  }

  private fun format(chat: Chat): String {
    return buildString {
      with(chat) {
        append("id ").append(id).append(' ')
        if (firstName != null) {
          append(firstName).append(' ')
        }
        if (lastName != null) {
          append(lastName).append(' ')
        }
        if (username != null) {
          append('@').append(username).append(' ')
        }
      }
    }.trimEnd(' ', '-')
  }
}