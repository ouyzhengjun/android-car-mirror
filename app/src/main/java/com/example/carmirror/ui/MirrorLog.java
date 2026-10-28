package com.example.carmirror.ui;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** In-memory trail of the last mirror attempts, shown by 投屏日志. */
public final class MirrorLog {
  public static final int CAPACITY = 40;
  private static final ArrayDeque<Entry> ENTRIES = new ArrayDeque<>();
  private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.US);

  private MirrorLog() {}

  public static final class Entry {
    public final long at;
    public final String text;
    public final boolean failure;

    Entry(long at, String text, boolean failure) {
      this.at = at;
      this.text = text;
      this.failure = failure;
    }

    public String line() {
      return TIME.format(new Date(at)) + "  " + text;
    }
  }

  public static synchronized void failure(String text) {
    add(new Entry(System.currentTimeMillis(), text, true));
  }

  public static synchronized void info(String text) {
    add(new Entry(System.currentTimeMillis(), text, false));
  }

  private static void add(Entry entry) {
    ENTRIES.addFirst(entry);
    while (ENTRIES.size() > CAPACITY) ENTRIES.removeLast();
  }

  public static synchronized List<Entry> entries() {
    return new ArrayList<>(ENTRIES);
  }

  public static synchronized String lastFailure() {
    for (Entry e : ENTRIES) if (e.failure) return e.line();
    return "还没有失败记录";
  }
}
