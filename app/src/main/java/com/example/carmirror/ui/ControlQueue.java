package com.example.carmirror.ui;

import java.util.Iterator;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded, deadline-aware queue for mirror control writes.
 *
 * <p>The control channel is stop-and-wait: each write has to be acknowledged before the next one
 * goes out, so one slow write holds up everything behind it. A touch that waited out that stall is
 * no longer what the user is doing; replaying it seconds later moves the pointer somewhere the
 * user never asked for. Entries therefore carry a deadline and are dropped when they reach the
 * head too late, letting the queue catch up to the newest input instead of replaying history.
 *
 * <p>Entries marked {@code keepAlive} are always delivered: a pointer release that gets dropped
 * would leave the device's finger pressed down forever.
 */
public final class ControlQueue {
  /** Wall clock in milliseconds; injectable so ordering rules stay testable. */
  public interface Clock {
    long now();
  }

  public interface Action {
    void run() throws Exception;
  }

  public interface Failure {
    void onFailure(Exception error);
  }

  private static final class Entry {
    final Action action;
    final long deadline;
    final boolean keepAlive;

    Entry(Action action, long deadline, boolean keepAlive) {
      this.action = action;
      this.deadline = deadline;
      this.keepAlive = keepAlive;
    }
  }

  private final LinkedBlockingDeque<Entry> queue;
  private final Thread worker;
  private final Clock clock;
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong skipped = new AtomicLong();
  private final AtomicLong completed = new AtomicLong();
  private volatile Failure failure;
  private volatile boolean stopped;

  public ControlQueue(int capacity) {
    this(capacity, System::currentTimeMillis);
  }

  public ControlQueue(int capacity, Clock clock) {
    if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
    this.clock = clock;
    this.queue = new LinkedBlockingDeque<>(capacity);
    worker = new Thread(this::loop, "mirror-control");
    worker.setDaemon(true);
    worker.start();
  }

  public void failureHandler(Failure handler) {
    failure = handler;
  }

  /**
   * Enqueues one control write. When the queue is full the oldest droppable entry is evicted;
   * false means every entry in the queue is keepAlive and this one had to be refused.
   */
  public boolean submit(Action action, boolean keepAlive, long budgetMs) {
    if (stopped || action == null) return false;
    Entry entry = new Entry(action, clock.now() + Math.max(0, budgetMs), keepAlive);
    while (!queue.offerLast(entry)) {
      if (!evictDroppable()) return false;
    }
    return true;
  }

  private boolean evictDroppable() {
    for (Iterator<Entry> entries = queue.iterator(); entries.hasNext(); ) {
      if (!entries.next().keepAlive) {
        entries.remove();
        dropped.incrementAndGet();
        return true;
      }
    }
    return false;
  }

  private void loop() {
    while (!stopped) {
      Entry entry;
      try {
        entry = queue.takeFirst();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      }
      if (stopped) return;
      if (!entry.keepAlive && clock.now() > entry.deadline) {
        skipped.incrementAndGet();
        continue;
      }
      try {
        entry.action.run();
        completed.incrementAndGet();
      } catch (Exception error) {
        Failure handler = failure;
        if (handler != null) handler.onFailure(error);
      }
    }
  }

  public void stop() {
    stopped = true;
    queue.clear();
    worker.interrupt();
  }

  /** Entries evicted to make room. */
  public long dropped() {
    return dropped.get();
  }

  /** Stale entries abandoned at the head instead of being written. */
  public long skipped() {
    return skipped.get();
  }

  public long completed() {
    return completed.get();
  }

  public int size() {
    return queue.size();
  }
}
