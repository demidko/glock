package com.github.demidko.glock

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ChatPermissions
import com.github.kotlintelegrambot.entities.Message
import org.apache.commons.collections4.QueueUtils.synchronizedQueue
import org.apache.commons.collections4.queue.CircularFifoQueue
import java.io.Closeable
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
  private val healingTimeZone: ZoneId
) {

  private val messagesToLifetimes = ConcurrentHashMap<Long, Long>()
  private val recentMessages = synchronizedQueue(CircularFifoQueue<Message>(12))
  private val statuettes = ConcurrentLinkedQueue<Long>()

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
    val targetId = target.from?.id ?: return
    val magicCode = extractMagicCode(args) ?: return
    if (!isHealingCode(magicCode)) {
      markAsTemp(healerMessage)
      return
    }
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
      statuettes += statuetteId
    }
    markAsTemp(gunfighterMessage)
  }

  fun tryProcessStatuette(message: Message) {
    val statuette = statuettes.poll()
    if (statuette == null) {
      recentMessages += message
      return
    }
    bot.deleteMessage(chatId, statuette)
    mute(message, restrictionsDuration.seconds, "💥")
  }

  fun buckshot(gunfighterMessage: Message) {
    val gunfighterId = gunfighterMessage.from?.id ?: return
    val targetMessages = recentMessages.filter { it.from?.id != gunfighterId }
    if (targetMessages.isEmpty()) {
      markAsTemp(gunfighterMessage)
      return
    }
    val emoji = setOf("💥", "🗯️", "⚡️")
    if (targetMessages.size == 1) {
      mute(targetMessages.random(), restrictionsDuration.seconds, emoji.random())
      markAsTemp(gunfighterMessage)
      return
    }
    val targetsCount = nextInt(2, targetMessages.size + 1)
    for (t in 1..targetsCount) {
      val target = targetMessages.random()
      val restrictionsDurationSec = nextLong(45, restrictionsDuration.seconds * 2 + 1)
      mute(target, restrictionsDurationSec, emoji.random())
    }
    markAsTemp(gunfighterMessage)
  }

  fun shoot(gunfighterMessage: Message) {
    val target = gunfighterMessage.replyToMessage
    if (target == null) {
      markAsTemp(gunfighterMessage)
      return
    }
    mute(target, restrictionsDuration.seconds, "💥")
    markAsTemp(gunfighterMessage)
  }

  private fun tryRemoveMessage(messageId: Long, epochSecond: Long) {
    if (isLifetimeExceeded(epochSecond)) {
      bot.deleteMessage(chatId, messageId)
      messagesToLifetimes.remove(messageId)
    }
  }

  private fun isTopic(message: Message): Boolean {
    return message.chat.type == "channel"
      || message.authorSignature != null
      || message.forwardSignature != null
  }

  private fun mute(target: Message, restrictionsDurationSec: Long, shootEmoji: String) {
    if (isTopic(target)) {
      return
    }
    val userId = target.from?.id ?: return
    val untilEpochSecond = now().epochSecond + restrictionsDurationSec
    bot.restrictChatMember(chatId, userId, restrictions, untilEpochSecond)
    reply(target, shootEmoji)
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
        bot.sendMessage(chatId, emoji, replyToMessageId = to.messageId, disableNotification = true).get()
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