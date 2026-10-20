package com.example.carmirror.scrcpy;

import com.example.carmirror.adb.AdbChannel;
import com.example.carmirror.adb.AdbGateway;
import java.io.IOException;

public interface ScrcpySession {
  Streams start(AdbGateway adb) throws IOException;

  void stop();

  final class Streams {
    private final AdbChannel video, control;

    public Streams(AdbChannel video, AdbChannel control) {
      this.video = video;
      this.control = control;
    }

    public AdbChannel video() {
      return video;
    }

    public AdbChannel control() {
      return control;
    }
  }
}
