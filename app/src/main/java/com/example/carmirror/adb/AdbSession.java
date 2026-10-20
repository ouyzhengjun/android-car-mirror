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
  public interface Transport extends Closeable {
    boolean connect(long timeoutMs) throws Exception;

    AdbChannel open(String destination) throws Exception;
  }

  public interface Factory {
    Transport create(Endpoint endpoint) throws Exception;
  }

  private final Factory factory;
  private final Executor callbacks;
  private final ExecutorService workers =
      Executors.newCachedThreadPool(
          r -> {
            Thread t = new Thread(r, "adb-worker");
            t.setDaemon(true);
            return t;
          });
  private final Set<AdbChannel> channels = new HashSet<>();
  private Transport transport;
  private long generation;
  private Listener listener;
  private Endpoint endpoint;

  public AdbSession(Factory factory, Executor callbacks) {
    this.factory = factory;
    this.callbacks = callbacks;
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
    synchronized (this) {
      token = ++generation;
      this.listener = listener;
      this.endpoint = endpoint;
      old = transport;
      transport = null;
      stale = new ArrayList<>(channels);
      channels.clear();
    }
    workers.execute(
        () -> {
          close(old);
          for (AdbChannel c : stale) close(c);
          state(token, ConnectionState.CONNECTING, "正在连接 " + endpoint.serial());
          try {
            Transport next = factory.create(endpoint);
            synchronized (this) {
              if (token != generation) {
                close(next);
                return;
              }
              transport = next;
            }
            state(token, ConnectionState.AUTHORIZING, "请在车机上允许此设备的 USB 调试授权");
            if (!next.connect(30000)) throw new IOException("ADB 授权或连接超时");
            state(token, ConnectionState.CONNECTED, "已连接：" + endpoint.serial());
          } catch (Exception e) {
            fail(token, e);
          }
        });
  }

  private void fail(long token, Exception error) {
    Transport failed;
    List<AdbChannel> stale;
    synchronized (this) {
      if (token != generation) return;
      failed = transport;
      transport = null;
      stale = new ArrayList<>(channels);
      channels.clear();
    }
    close(failed);
    for (AdbChannel c : stale) close(c);
    state(token, ConnectionState.ERROR, String.valueOf(error.getMessage()));
  }

  public void disconnect() {
    final Transport old;
    final List<AdbChannel> stale;
    final long token;
    synchronized (this) {
      token = ++generation;
      old = transport;
      transport = null;
      stale = new ArrayList<>(channels);
      channels.clear();
    }
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
    }
    if (current == null) throw new IOException("ADB 未连接");
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
        if (current != transport) {
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
                  if (output.size() + n > 4 * 1024 * 1024) throw new IOException("命令输出超过 4 MiB");
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
