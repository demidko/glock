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
import com.github.kotlintelegrambot.entities.ChatPermissions
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
  private val restrictions: ChatPermissions,
  private val restrictionsDuration: Duration,
  private val healingConstant: Long,
  private val healingTimeZone: ZoneId,
  private val interestingChatIds: Set<Long>,
) {

  init {
    require(restrictionsDuration in ofSeconds(30)..ofDays(366)) {
      "If user is restricted for more than 366 days or less than 30 seconds from the current time," +
        " they are considered to be restricted forever"
    }
  }

  private val bot =
    bot {
      token = apiKey
      dispatch {
        command("shoot", handleCommand(ChatOps::shoot))
        command("buckshot", handleCommand(ChatOps::buckshot))
        command("statuette", handleCommand(ChatOps::statuette))
        command("heal", handleCommand(ChatOps::heal))
        command("help", handleCommand(ChatOps::help))
        command("start", handleCommand(ChatOps::help))
        message(handleMessage(ChatOps::tryProcessStatuette))
        text {
          if (message.chat.id in interestingChatIds && text.isNotBlank()) {
            println("I - ${format(message.chat)} - ${format(message.from)} - $text")
          }
        }
      }
    }

  private val idToChatOps = ConcurrentHashMap<Long, ChatOps>()

  fun cleanTempMessages() {
    forEachChat(ChatOps::cleanTempMessages)
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
        restrictions,
        restrictionsDuration,
        healingConstant,
        healingTimeZone
      )
    }
  }

  private fun forEachChat(process: (ChatOps) -> Unit) {
    val chatsCount = idToChatOps.mappingCount()
    idToChatOps.forEachValue(chatsCount, process)
  }

  private fun handleMessage(method: ChatOps.(Message) -> Unit): HandleMessage {
    return {
      startVirtualThread(method, message)
    }
  }

  private fun handleCommand(method: ChatOps.(Message, List<String>) -> Unit): HandleCommand {
    return {
      startVirtualThread(method, message, args)
    }
  }

  private fun handleCommand(method: ChatOps.(Message) -> Unit): HandleCommand {
    return {
      startVirtualThread(method, message)
    }
  }

  private fun startVirtualThread(method: ChatOps.(Message) -> Unit, message: Message) {
    startVirtualThread {
      getChatOps(message.chat).method(message)
    }
  }

  private fun startVirtualThread(
    method: ChatOps.(Message, List<String>) -> Unit,
    message: Message,
    args: List<String>
  ) {
    startVirtualThread {
      getChatOps(message.chat).method(message, args)
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
        if (inviteLink != null) {
          append(inviteLink).append(' ')
        }
        if (bio?.isNotBlank() == true) {
          append(bio).append(" - ")
        }
        if (description?.isNotBlank() == true) {
          append(description).append(" - ")
        }
        if (pinnedMessage?.isNotBlank() == true) {
          append(pinnedMessage).append(" - ")
        }
      }
    }.trimEnd(' ', '-')
  }
}