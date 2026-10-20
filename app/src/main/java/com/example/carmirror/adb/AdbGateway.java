package com.example.carmirror.adb;

import com.example.carmirror.Endpoint;
import java.io.*;

public interface AdbGateway {
  interface Listener {
    void onState(ConnectionState state, String message);
  }

  void connect(Endpoint endpoint, Listener listener);

  void disconnect();

  CommandResult shell(String command, long timeoutMs) throws IOException;

  AdbChannel openService(String destination) throws IOException;

  void push(InputStream source, long size, String remotePath) throws IOException;
}
