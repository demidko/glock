package com.github.demidko.glock

import java.lang.System.Logger.Level.ERROR
import java.lang.System.getLogger
import java.util.ArrayDeque
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Processes updates in submission order within a chat, while different chats can run concurrently. */
internal class ChatTaskDispatcher : AutoCloseable {
  private val lock = ReentrantLock()
  private val drained = lock.newCondition()
  private val queues = mutableMapOf<Long, ArrayDeque<() -> Unit>>()
  private var closed = false

  fun execute(chatId: Long, action: () -> Unit) {
    lock.withLock {
      if (closed) throw RejectedExecutionException("Chat dispatcher is closed")
      val queue = queues[chatId]
      if (queue != null) {
        queue.addLast(action)
      } else {
        queues[chatId] = ArrayDeque<() -> Unit>().apply { addLast(action) }
        try {
          Thread.ofVirtual().name("glock-chat-$chatId").start { drain(chatId) }
        } catch (failure: Throwable) {
          queues.remove(chatId)
          throw failure
        }
      }
    }
  }

  private fun drain(chatId: Long) {
    while (true) {
      val action = lock.withLock {
        val next = queues.getValue(chatId).pollFirst()
        if (next == null) {
          // Retirement and submission share the lock: a replacement worker can only start
          // after this worker has finished all chat actions and removed its empty queue.
          queues.remove(chatId)
          if (queues.isEmpty()) drained.signalAll()
          return
        }
        next
      }
      // Like an executor worker, start each action without a previous action's interrupt flag.
      Thread.interrupted()
      try {
        action()
      } catch (failure: Throwable) {
        getLogger(ChatTaskDispatcher::class.java.name).log(ERROR, "Chat $chatId action failed", failure)
      }
    }
  }

  /** Stops accepting work and waits for accepted actions to finish, preserving interruption status. */
  override fun close() {
    lock.withLock {
      closed = true
      while (queues.isNotEmpty()) drained.awaitUninterruptibly()
    }
  }
}
