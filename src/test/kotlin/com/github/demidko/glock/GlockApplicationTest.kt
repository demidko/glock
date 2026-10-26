package com.github.demidko.glock

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration.ofMillis
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger

class GlockApplicationTest {
  @Test
  fun `one cleanup exception does not stop future retries`() {
    val attempts = AtomicInteger()
    val retried = CountDownLatch(1)
    val worker = startLoopWithFixedRate(ofMillis(1)) {
      if (attempts.incrementAndGet() == 1) error("Temporary cleanup failure")
      retried.countDown()
    }
    try {
      assertThat(retried.await(5, SECONDS)).isTrue()
      assertThat(attempts.get()).isAtLeast(2)
    } finally {
      worker.interrupt()
      worker.join(5000)
    }
    assertThat(worker.isAlive).isFalse()
  }

  @Test
  fun `interrupting cleanup still stops its worker`() {
    val entered = CountDownLatch(1)
    val attempts = AtomicInteger()
    val worker = startLoopWithFixedRate(ofMillis(1)) {
      attempts.incrementAndGet()
      entered.countDown()
      Thread.sleep(60_000)
    }
    try {
      assertThat(entered.await(5, SECONDS)).isTrue()
    } finally {
      worker.interrupt()
      worker.join(5000)
    }
    assertThat(worker.isAlive).isFalse()
    assertThat(attempts.get()).isEqualTo(1)
  }
}
