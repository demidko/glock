package com.github.demidko.glock

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ChatPermissions
import com.github.kotlintelegrambot.entities.Message
import org.apache.commons.collections4.QueueUtils.synchronizedQueue
import org.apache.commons.collections4.queue.CircularFifoQueue
import java.io.Closeable
import java.time.Duration
import java.time.Duration.between
import java.time.Duration.ofSeconds
import java.time.Instant.now
import java.time.Instant.ofEpochSecond
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors.newSingleThreadExecutor
import kotlin.random.Random.Default.nextInt
import kotlin.random.Random.Default.nextLong

/**
 * Class for sequential, secure, atomic operations in a single chat
 */
class ChatOps(
  private val bot: Bot,
  private val chatId: ChatId,
  private val userToLastActivity: MagicStorage,
  private val restrictions: ChatPermissions,
  private val restrictionsDuration: Duration,
  private val healingConstant: Long,
  private val healingTimeZone: ZoneId
) : Closeable {

  private val restrictionsExecutor = newSingleThreadExecutor()
  private val usersToRestrictions = ConcurrentHashMap<Long, Long>()
  private val messagesToLifetimes = ConcurrentHashMap<Long, Long>()
  private val recentMessages = synchronizedQueue(CircularFifoQueue<Message>(12))
  private val statuettes = ConcurrentLinkedQueue<Long>()

  fun cleanTempMessages() {
    val tempMessagesCount = messagesToLifetimes.mappingCount()
    messagesToLifetimes.forEach(tempMessagesCount, ::tryRemoveMessage)
  }

  fun processRestrictions() {
    restrictionsExecutor.submit {
      val restrictedUsersCount = usersToRestrictions.mappingCount()
      usersToRestrictions.forEach(restrictedUsersCount, ::processRestriction)
    }.get()
  }

  private fun logActivity(m: Message) {
    val userId = m.from?.id ?: return
    userToLastActivity[userId] = now().epochSecond
  }

  fun help(m: Message) {
    if (isRestricted(m)) {
      return
    }
    val dialogLifetime = ofSeconds(20)
    markAsTemp(m, dialogLifetime)
    val rules =
      """
        Logging into the game is done by trying to shoot someone else's message, from this point you can be banned (and you can ban other players).
        If you are not logged in, then you cannot be shot.
      """.trimIndent()
    reply(m, rules, Temp(dialogLifetime))
  }

  fun tryLeaveGame(m: Message) {
    if (isRestricted(m)) {
      return
    }
    val dialogLifetime = ofSeconds(10)
    markAsTemp(m, dialogLifetime)
    if (isIsHePeacefulToday(m)) {
      reply(m, "🕊️")
      return leaveGame(m)
    }
    val notification =
      "Since you have already shot other users, you cannot quit the game until 24 hours have passed 😈"
    reply(m, notification, Temp(dialogLifetime))
  }

  private fun leaveGame(m: Message) {
    val userId = m.from?.id ?: return
    userToLastActivity.remove(userId)
  }

  private fun isIsHePeacefulToday(message: Message): Boolean {
    val userId = message.from?.id ?: return true
    val lastActivity = userToLastActivity.get<Long>(userId) ?: return true
    val passedHours = between(ofEpochSecond(lastActivity), now()).toHours()
    return passedHours >= 24
  }

  fun filterMessage(message: Message) {
    if (isRestricted(message)) {
      val messageId = message.messageId
      bot.deleteMessage(chatId, messageId)
      return
    }
    recentMessages += message
  }

  fun heal(healerMessage: Message, args: List<String>) {
    if (isRestricted(healerMessage)) {
      return
    }
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
    restrictionsExecutor.execute {
      usersToRestrictions.remove(targetId)
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
    if (isRestricted(gunfighterMessage)) {
      return
    }
    logActivity(gunfighterMessage)
    val statuetteId = reply(gunfighterMessage, "🗿", Persistent)
    if (statuetteId != null) {
      statuettes += statuetteId
    }
    markAsTemp(gunfighterMessage)
  }

  fun tryProcessStatuette(message: Message) {
    if (isRestricted(message)) {
      return
    }
    val statuette = statuettes.poll() ?: return
    bot.deleteMessage(chatId, statuette)
    mute(message, restrictionsDuration.seconds, "💥")
  }

  fun buckshot(gunfighterMessage: Message) {
    if (isRestricted(gunfighterMessage)) {
      return
    }
    logActivity(gunfighterMessage)
    if (recentMessages.isEmpty()) {
      markAsTemp(gunfighterMessage)
      return
    }
    val emoji = setOf("💥", "🗯️", "⚡️")
    if (recentMessages.size == 1) {
      mute(recentMessages.random(), restrictionsDuration.seconds, emoji.random())
      markAsTemp(gunfighterMessage)
      return
    }
    val targetsCount = nextInt(2, recentMessages.size + 1)
    for (t in 1..targetsCount) {
      val target = recentMessages.random()
      val restrictionsDurationSec = nextLong(45, restrictionsDuration.seconds * 2 + 1)
      mute(target, restrictionsDurationSec, emoji.random())
    }
    markAsTemp(gunfighterMessage)
  }

  fun shoot(gunfighterMessage: Message) {
    if (isRestricted(gunfighterMessage)) {
      return
    }
    logActivity(gunfighterMessage)
    val target = gunfighterMessage.replyToMessage
    if (target == null) {
      mute(gunfighterMessage, restrictionsDuration.seconds, "💥")
    } else {
      mute(target, restrictionsDuration.seconds, "💥")
    }
    markAsTemp(gunfighterMessage)
  }

  private fun processRestriction(userId: Long, epochSecond: Long) {
    if (isLifetimeExceeded(epochSecond)) {
      usersToRestrictions.remove(userId)
    }
  }

  private fun tryRemoveMessage(messageId: Long, epochSecond: Long) {
    if (isLifetimeExceeded(epochSecond)) {
      bot.deleteMessage(chatId, messageId)
      messagesToLifetimes.remove(messageId)
    }
  }

  private fun isRestricted(message: Message): Boolean {
    val userId = message.from?.id ?: return false
    val epochSecond = usersToRestrictions[userId]
    return epochSecond != null && !isLifetimeExceeded(epochSecond)
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
    if (!isLegalTarget(target)) {
      reply(target, "💨")
      return
    }
    val userId = target.from?.id ?: return
    val untilEpochSecond = now().epochSecond + restrictionsDurationSec
    bot.restrictChatMember(chatId, userId, restrictions, untilEpochSecond)
    restrictionsExecutor.execute {
      usersToRestrictions.compute(userId) { _, previousRestriction ->
        when (previousRestriction == null || untilEpochSecond > previousRestriction) {
          true -> untilEpochSecond
          else -> previousRestriction
        }
      }
    }
    reply(target, shootEmoji)
  }

  private fun isLegalTarget(m: Message): Boolean {
    val userId = m.from?.id ?: return false
    return userToLastActivity.keys.contains(userId)
  }

  private fun isLifetimeExceeded(epochSecond: Long): Boolean {
    return epochSecond < now().epochSecond
  }

  private sealed interface ReplyLifetime

  private class Temp(val duration: Duration) : ReplyLifetime

  private object Persistent : ReplyLifetime

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

  override fun close() {
    restrictionsExecutor.close()
  }
}