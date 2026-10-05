package com.github.demidko.glock

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Collections.synchronizedList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

class ChatTaskDispatcherTest {
  @Test
  fun `updates in one chat run sequentially in submission order`() {
    ChatTaskDispatcher().use { dispatcher ->
      val started = CountDownLatch(1)
      val release = CountDownLatch(1)
      val finished = CountDownLatch(3)
      val order = synchronizedList(mutableListOf<Int>())
      try {
        dispatcher.execute(1) {
          started.countDown()
          check(release.await(5, SECONDS))
          order += 1
          finished.countDown()
        }
        assertThat(started.await(5, SECONDS)).isTrue()
        dispatcher.execute(1) { order += 2; finished.countDown() }
        dispatcher.execute(1) { order += 3; finished.countDown() }
        assertThat(order).isEmpty()
      } finally {
        release.countDown()
      }
      assertThat(finished.await(5, SECONDS)).isTrue()
      assertThat(order).containsExactly(1, 2, 3).inOrder()
    }
  }

  @Test
  fun `a blocked chat does not block a different chat`() {
    ChatTaskDispatcher().use { dispatcher ->
      val started = CountDownLatch(1)
      val release = CountDownLatch(1)
      val otherFinished = CountDownLatch(1)
      try {
        dispatcher.execute(1) {
          started.countDown()
          check(release.await(5, SECONDS))
        }
        assertThat(started.await(5, SECONDS)).isTrue()
        dispatcher.execute(2) { otherFinished.countDown() }
        assertThat(otherFinished.await(5, SECONDS)).isTrue()
      } finally {
        release.countDown()
      }
    }
  }

  @Test
  fun `a failed action does not prevent the next update from running`() {
    ChatTaskDispatcher().use { dispatcher ->
      val finished = CountDownLatch(1)
      dispatcher.execute(1) { throw IllegalArgumentException("Invalid command") }
      dispatcher.execute(1) { finished.countDown() }
      assertThat(finished.await(5, SECONDS)).isTrue()
    }
  }

  @Test
  fun `completed one-off chats release workers and queues and can be used again`() {
    ChatTaskDispatcher().use { dispatcher ->
      repeat(2) {
        val workers = synchronizedList(mutableListOf<Thread>())
        val finished = CountDownLatch(500)
        repeat(500) { chatId ->
          dispatcher.execute(chatId.toLong()) {
            workers += Thread.currentThread()
            finished.countDown()
          }
        }
        assertThat(finished.await(5, SECONDS)).isTrue()
        for (worker in workers) {
          worker.join(5000)
          assertThat(worker.isAlive).isFalse()
        }
        // All workers have exited, so there are no concurrent accesses to the map.
        val queues = ChatTaskDispatcher::class.java.getDeclaredField("queues").apply { isAccessible = true }
        assertThat(queues.get(dispatcher) as Map<*, *>).isEmpty()
      }
    }
  }

  @Test
  fun `submissions racing retirement never overlap or reorder chat actions`() {
    ChatTaskDispatcher().use { dispatcher ->
      val active = AtomicInteger()
      val overlaps = AtomicInteger()
      val order = synchronizedList(mutableListOf<Int>())
      repeat(2000) { index ->
        val running = CountDownLatch(1)
        dispatcher.execute(1) {
          if (active.incrementAndGet() != 1) overlaps.incrementAndGet()
          try {
            order += index
            running.countDown()
            Thread.yield()
          } finally {
            active.decrementAndGet()
          }
        }
        // Submit the next action as the previous action finishes and its queue may retire.
        assertThat(running.await(5, SECONDS)).isTrue()
      }
      dispatcher.close()
      assertThat(overlaps.get()).isEqualTo(0)
      assertThat(order).containsExactlyElementsIn(0 until 2000).inOrder()
    }
  }

  @Test
  fun `close drains accepted actions rejects every chat and preserves interruption`() {
    val dispatcher = ChatTaskDispatcher()
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val closing = CountDownLatch(1)
    val closed = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val interruptionPreserved = java.util.concurrent.atomic.AtomicBoolean()
    dispatcher.execute(1) { started.countDown(); release.await() }
    assertThat(started.await(5, SECONDS)).isTrue()
    dispatcher.execute(1) { finished.countDown() }
    val closer = Thread.startVirtualThread {
      Thread.currentThread().interrupt()
      closing.countDown()
      dispatcher.close()
      interruptionPreserved.set(Thread.currentThread().isInterrupted)
      closed.countDown()
    }
    try {
      assertThat(closing.await(5, SECONDS)).isTrue()
      assertThat(closed.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)).isFalse()
    } finally {
      release.countDown()
    }
    assertThat(closed.await(5, SECONDS)).isTrue()
    closer.join(5000)
    assertThat(finished.count).isEqualTo(0)
    assertThat(interruptionPreserved.get()).isTrue()
    assertThrows<RejectedExecutionException> { dispatcher.execute(1) {} }
    assertThrows<RejectedExecutionException> { dispatcher.execute(2) {} }
    dispatcher.close()
  }


  @Test
  fun `an interrupted action does not poison the next action`() {
    ChatTaskDispatcher().use { dispatcher ->
      val started = CountDownLatch(1)
      val release = CountDownLatch(1)
      val inheritedInterrupt = java.util.concurrent.atomic.AtomicBoolean(true)
      dispatcher.execute(1) {
        started.countDown()
        release.await()
        Thread.currentThread().interrupt()
      }
      try {
        assertThat(started.await(5, SECONDS)).isTrue()
        dispatcher.execute(1) { inheritedInterrupt.set(Thread.currentThread().isInterrupted) }
      } finally {
        release.countDown()
      }
      dispatcher.close()
      assertThat(inheritedInterrupt.get()).isFalse()
    }
  }

}
