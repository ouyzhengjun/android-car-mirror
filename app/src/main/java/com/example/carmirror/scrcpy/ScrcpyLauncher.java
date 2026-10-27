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

  // Some head units mount /data/local/tmp without write access for the shell user,
  // so the server jar falls back to shared storage where adbd can still write.
  private static final String[] REMOTE_DIRS = {"/data/local/tmp", "/sdcard"};

  private final ServerSource source;
  private final long size, timeoutMs;
  private final MirrorOptions options;
  private Run active;

  public ScrcpyLauncher(Context context) throws IOException {
    this(context, MirrorSettings.defaultForDisplay(0, 0).resolve(0, 0));
  }

  public ScrcpyLauncher(Context context, MirrorOptions options) throws IOException {
    this(() -> context.getAssets().open("scrcpy-server-v4.0"), assetSize(context), 15000, options);
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
    this(source, size, timeoutMs, MirrorSettings.defaultForDisplay(0, 0).resolve(0, 0));
  }

  public ScrcpyLauncher(ServerSource source, long size, long timeoutMs, MirrorOptions options) {
    if (size <= 0 || timeoutMs <= 0) throw new IllegalArgumentException();
    this.source = Objects.requireNonNull(source);
    this.options = Objects.requireNonNull(options);
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
      Throwable cause = e.getCause();
      String detail = cause == null ? null : cause.getMessage();
      throw new IOException(
          detail == null || detail.trim().isEmpty()
              ? "scrcpy startup failed"
              : "scrcpy startup failed: " + detail,
          cause);
    } finally {
      if (!ready) stopRun(run);
    }
  }

  private Streams launch(AdbGateway adb, Run run) throws Exception {
    String scid = String.format(Locale.ROOT, "%08x", new SecureRandom().nextInt() & 0x7fffffff);
    String remote = null;
    IOException uploadFailure = null;
    for (String dir : REMOTE_DIRS) {
      String candidate = dir + "/scrcpy-server-" + scid + ".jar";
      try (InputStream in = run.own(source.open())) {
        adb.push(in, size, candidate);
        remote = candidate;
        break;
      } catch (IOException e) {
        uploadFailure = e;
      }
    }
    if (remote == null)
      throw new IOException(
          "server upload failed: "
              + (uploadFailure.getMessage() == null ? "unknown" : uploadFailure.getMessage()),
          uploadFailure);
    AdbChannel process = run.own(adb.openService("shell:"));
    String command =
        "stty -echo 2>/dev/null; CLASSPATH="
            + remote
            + " exec app_process / com.genymobile.scrcpy.Server 4.0 scid="
            + scid
            + " tunnel_forward=true audio=false video=true control=true video_codec=h264"
            + " display_id=" + options.displayId
            + " max_size=" + options.maxSize
            + " video_bit_rate=" + options.bitRate
            + " max_fps=" + options.maxFps
            + (options.crop == null ? "" : " crop=" + options.crop)
            + " cleanup=true\n";
    process.output().write(command.getBytes(StandardCharsets.UTF_8));
    process.output().flush();
    // Drain server logs so ADB flow control cannot stall the server. Never retain unbounded logs.
    run.workers.submit(
        () -> {
          try {
            BufferedReader reader =
                new BufferedReader(
                    new InputStreamReader(process.input(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) logServer(line);
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

  private static void logServer(String line) {
    if (android.os.Build.VERSION.SDK_INT == 0) return;
    try {
      android.util.Log.i("ScrcpyServer", line.length() > 300 ? line.substring(0, 300) : line);
    } catch (Throwable ignored) {
    }
  }

  private static void close(Closeable c) {
    try {
      c.close();
    } catch (IOException ignored) {
    }
  }
}
