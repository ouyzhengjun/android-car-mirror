package com.example.carmirror.scrcpy;

/** Immutable validated launch snapshot. A null crop means the complete display. */
public final class MirrorOptions {
  public final int displayId, maxSize, bitRate, maxFps;
  public final CropRect crop;
  /** True fills the screen and crops the overflow; false keeps the whole picture with bars. */
  public final boolean cover;

  public MirrorOptions(int displayId, CropRect crop, int maxSize, int bitRate, int maxFps) {
    this(displayId, crop, maxSize, bitRate, maxFps, true);
  }

  public MirrorOptions(int displayId, CropRect crop, int maxSize, int bitRate, int maxFps,
      boolean cover) {
    if (displayId < 0 || displayId > 255) throw new IllegalArgumentException("Display ID must be 0–255");
    if (maxSize < 256 || maxSize > 8192 || maxSize % 16 != 0)
      throw new IllegalArgumentException("Maximum size must be 256–8192 and a multiple of 16");
    if (bitRate < 1000000 || bitRate > 50000000)
      throw new IllegalArgumentException("Video bit rate must be 1–50 Mbps");
    if (maxFps < 1 || maxFps > 60) throw new IllegalArgumentException("Frame rate must be 1–60");
    this.displayId = displayId;
    this.crop = crop;
    this.maxSize = maxSize;
    this.bitRate = bitRate;
    this.maxFps = maxFps;
    this.cover = cover;
  }
}
