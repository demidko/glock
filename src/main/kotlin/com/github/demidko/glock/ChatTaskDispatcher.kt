package com.github.demidko.glock

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors.newSingleThreadExecutor

/** Processes updates in submission order within a chat, while different chats can run concurrently. */
internal class ChatTaskDispatcher : AutoCloseable {
  private val executors = ConcurrentHashMap<Long, ExecutorService>()

  fun execute(chatId: Long, action: () -> Unit) {
    executors.computeIfAbsent(chatId) {
      newSingleThreadExecutor(Thread.ofVirtual().name("glock-chat-$chatId-", 0).factory())
    }.execute(action)
  }

  /** Call after submissions have stopped; waits for queued actions to finish. */
  override fun close() {
    executors.values.forEach(ExecutorService::close)
  }
}
