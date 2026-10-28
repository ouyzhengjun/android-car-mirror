package com.example.carmirror.ui;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class ControlQueueTest {

  /** Lets a test move time without sleeping. */
  static class ManualClock implements ControlQueue.Clock {
    volatile long now = 1000;

    @Override
    public long now() {
      return now;
    }
  }

  static void awaitCount(ControlQueue queue, long completed, long timeoutMs) throws Exception {
    long end = System.currentTimeMillis() + timeoutMs;
    while (queue.completed() < completed && System.currentTimeMillis() < end) Thread.sleep(2);
  }

  @Test
  public void runsInOrderWhenFresh() throws Exception {
    ControlQueue queue = new ControlQueue(8, new ManualClock());
    List<Integer> ran = new ArrayList<>();
    CountDownLatch done = new CountDownLatch(3);
    try {
      for (int i = 0; i < 3; i++) {
        final int value = i;
        assertTrue(
            queue.submit(
                () -> {
                  synchronized (ran) {
                    ran.add(value);
                  }
                  done.countDown();
                },
                false,
                100));
      }
      assertTrue(done.await(2, TimeUnit.SECONDS));
      synchronized (ran) {
        assertEquals(List.of(0, 1, 2), ran);
      }
      assertEquals(0, queue.skipped());
    } finally {
      queue.stop();
    }
  }

  @Test
  public void dropsStaleEntriesAndKeepsProcessing() throws Exception {
    ManualClock clock = new ManualClock();
    ControlQueue queue = new ControlQueue(8, clock);
    List<String> ran = new ArrayList<>();
    CountDownLatch first = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try {
      // Occupy the worker so the next two entries age past their deadline.
      queue.submit(
          () -> {
            first.countDown();
            release.await(2, TimeUnit.SECONDS);
          },
          true,
          100);
      assertTrue(first.await(2, TimeUnit.SECONDS));
      queue.submit(() -> add(ran, "stale"), false, 100);
      queue.submit(() -> add(ran, "latest"), false, 100);
      clock.now += 500; // both are now older than their 100 ms budget
      release.countDown();
      awaitCount(queue, 1, 2000);
      Thread.sleep(100);
      assertEquals(List.of(), ran);
      assertEquals(2, queue.skipped());
    } finally {
      queue.stop();
    }
  }

  @Test
  public void keepAliveEntriesAreNeverSkipped() throws Exception {
    ManualClock clock = new ManualClock();
    ControlQueue queue = new ControlQueue(8, clock);
    List<String> ran = new ArrayList<>();
    CountDownLatch first = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try {
      queue.submit(
          () -> {
            first.countDown();
            release.await(2, TimeUnit.SECONDS);
          },
          true,
          100);
      assertTrue(first.await(2, TimeUnit.SECONDS));
      queue.submit(() -> add(ran, "release"), true, 100);
      clock.now += 5000; // long past every deadline
      release.countDown();
      awaitCount(queue, 1, 2000);
      Thread.sleep(100);
      assertEquals(List.of("release"), ran);
    } finally {
      queue.stop();
    }
  }

  @Test
  public void fullQueueEvictsTheOldestDroppableEntry() throws Exception {
    ManualClock clock = new ManualClock();
    ControlQueue queue = new ControlQueue(2, clock);
    CountDownLatch hold = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try {
      queue.submit(
          () -> {
            hold.countDown();
            release.await(2, TimeUnit.SECONDS);
          },
          true,
          100);
      assertTrue(hold.await(2, TimeUnit.SECONDS));
      assertTrue(queue.submit(() -> {}, false, 100));
      assertTrue(queue.submit(() -> {}, false, 100));
      // Full: the oldest droppable entry makes room for the newest input.
      assertTrue(queue.submit(() -> {}, false, 100));
      assertEquals(1, queue.dropped());
      release.countDown();
    } finally {
      queue.stop();
    }
  }

  @Test
  public void refusesWhenEveryEntryIsKeepAlive() throws Exception {
    ControlQueue queue = new ControlQueue(1, new ManualClock());
    CountDownLatch hold = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try {
      queue.submit(
          () -> {
            hold.countDown();
            release.await(2, TimeUnit.SECONDS);
          },
          true,
          100);
      assertTrue(hold.await(2, TimeUnit.SECONDS));
      assertTrue(queue.submit(() -> {}, true, 100));
      assertFalse(queue.submit(() -> {}, true, 100));
      release.countDown();
    } finally {
      queue.stop();
    }
  }

  @Test
  public void failureIsReportedAndTheQueueKeepsDraining() throws Exception {
    ControlQueue queue = new ControlQueue(8, new ManualClock());
    List<Exception> failures = new ArrayList<>();
    CountDownLatch reported = new CountDownLatch(1);
    queue.failureHandler(
        error -> {
          synchronized (failures) {
            failures.add(error);
          }
          reported.countDown();
        });
    CountDownLatch after = new CountDownLatch(1);
    try {
      queue.submit(
          () -> {
            throw new java.io.IOException("stream closed");
          },
          true,
          100);
      assertTrue(reported.await(2, TimeUnit.SECONDS));
      queue.submit(after::countDown, false, 1000);
      assertTrue(after.await(2, TimeUnit.SECONDS));
      synchronized (failures) {
        assertEquals(1, failures.size());
        assertEquals("stream closed", failures.get(0).getMessage());
      }
    } finally {
      queue.stop();
    }
  }

  @Test
  public void stopDropsPendingWork() throws Exception {
    ControlQueue queue = new ControlQueue(8, new ManualClock());
    CountDownLatch submitted = new CountDownLatch(1);
    CountDownLatch ran = new CountDownLatch(1);
    queue.submit(
        () -> {
          submitted.countDown();
          Thread.sleep(300);
        },
        true,
        100);
    assertTrue(submitted.await(2, TimeUnit.SECONDS));
    queue.submit(ran::countDown, false, 5000);
    queue.stop();
    assertFalse(ran.await(300, TimeUnit.MILLISECONDS));
    assertFalse(queue.submit(() -> {}, false, 100));
  }

  private static void add(List<String> list, String value) {
    synchronized (list) {
      list.add(value);
    }
  }
}
