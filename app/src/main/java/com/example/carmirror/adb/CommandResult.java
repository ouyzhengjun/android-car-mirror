package com.example.carmirror.adb;

public final class CommandResult {
  private final String output;
  private final int exitCode;
  private final boolean timedOut;

  public CommandResult(String output, int exitCode, boolean timedOut) {
    this.output = output;
    this.exitCode = exitCode;
    this.timedOut = timedOut;
  }

  public String output() {
    return output;
  }

  public int exitCode() {
    return exitCode;
  }

  public boolean timedOut() {
    return timedOut;
  }
}
