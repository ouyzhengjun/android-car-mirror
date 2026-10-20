package com.example.carmirror.adb;

import com.example.carmirror.Endpoint;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Blocking gateway methods must be invoked on a worker; connect/disconnect schedule their own work.
 */
public final class AdbSession implements AdbGateway {
  private static final long DEFAULT_CONNECT_TIMEOUT_MS = 30000;
  private static final int MAX_COMMAND_OUTPUT_BYTES = 4 * 1024 * 1024;
  private static final ScheduledExecutorService CONNECT_TIMER =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "adb-connect-timer");
            t.setDaemon(true);
            return t;
          });

  private static final class CommandUnavailableException extends IOException {
    CommandUnavailableException(String message) {
      super(message);
    }
  }

  private static final class CommandOutputLimitException extends IOException {
    CommandOutputLimitException() {
      super("命令输出超过 4 MiB");
    }
  }

  public interface Transport extends Closeable {
    boolean connect(long timeoutMs) throws Exception;

    AdbChannel open(String destination) throws Exception;
  }

  public interface Factory {
    Transport create(Endpoint endpoint) throws Exception;
  }

  private final Factory factory;
  private final Executor callbacks;
  private final long connectTimeoutMs;
  private final ExecutorService workers =
      Executors.newCachedThreadPool(
          r -> {
            Thread t = new Thread(r, "adb-worker");
            t.setDaemon(true);
            return t;
          });
  private final Set<AdbChannel> channels = new HashSet<>();
  private Transport transport;
  private boolean ready;
  private boolean connecting;
  private Future<?> connectTask;
  private ScheduledFuture<?> connectWatchdog;
  private long generation;
  private Listener listener;
  private Endpoint endpoint;

  public AdbSession(Factory factory, Executor callbacks) {
    this(factory, callbacks, DEFAULT_CONNECT_TIMEOUT_MS);
  }

  AdbSession(Factory factory, Executor callbacks, long connectTimeoutMs) {
    if (connectTimeoutMs <= 0) throw new IllegalArgumentException("connect timeout must be positive");
    this.factory = factory;
    this.callbacks = callbacks;
    this.connectTimeoutMs = connectTimeoutMs;
  }

  private void state(long token, ConnectionState state, String message) {
    final Listener target;
    synchronized (this) {
      if (token != generation) return;
      target = listener;
    }
    if (target != null)
      callbacks.execute(
          () -> {
            synchronized (this) {
              if (token != generation) return;
            }
            target.onState(state, message);
          });
  }

  public void connect(Endpoint endpoint, Listener listener) {
    final long token;
    final Transport old;
    final List<AdbChannel> stale;
    final Future<?> previousTask;
    final ScheduledFuture<?> previousWatchdog;
    synchronized (this) {
      token = ++generation;
      this.listener = listener;
      this.endpoint = endpoint;
      old = transport;
      transport = null;
      ready = false;
      connecting = true;
      previousTask = connectTask;
      previousWatchdog = connectWatchdog;
      connectTask = null;
      connectWatchdog = null;
      stale = new ArrayList<>(channels);
      channels.clear();
    }
    if (previousTask != null) previousTask.cancel(true);
    if (previousWatchdog != null) previousWatchdog.cancel(false);
    workers.execute(
        () -> {
          for (AdbChannel c : stale) close(c);
          close(old);
        });
    state(token, ConnectionState.CONNECTING, "正在连接 " + endpoint.serial());
    FutureTask<Void> task = new FutureTask<>(
        () -> {
          try {
            Transport next = factory.create(endpoint);
            boolean obsolete;
            synchronized (this) {
              obsolete = token != generation || !connecting;
              if (!obsolete) transport = next;
            }
            if (obsolete) {
              close(next);
              return null;
            }
            state(token, ConnectionState.AUTHORIZING, "请在车机上允许此设备的 USB 调试授权");
            if (!next.connect(connectTimeoutMs)) throw new IOException("ADB 授权或连接超时");
            synchronized (this) {
              obsolete = token != generation || !connecting;
              if (!obsolete) {
                ready = true;
                connecting = false;
                if (connectWatchdog != null) connectWatchdog.cancel(false);
                connectWatchdog = null;
                connectTask = null;
              }
            }
            if (obsolete) {
              close(next);
              return null;
            }
            state(token, ConnectionState.CONNECTED, "已连接：" + endpoint.serial());
          } catch (Exception e) {
            fail(token, e);
          }
          return null;
        });
    synchronized (this) {
      if (token != generation) return;
      connectTask = task;
      connectWatchdog =
          CONNECT_TIMER.schedule(
              () -> fail(token, new IOException("ADB 连接超过 " + connectTimeoutMs + " 毫秒"), true),
              connectTimeoutMs,
              TimeUnit.MILLISECONDS);
    }
    workers.execute(task);
  }

  private void fail(long token, Exception error) {
    fail(token, error, false);
  }

  private void fail(long token, Exception error, boolean onlyWhileConnecting) {
    if (android.os.Build.VERSION.SDK_INT > 0)
      android.util.Log.e("CarMirrorADB", "ADB session failed", error);
    Transport failed;
    List<AdbChannel> stale;
    Future<?> task;
    ScheduledFuture<?> watchdog;
    synchronized (this) {
      if (token != generation || (onlyWhileConnecting && !connecting)) return;
      if (!connecting && transport == null) return;
      failed = transport;
      transport = null;
      ready = false;
      connecting = false;
      task = connectTask;
      connectTask = null;
      watchdog = connectWatchdog;
      connectWatchdog = null;
      stale = new ArrayList<>(channels);
      channels.clear();
    }
    if (task != null) task.cancel(true);
    if (watchdog != null) watchdog.cancel(false);
    String detail = error.getMessage();
    if (detail == null || detail.trim().isEmpty()) detail = error.getClass().getSimpleName();
    if (onlyWhileConnecting) {
      // A stalled library close must not delay the deadline reported to the UI.
      state(token, ConnectionState.ERROR, "ADB 连接失败：" + detail);
      workers.execute(
          () -> {
            for (AdbChannel c : stale) close(c);
            close(failed);
          });
    } else {
      close(failed);
      for (AdbChannel c : stale) close(c);
      state(token, ConnectionState.ERROR, "ADB 连接失败：" + detail);
    }
  }

  public void disconnect() {
    final Transport old;
    final List<AdbChannel> stale;
    final long token;
    final Future<?> task;
    final ScheduledFuture<?> watchdog;
    synchronized (this) {
      token = ++generation;
      old = transport;
      transport = null;
      ready = false;
      connecting = false;
      task = connectTask;
      connectTask = null;
      watchdog = connectWatchdog;
      connectWatchdog = null;
      stale = new ArrayList<>(channels);
      channels.clear();
    }
    if (task != null) task.cancel(true);
    if (watchdog != null) watchdog.cancel(false);
    workers.execute(
        () -> {
          for (AdbChannel c : stale) close(c);
          close(old);
          state(token, ConnectionState.DISCONNECTED, "已断开");
        });
  }

  public AdbChannel openService(String destination) throws IOException {
    if (!(destination.equals("shell:")
            || destination.equals("sync:")
            || destination.startsWith("localabstract:"))
        || !destination.matches("[\\x20-\\x7E]+")
        || destination.getBytes(StandardCharsets.UTF_8).length > 96
        || destination.indexOf('\0') >= 0) throw new IOException("不支持或过长的 ADB 服务名称");
    final Transport current;
    final long token;
    synchronized (this) {
      current = transport;
      token = generation;
      if (!ready) {
        throw new CommandUnavailableException(
            connecting ? "ADB 正在连接，请等待授权完成" : "ADB 未连接");
      }
    }
    if (current == null) throw new CommandUnavailableException("ADB 未连接");
    try {
      AdbChannel raw = current.open(destination);
      final AdbChannel[] owned = new AdbChannel[1];
      AdbChannel channel =
          new AdbChannel(
              raw.input(),
              raw.output(),
              () -> {
                try {
                  raw.close();
                } finally {
                  synchronized (this) {
                    channels.remove(owned[0]);
                  }
                }
              });
      owned[0] = channel;
      synchronized (this) {
        if (current != transport || !ready) {
          close(channel);
          throw new IOException("连接已替换");
        }
        channels.add(channel);
      }
      return channel;
    } catch (Exception e) {
      // A forward-mode scrcpy server may not have bound its per-session socket yet.
      // libadb uses ConnectException specifically for an OPEN rejected by the peer.
      if (!(e instanceof java.net.ConnectException
          && destination.matches("localabstract:scrcpy_[0-7][0-9a-f]{7}"))) fail(token, e);
      throw new IOException(e);
    }
  }

  public CommandResult shell(String command, long timeoutMs) throws IOException {
    if (timeoutMs <= 0) throw new IllegalArgumentException("timeoutMs must be positive");
    final long commandGeneration;
    synchronized (this) {
      commandGeneration = generation;
    }
    final java.util.concurrent.atomic.AtomicReference<AdbChannel> active =
        new java.util.concurrent.atomic.AtomicReference<>();
    final java.util.concurrent.atomic.AtomicBoolean canceled =
        new java.util.concurrent.atomic.AtomicBoolean();
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    Future<CommandResult> job =
        workers.submit(
            () -> {
              AdbChannel channel = openService("shell:");
              active.set(channel);
              if (canceled.get()) {
                close(channel);
                throw new IOException("命令已取消");
              }
              String escaped = command.replace("'", "'\"'\"'");
              channel
                  .output()
                  .write(
                      ("stty -echo 2>/dev/null; sh -c '"
                              + escaped
                              + "'; printf '\\n__CAR_MIRROR_EXIT__%s\\n' \"$?\"; exit\n")
                          .getBytes(StandardCharsets.UTF_8));
              channel.output().flush();
              byte[] buffer = new byte[4096];
              int n;
              while ((n = channel.input().read(buffer)) != -1) {
                synchronized (output) {
                  if (output.size() + n > MAX_COMMAND_OUTPUT_BYTES)
                    throw new CommandOutputLimitException();
                  output.write(buffer, 0, n);
                }
              }
              String text = output.toString("UTF-8");
              int marker = text.lastIndexOf("__CAR_MIRROR_EXIT__");
              int code = -1;
              if (marker >= 0) {
                try {
                  code = Integer.parseInt(text.substring(marker + 19).trim());
                } catch (NumberFormatException ignored) {
                }
                text = text.substring(0, marker);
              }
              return new CommandResult(text, code, false);
            });
    try {
      return job.get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      synchronized (output) {
        return new CommandResult(output.toString("UTF-8"), -1, true);
      }
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (!(cause instanceof CommandUnavailableException)
          && !(cause instanceof CommandOutputLimitException)) fail(commandGeneration, e);
      String message =
          cause instanceof CommandUnavailableException || cause instanceof CommandOutputLimitException
              ? cause.getMessage()
              : "命令失败";
      throw new IOException(message, cause);
    } catch (Exception e) {
      fail(commandGeneration, e);
      throw new IOException("命令失败", e);
    } finally {
      canceled.set(true);
      close(active.get());
      job.cancel(true);
    }
  }

  public void push(InputStream source, long size, String path) throws IOException {
    if (size < 0 || path.indexOf('\0') >= 0 || path.getBytes(StandardCharsets.UTF_8).length > 1000)
      throw new IOException("无效的推送路径或大小");
    try (AdbChannel channel = openService("sync:")) {
      DataOutputStream out = new DataOutputStream(channel.output());
      byte[] name = (path + ",33206").getBytes(StandardCharsets.UTF_8);
      packet(out, "SEND", name.length);
      out.write(name);
      byte[] data = new byte[65536];
      long remaining = size;
      while (remaining > 0) {
        int n = source.read(data, 0, (int) Math.min(data.length, remaining));
        if (n < 0) throw new EOFException("源文件过短");
        if (n == 0) continue;
        packet(out, "DATA", n);
        out.write(data, 0, n);
        remaining -= n;
      }
      packet(out, "DONE", (int) (System.currentTimeMillis() / 1000));
      out.flush();
      DataInputStream in = new DataInputStream(channel.input());
      byte[] id = new byte[4];
      in.readFully(id);
      int length = Integer.reverseBytes(in.readInt());
      if (!new String(id, StandardCharsets.US_ASCII).equals("OKAY")) {
        if (length < 0 || length > 65536) throw new IOException("无效的 sync 响应");
        byte[] message = new byte[length];
        in.readFully(message);
        throw new IOException(new String(message, StandardCharsets.UTF_8));
      }
    }
  }

  private static void packet(DataOutputStream out, String id, int length) throws IOException {
    out.writeBytes(id);
    out.writeInt(Integer.reverseBytes(length));
  }

  private static void close(Closeable c) {
    if (c != null)
      try {
        c.close();
      } catch (IOException ignored) {
      }
  }
}
