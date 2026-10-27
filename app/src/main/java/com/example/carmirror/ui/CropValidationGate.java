package com.example.carmirror.ui;
import com.example.carmirror.scrcpy.CropRect;
/** Generation-scoped touch gate: encoded video dimensions cannot prove crop bounds. */
public final class CropValidationGate {
  private final CropRect crop;
  private long generation;
  private boolean allowed;
  public CropValidationGate(CropRect crop) { this.crop = crop; }
  public synchronized long begin() { allowed = false; return ++generation; }
  public synchronized boolean current(long token) { return token == generation; }
  public synchronized boolean validate(long token, int originalWidth, int originalHeight) {
    if (!current(token)) return false;
    if (crop != null) crop.validateWithin(originalWidth, originalHeight);
    allowed = true;
    return true;
  }
  public synchronized long generation() { return generation; }
  public synchronized boolean allowsTouch(long token) { return allowed && current(token); }
  public synchronized boolean allowsTouch() { return allowed; }
}
