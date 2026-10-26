package com.github.demidko.glock

import com.github.kotlintelegrambot.entities.Chat
import com.github.kotlintelegrambot.entities.Message
import com.google.gson.Gson
import java.lang.System.Logger.Level.WARNING
import java.lang.System.getLogger
import java.util.Collections.newSetFromMap
import java.util.IdentityHashMap

/** Carries the originating chat action through synchronous API and storage calls on this thread. */
object ActionDiagnostics {
  private val logger = getLogger(ActionDiagnostics::class.java.name)
  private val gson = Gson()
  private val context = ThreadLocal<Map<String, Any?>>()
  private val botUrlToken = Regex("(?i)(/bot)[^/\\s\"<>]+")
  private val botToken = Regex("\\b[0-9]{5,}:[A-Za-z0-9_-]{20,}\\b")

  fun <T> withContext(action: String, initiator: Message?, target: Message?, block: () -> T): T {
    val previous = context.get()
    context.set(linkedMapOf(
      "action" to action,
      "command" to initiator?.text?.takeIf { it.startsWith('/') }?.take(512),
      "trigger_message_id" to initiator?.messageId,
      "trigger_date" to initiator?.date,
      "actor" to sender(initiator),
      "source_chat" to initiator?.chat?.let(::chat),
      "chat" to (target?.chat ?: initiator?.chat)?.let(::chat),
      "message_thread_id" to (target?.messageThreadId ?: initiator?.messageThreadId),
      "target" to sender(target),
      "target_message_id" to target?.messageId
    ))
    return try {
      block()
    } catch (failure: Exception) {
      error(action, "Unhandled exception while processing chat action", failure)
      throw failure
    } finally {
      if (previous == null) context.remove() else context.set(previous)
    }
  }

  fun error(operation: String, details: String, exception: Throwable? = null) {
    val fields = linkedMapOf<String, Any?>(
      "operation" to operation,
      "failure_kind" to if (exception == null) "request_rejected" else "exception",
      "details" to details
    )
    fields.putAll(context.get().orEmpty())
    val failure = exception?.let { sanitized(it, newSetFromMap(IdentityHashMap())) }
      ?: IllegalStateException("Request failed at this call site: ${redact(operation)}")
    logger.log(WARNING, redact(gson.toJson(fields)), failure)
  }

  private fun sender(message: Message?): Map<String, Any?>? {
    message ?: return null
    message.senderChat?.let { return chat(it) }
    val user = message.from ?: return null
    return linkedMapOf(
      "type" to "user", "id" to user.id, "username" to user.username,
      "name" to listOfNotNull(user.firstName, user.lastName).joinToString(" "), "is_bot" to user.isBot
    )
  }

  private fun chat(chat: Chat): Map<String, Any?> = linkedMapOf(
    "type" to chat.type, "id" to chat.id, "title" to chat.title, "username" to chat.username,
    "name" to listOfNotNull(chat.firstName, chat.lastName).joinToString(" ").takeIf { it.isNotEmpty() }
  )

  private fun redact(value: String): String {
    return botToken.replace(botUrlToken.replace(value) { "${it.groupValues[1]}[redacted]" }, "[redacted]")
  }

  private fun sanitized(source: Throwable, seen: MutableSet<Throwable>): Throwable {
    if (!seen.add(source)) return IllegalStateException("Cyclic exception cause")
    val failure = DiagnosticFailure(source.javaClass.name, redact(source.message.orEmpty()))
    failure.stackTrace = source.stackTrace
    source.cause?.let { failure.initCause(sanitized(it, seen)) }
    source.suppressed.forEach { failure.addSuppressed(sanitized(it, seen)) }
    return failure
  }

  private class DiagnosticFailure(private val type: String, message: String) : RuntimeException(message) {
    override fun toString(): String = "$type: $message"
  }
}
