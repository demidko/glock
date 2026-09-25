package com.github.demidko.glock

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ChatPermissions
import com.github.kotlintelegrambot.entities.LinkPreviewOptions
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.entities.ParseMode.HTML
import com.github.kotlintelegrambot.entities.ReplyParameters
import com.github.kotlintelegrambot.types.TelegramBotResult.Error.Unknown
import org.apache.commons.collections4.QueueUtils.synchronizedQueue
import org.apache.commons.collections4.queue.CircularFifoQueue
import java.lang.System.Logger.Level.WARNING
import java.lang.System.getLogger
import java.time.Duration
import java.time.Duration.ofSeconds
import java.time.Instant.now
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random.Default.nextInt
import kotlin.random.Random.Default.nextLong

/**
 * Class for sequential, secure, atomic operations in a single chat
 */
class ChatOps(
  private val bot: Bot,
  private val chatId: ChatId,
  private val restrictions: ChatPermissions,
  private val restrictionsDuration: Duration,
  private val healingConstant: Long,
  private val healingTimeZone: ZoneId,
  private val senderChatBans: SenderChatBans
) {

  private val logger = getLogger(ChatOps::class.java.name)
  private val messagesToLifetimes = ConcurrentHashMap<Long, Long>()
  private val recentMessages = synchronizedQueue(CircularFifoQueue<Message>(12))
  private val statuettes = ConcurrentLinkedQueue<Statuette>()

  private data class Statuette(val messageId: Long, val gunfighter: Message)

  fun cleanTempMessages() {
    val tempMessagesCount = messagesToLifetimes.mappingCount()
    messagesToLifetimes.forEach(tempMessagesCount, ::tryRemoveMessage)
  }

  fun help(m: Message) {
    val dialogLifetime = ofSeconds(20)
    markAsTemp(m, dialogLifetime)
    val rules =
      """
        Matthew 26:52 for all they that take the sword shall perish with the sword.
      """.trimIndent()
    reply(m, rules, Temp(dialogLifetime))
  }

  fun heal(healerMessage: Message, args: List<String>) {
    val target = healerMessage.replyToMessage ?: return
    val targetId = senderId(target) ?: return
    val senderChat = target.senderChat
    if (senderChat != null && senderChat.type != "channel") {
      return
    }
    val magicCode = extractMagicCode(args) ?: return
    if (!isHealingCode(magicCode)) {
      markAsTemp(healerMessage)
      return
    }
    if (senderChat != null) {
      if (!senderChatBans.unban(target.chat.id, targetId)) {
        markAsTemp(healerMessage)
        return
      }
    } else {
      bot.restrictChatMember(
        chatId, targetId, ChatPermissions(
          canSendMessages = true,
          canSendMediaMessages = true,
          canSendPolls = true,
          canSendOtherMessages = true,
          canAddWebPagePreviews = true,
          canChangeInfo = true,
          canInviteUsers = true,
          canPinMessages = true
        )
      )
    }
    val emoji = setOf("💊", "💉", "🚑")
    reply(target, emoji.random())
    markAsTemp(healerMessage)
  }

  private fun extractMagicCode(args: List<String>): Long? {
    return args.singleOrNull()?.toLong()
  }

  private fun isHealingCode(code: Long): Boolean {
    val time = LocalTime.now(healingTimeZone)
    val hour = time.hour
    val minute = time.minute
    // Congratulations, you've just found the secret healing code!
    // Don't tell anyone about it, please.
    val verification = "${hour}${minute}".toLong() * healingConstant
    return code == verification
  }

  fun statuette(gunfighterMessage: Message) {
    val statuetteId = reply(gunfighterMessage, "🗿", Persistent)
    if (statuetteId != null) {
      statuettes += Statuette(statuetteId, gunfighterMessage)
    }
    markAsTemp(gunfighterMessage)
  }

  fun tryProcessStatuette(message: Message) {
    val statuette = statuettes.poll()
    if (statuette == null) {
      recentMessages += message
      return
    }
    bot.deleteMessage(chatId, statuette.messageId)
    hurt(statuette.gunfighter, message, restrictionsDuration.seconds, "💥")
  }

  fun buckshot(gunfighterMessage: Message) {
    val gunfighterId = senderId(gunfighterMessage) ?: return
    val targetMessages = recentMessages.filter { senderId(it) != gunfighterId }
    if (targetMessages.isEmpty()) {
      markAsTemp(gunfighterMessage)
      return
    }
    val emoji = setOf("💥", "🗯️", "⚡️")
    if (targetMessages.size == 1) {
      hurt(gunfighterMessage, targetMessages.random(), restrictionsDuration.seconds, emoji.random())
      markAsTemp(gunfighterMessage)
      return
    }
    val targetsCount = nextInt(2, targetMessages.size + 1)
    for (t in 1..targetsCount) {
      val target = targetMessages.random()
      val restrictionsDurationSec = nextLong(45, restrictionsDuration.seconds * 2 + 1)
      hurt(gunfighterMessage, target, restrictionsDurationSec, emoji.random())
    }
    markAsTemp(gunfighterMessage)
  }

  fun shoot(gunfighterMessage: Message) {
    val target = gunfighterMessage.replyToMessage
    if (target == null) {
      markAsTemp(gunfighterMessage)
      return
    }
    hurt(gunfighterMessage, target, restrictionsDuration.seconds, "💥")
    markAsTemp(gunfighterMessage)
  }

  private fun tryRemoveMessage(messageId: Long, epochSecond: Long) {
    if (isLifetimeExceeded(epochSecond)) {
      bot.deleteMessage(chatId, messageId)
      messagesToLifetimes.remove(messageId)
    }
  }

  private fun hurt(gunfighter: Message, target: Message, restrictionsDurationSec: Long, emoji: String) {
    val senderChat = target.senderChat
    if (senderChat != null) {
      if (senderChat.type != "channel" || !senderChatBans.ban(target.chat.id, senderChat.id, restrictionsDurationSec)) {
        return
      }
    } else {
      val userId = target.from?.id ?: return
      val untilEpochSecond = epochSecond(userId) + restrictionsDurationSec
      val (response, exception) = bot.restrictChatMember(chatId, userId, restrictions, untilEpochSecond)
      if (exception != null) {
        logger.log(WARNING, "Failed to restrict user $userId", exception)
        return
      }
      val body = response?.body()
      if (response?.isSuccessful != true || body?.ok != true || body.result != true) {
        logger.log(WARNING, "Failed to restrict user $userId: HTTP ${response?.code()}, ${body?.errorDescription}")
        return
      }
    }
    val duration = "${restrictionsDurationSec / 60}:${(restrictionsDurationSec % 60).toString().padStart(2, '0')}"
    val text = "$emoji ${mention(gunfighter)} → ${mention(target)} · +$duration"
    bot.sendMessage(
      chatId,
      text,
      parseMode = HTML,
      linkPreviewOptions = LinkPreviewOptions(isDisabled = true),
      disableNotification = true,
      messageThreadId = target.messageThreadId
    ).onError {
      if (it is Unknown) {
        logger.log(WARNING, "Failed to send ban log", it.exception)
      } else {
        logger.log(WARNING, "Failed to send ban log: $it")
      }
    }
  }

  private fun mention(message: Message): String {
    val senderChat = message.senderChat
    if (senderChat != null) {
      val username = senderChat.username?.takeIf(String::isNotBlank)
      if (username != null) {
        return link("https://t.me/$username", "@$username")
      }
      val title = senderChat.title?.takeIf(String::isNotBlank)
      return escapeHtml(title?.let { "$it (${senderChat.id})" } ?: senderChat.id.toString())
    }
    val user = message.from ?: return "?"
    val name = listOfNotNull(user.firstName, user.lastName).filter(String::isNotBlank).joinToString(" ")
    val label = user.username?.takeIf(String::isNotBlank)?.let { "@$it" }
      ?: name.takeIf(String::isNotBlank)
      ?: user.id.toString()
    return link("tg://user?id=${user.id}", label)
  }

  private fun link(url: String, label: String): String {
    return "<a href=\"${escapeHtml(url)}\">${escapeHtml(label)}</a>"
  }

  private fun escapeHtml(text: String): String {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
      .replace('\n', ' ').replace('\r', ' ')
  }

  private fun senderId(message: Message): Long? {
    return message.senderChat?.id ?: message.from?.id
  }

  private fun epochSecond(userId: Long): Long {
    val chatMember = bot.getChatMember(chatId, userId).getOrNull() ?: return now().epochSecond
    if (chatMember.status == "restricted") {
      return chatMember.untilDate?.toLong() ?: now().epochSecond
    }
    return now().epochSecond
  }

  private fun isLifetimeExceeded(epochSecond: Long): Boolean {
    return epochSecond < now().epochSecond
  }

  private sealed interface ReplyLifetime

  private class Temp(val duration: Duration) : ReplyLifetime

  private data object Persistent : ReplyLifetime

  private fun reply(to: Message, emoji: String, lifetime: ReplyLifetime = Temp(ofSeconds(3))): Long? {
    val message =
      try {
        bot.sendMessage(
          chatId, emoji, replyParameters = ReplyParameters(to.messageId), disableNotification = true
        ).get()
      } catch (e: IllegalStateException) {
        return null
      }
    if (lifetime is Temp) {
      markAsTemp(message, lifetime.duration)
    }
    return message.messageId
  }

  private fun markAsTemp(message: Message, lifetime: Duration = ofSeconds(3)) {
    messagesToLifetimes[message.messageId] = now().epochSecond + lifetime.seconds
  }
}
