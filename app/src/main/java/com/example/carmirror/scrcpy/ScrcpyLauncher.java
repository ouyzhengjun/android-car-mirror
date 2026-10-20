package com.example.carmirror.scrcpy;

import android.content.Context;
import com.example.carmirror.adb.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** Owns the server shell and its two sockets; start must run off the UI thread. */
public final class ScrcpyLauncher implements ScrcpySession {
  public interface ServerSource {
    InputStream open() throws IOException;
  }

  private final ServerSource source;
  private final long size, timeoutMs;
  private Run active;

  public ScrcpyLauncher(Context context) throws IOException {
    this(() -> context.getAssets().open("scrcpy-server-v4.0"), assetSize(context), 15000);
  }

  private static long assetSize(Context context) throws IOException {
    try (InputStream in = context.getAssets().open("scrcpy-server-v4.0")) {
      long n = 0;
      byte[] b = new byte[8192];
      int count;
      while ((count = in.read(b)) != -1) n += count;
      return n;
    }
  }

  public ScrcpyLauncher(ServerSource source, long size, long timeoutMs) {
    if (size <= 0 || timeoutMs <= 0) throw new IllegalArgumentException();
    this.source = Objects.requireNonNull(source);
    this.size = size;
    this.timeoutMs = timeoutMs;
  }

  private static final class Run {
    final List<Closeable> resources = new ArrayList<>();
    final ExecutorService workers =
        Executors.newCachedThreadPool(
            r -> {
              Thread t = new Thread(r, "scrcpy-session");
              t.setDaemon(true);
              return t;
            });
    Future<Streams> job;
    boolean stopped;

    synchronized <T extends Closeable> T own(T resource) throws IOException {
      if (stopped) {
        close(resource);
        throw new IOException("scrcpy startup canceled");
      }
      resources.add(resource);
      return resource;
    }

    synchronized void stop() {
      if (stopped) return;
      stopped = true;
      if (job != null) job.cancel(true);
      for (int i = resources.size() - 1; i >= 0; i--) close(resources.get(i));
      resources.clear();
      workers.shutdownNow();
    }
  }

  @Override
  public Streams start(AdbGateway adb) throws IOException {
    final Run run = new Run();
    synchronized (this) {
      if (active != null) throw new IOException("scrcpy session already started");
      active = run;
      run.job = run.workers.submit(() -> launch(adb, run));
    }
    boolean ready = false;
    try {
      Streams streams = run.job.get(timeoutMs, TimeUnit.MILLISECONDS);
      ready = true;
      return streams;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("scrcpy startup interrupted", e);
    } catch (TimeoutException e) {
      throw new IOException("scrcpy startup timed out", e);
    } catch (CancellationException e) {
      throw new IOException("scrcpy startup canceled", e);
    } catch (ExecutionException e) {
      throw new IOException("scrcpy startup failed", e.getCause());
    } finally {
      if (!ready) stopRun(run);
    }
  }

  private Streams launch(AdbGateway adb, Run run) throws Exception {
    String scid = String.format(Locale.ROOT, "%08x", new SecureRandom().nextInt() & 0x7fffffff);
    String remote = "/data/local/tmp/scrcpy-server-" + scid + ".jar";
    try (InputStream in = run.own(source.open())) {
      adb.push(in, size, remote);
    }
    AdbChannel process = run.own(adb.openService("shell:"));
    String command =
        "stty -echo 2>/dev/null; CLASSPATH="
            + remote
            + " exec app_process / com.genymobile.scrcpy.Server 4.0 scid="
            + scid
            + " tunnel_forward=true audio=false video=true control=true video_codec=h264"
            + " cleanup=true\n";
    process.output().write(command.getBytes(StandardCharsets.UTF_8));
    process.output().flush();
    // Drain server logs so ADB flow control cannot stall the server. Never retain unbounded logs.
    run.workers.submit(
        () -> {
          try {
            byte[] b = new byte[4096];
            while (process.input().read(b) != -1) {}
          } catch (IOException ignored) {
          }
        });
    String socket = "localabstract:scrcpy_" + scid;
    AdbChannel video;
    while (true) {
      if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
      try {
        video = run.own(adb.openService(socket));
        break;
      } catch (IOException e) {
        synchronized (run) {
          if (run.stopped) throw e;
        }
        Thread.sleep(50);
      }
    }
    // The forward-mode dummy byte validates readiness; metadata starts immediately after it.
    if (video.input().read() != 0) throw new IOException("Invalid scrcpy readiness byte");
    AdbChannel control = run.own(adb.openService(socket));
    return new Streams(video, control);
  }

  @Override
  public void stop() {
    Run run;
    synchronized (this) {
      run = active;
      active = null;
    }
    if (run != null) run.stop();
  }

  private void stopRun(Run run) {
    synchronized (this) {
      if (active == run) active = null;
    }
    run.stop();
  }

  private static void close(Closeable c) {
    try {
      c.close();
    } catch (IOException ignored) {
    }
  }
}
