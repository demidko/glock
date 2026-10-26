package com.github.demidko.glock

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.LinkPreviewOptions
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.entities.ParseMode.HTML
import com.github.kotlintelegrambot.entities.ReplyParameters
import com.github.kotlintelegrambot.types.TelegramBotResult.Error.Unknown
import org.apache.commons.collections4.QueueUtils.synchronizedQueue
import org.apache.commons.collections4.queue.CircularFifoQueue
import java.time.Duration
import java.time.Duration.ofSeconds
import java.time.Instant.now
import java.time.Instant.ofEpochSecond
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random.Default.nextInt
import kotlin.random.Random.Default.nextLong

// The muzzle of 🔫 points left in every major emoji set, so the muted side always precedes the weapon.
private const val PISTOL = "💥🔫"
private const val STATUETTE = "💥🗿"
private const val BUCKSHOT = "💥💥🔫"

/**
 * Class for sequential, secure, atomic operations in a single chat
 */
class ChatOps(
  private val bot: Bot,
  private val chatId: ChatId,
  private val restrictionsDuration: Duration,
  private val healingConstant: Long,
  private val healingTimeZone: ZoneId,
  private val senderChatBans: SenderChatBans,
  private val userRestrictions: UserRestrictions
) {

  private val messagesToLifetimes = ConcurrentHashMap<Long, Long>()
  private val recentMessages = synchronizedQueue(CircularFifoQueue<Message>(12))
  private val statuettes = ConcurrentLinkedQueue<Statuette>()

  private data class Statuette(val messageId: Long, val gunfighter: Message)

  /** One successful restriction: [remaining] is the whole mute left, [added] is what this hit contributed. */
  private data class Hit(
    val targetId: Long,
    val label: String,
    val remaining: Long,
    val added: Long,
    val extended: Boolean
  )

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

  fun heal(healerMessage: Message, args: List<String>) =
    ActionDiagnostics.withContext("heal", healerMessage, healerMessage.replyToMessage) {
      healTarget(healerMessage, args)
    }

  private fun healTarget(healerMessage: Message, args: List<String>) {
    val target = healerMessage.replyToMessage ?: return
    val targetId = senderId(target) ?: return
    val senderChat = target.senderChat
    if (senderChat != null && senderChat.type != "channel") {
      return
    }
    val magicCode = extractMagicCode(args) ?: return
    if (!isHealingCode(magicCode, healerMessage.date)) {
      markAsTemp(healerMessage)
      return
    }
    if (senderChat != null) {
      if (!senderChatBans.unban(target.chat.id, targetId)) {
        markAsTemp(healerMessage)
        return
      }
    } else {
      if (!userRestrictions.heal(target.chat.id, targetId)) {
        markAsTemp(healerMessage)
        return
      }
    }
    val emoji = setOf("💊", "💉", "🚑")
    reply(target, emoji.random())
    markAsTemp(healerMessage)
  }

  private fun extractMagicCode(args: List<String>): Long? {
    return args.singleOrNull()?.toLongOrNull()
  }

  private fun isHealingCode(code: Long, sentAt: Long): Boolean {
    val time = LocalTime.ofInstant(ofEpochSecond(sentAt), healingTimeZone)
    val hour = time.hour
    val minute = time.minute
    // Congratulations, you've just found the secret healing code!
    // Don't tell anyone about it, please.
    val verification = "${hour}${minute}".toLong() * healingConstant
    return code == verification
  }

  fun statuette(gunfighterMessage: Message) = ActionDiagnostics.withContext("statuette", gunfighterMessage, null) {
    placeStatuette(gunfighterMessage)
  }

  private fun placeStatuette(gunfighterMessage: Message) {
    val statuetteId = reply(gunfighterMessage, "🗿", Persistent)
    if (statuetteId != null) {
      statuettes += Statuette(statuetteId, gunfighterMessage)
    }
    markAsTemp(gunfighterMessage)
  }

  fun tryProcessStatuette(message: Message) {
    val statuette = statuettes.peek()
    if (statuette == null) {
      recentMessages += message
      return
    }
    if (!isStatuetteTarget(message)) {
      return
    }
    val hit = hit(statuette.gunfighter, message, restrictionsDuration.seconds) ?: return
    // A failed restriction must not consume the trap; a failed deletion must not hit twice.
    statuettes.poll()
    ActionDiagnostics.withContext("statuette-hit", statuette.gunfighter, message) {
      bot.deleteMessage(chatId, statuette.messageId).onError {
        ActionDiagnostics.error("deleteMessage", "messageId=${statuette.messageId}; $it", (it as? Unknown)?.exception)
      }
    }
    report(statuette.gunfighter, STATUETTE, listOf(hit), message.messageThreadId)
  }

  private fun isStatuetteTarget(message: Message): Boolean {
    if (message.newChatMembers != null || message.leftChatMember != null || message.newChatTitle != null ||
      message.newChatPhoto != null || message.deleteChatPhoto == true || message.groupChatCreated == true ||
      message.supergroupChatCreated == true || message.channelChatCreated == true ||
      message.migrateToChatId != null || message.migrateFromChatId != null) {
      return false
    }
    return message.senderChat?.type == "channel" || message.senderChat == null && message.from?.isBot == false
  }

  fun buckshot(gunfighterMessage: Message) {
    val gunfighterId = senderId(gunfighterMessage) ?: return
    // The synchronized queue guards single operations only; iterate over a copy taken under its lock.
    val targetMessages = synchronized(recentMessages) { recentMessages.toList() }.filter { senderId(it) != gunfighterId }
    if (targetMessages.isEmpty()) {
      markAsTemp(gunfighterMessage)
      return
    }
    val hits = mutableListOf<Hit>()
    if (targetMessages.size == 1) {
      hit(gunfighterMessage, targetMessages.random(), restrictionsDuration.seconds)?.let(hits::add)
    } else {
      val targetsCount = nextInt(2, targetMessages.size + 1)
      for (t in 1..targetsCount) {
        val target = targetMessages.random()
        val restrictionsDurationSec = nextLong(60, restrictionsDuration.seconds * 2 + 1)
        hit(gunfighterMessage, target, restrictionsDurationSec)?.let(hits::add)
      }
    }
    if (hits.isNotEmpty()) {
      // The volley is one line in the shooter's topic; pellets may have hit messages in other topics.
      report(gunfighterMessage, BUCKSHOT, mergeByTarget(hits), gunfighterMessage.messageThreadId)
    }
    markAsTemp(gunfighterMessage)
  }

  fun shoot(gunfighterMessage: Message) {
    val target = gunfighterMessage.replyToMessage
    if (target != null) {
      hit(gunfighterMessage, target, restrictionsDuration.seconds)?.let { hit ->
        report(gunfighterMessage, PISTOL, listOf(hit), target.messageThreadId)
      }
    }
    markAsTemp(gunfighterMessage)
  }

  private fun tryRemoveMessage(messageId: Long, epochSecond: Long) {
    if (isLifetimeExceeded(epochSecond)) {
      bot.deleteMessage(chatId, messageId).onError {
        ActionDiagnostics.error("deleteMessage", "chat=$chatId messageId=$messageId; $it", (it as? Unknown)?.exception)
      }
      messagesToLifetimes.remove(messageId)
    }
  }

  private fun hit(gunfighter: Message, target: Message, restrictionsDurationSec: Long): Hit? =
    ActionDiagnostics.withContext("mute", gunfighter, target) {
      hitTarget(target, restrictionsDurationSec)
    }

  private fun hitTarget(target: Message, restrictionsDurationSec: Long): Hit? {
    val senderChat = target.senderChat
    val targetId: Long
    val remaining: Long
    if (senderChat != null) {
      if (senderChat.type != "channel" || !senderChatBans.ban(target.chat.id, senderChat.id, restrictionsDurationSec)) {
        return null
      }
      targetId = senderChat.id
      remaining = senderChatBans.remainingSeconds(target.chat.id, senderChat.id)
    } else {
      targetId = target.from?.id ?: return null
      if (!userRestrictions.mute(target.chat.id, targetId, restrictionsDurationSec)) {
        return null
      }
      remaining = userRestrictions.remainingSeconds(target.chat.id, targetId)
    }
    // The registry already counts this hit; anything beyond it was left from earlier hits.
    return Hit(targetId, mention(target), remaining, restrictionsDurationSec, extended = remaining > restrictionsDurationSec)
  }

  private fun mergeByTarget(hits: List<Hit>): List<Hit> {
    val merged = LinkedHashMap<Long, Hit>()
    for (hit in hits) {
      merged.merge(hit.targetId, hit) { first, next ->
        first.copy(remaining = next.remaining, added = first.added + next.added)
      }
    }
    return merged.values.toList()
  }

  private fun report(gunfighter: Message, weapon: String, hits: List<Hit>, threadId: Long?) {
    ActionDiagnostics.withContext("hit-log", gunfighter, null) {
      bot.sendMessage(
        chatId,
        describe(gunfighter, weapon, hits),
        parseMode = HTML,
        linkPreviewOptions = LinkPreviewOptions(isDisabled = true),
        disableNotification = true,
        messageThreadId = threadId
      ).onError {
        ActionDiagnostics.error("sendMessage", "hit log; $it", (it as? Unknown)?.exception)
      }
    }
  }

  private fun describe(gunfighter: Message, weapon: String, hits: List<Hit>): String {
    val victims = hits.joinToString(" · ") { hit ->
      val addition = if (hit.extended) " (+${formatDuration(hit.added)})" else ""
      "🤐${formatDuration(hit.remaining)}$addition ${hit.label}"
    }
    // Shooting your own message leaves nobody at the grip.
    val shooterId = senderId(gunfighter)
    val shooter = if (hits.all { it.targetId == shooterId }) "" else " ${mention(gunfighter)}"
    return "$victims $weapon$shooter"
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

  private fun formatDuration(seconds: Long): String {
    val remaining = seconds.coerceAtLeast(0)
    val days = remaining / 86400
    val hours = remaining % 86400 / 3600
    val minutes = remaining % 3600 / 60
    val rest = remaining % 60
    return listOfNotNull(
      days.takeIf { it > 0 }?.let { "${it}d" },
      hours.takeIf { it > 0 }?.let { "${it}h" },
      minutes.takeIf { it > 0 }?.let { "${it}m" },
      rest.takeIf { it > 0 || remaining == 0L }?.let { "${it}s" }
    ).joinToString(" ")
  }

  private fun isLifetimeExceeded(epochSecond: Long): Boolean {
    return epochSecond < now().epochSecond
  }

  private sealed interface ReplyLifetime

  private class Temp(val duration: Duration) : ReplyLifetime

  private data object Persistent : ReplyLifetime

  private fun reply(to: Message, emoji: String, lifetime: ReplyLifetime = Temp(ofSeconds(3))): Long? {
    val result = bot.sendMessage(
      chatId, emoji, replyParameters = ReplyParameters(to.messageId), disableNotification = true
    )
    result.onError {
      ActionDiagnostics.error("sendMessage", "replyToMessageId=${to.messageId}; $it", (it as? Unknown)?.exception)
    }
    val message = result.getOrNull() ?: return null
    if (lifetime is Temp) {
      markAsTemp(message, lifetime.duration)
    }
    return message.messageId
  }

  private fun markAsTemp(message: Message, lifetime: Duration = ofSeconds(3)) {
    messagesToLifetimes[message.messageId] = now().epochSecond + lifetime.seconds
  }
}
