package com.github.demidko.glock

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Collections.synchronizedList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS

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
}
