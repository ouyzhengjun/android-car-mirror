package com.example.carmirror.scrcpy;

import java.util.Objects;

/** Saved selection; resolve again against current display dimensions before each session. */
public final class MirrorSettings {
  public enum Region { FULL, MAIN, SECONDARY, CUSTOM }
  public final Region region;
  public final int displayId, maxSize, bitRate, maxFps;
  public final CropRect customCrop;
  /** True when the encoder should use the head unit's own resolution instead of maxSize. */
  public final boolean nativeSize;
  /** Display id of the second physical panel, or -1 when both halves live on one panel. */
  public final int secondaryDisplayId;
  /** True fills the screen (crops); false shows the whole picture with dark bars. */
  public final boolean cover;

  public MirrorSettings(Region region, int displayId, int maxSize, int bitRate, int maxFps, CropRect customCrop) {
    this(region, displayId, maxSize, bitRate, maxFps, customCrop, false);
  }

  public MirrorSettings(Region region, int displayId, int maxSize, int bitRate, int maxFps,
      CropRect customCrop, boolean nativeSize) {
    this(region, displayId, maxSize, bitRate, maxFps, customCrop, nativeSize, -1);
  }

  public MirrorSettings(Region region, int displayId, int maxSize, int bitRate, int maxFps,
      CropRect customCrop, boolean nativeSize, int secondaryDisplayId) {
    this(region, displayId, maxSize, bitRate, maxFps, customCrop, nativeSize, secondaryDisplayId,
        true);
  }

  public MirrorSettings(Region region, int displayId, int maxSize, int bitRate, int maxFps,
      CropRect customCrop, boolean nativeSize, int secondaryDisplayId, boolean cover) {
    this.region = Objects.requireNonNull(region);
    new MirrorOptions(displayId, customCrop, maxSize, bitRate, maxFps);
    this.displayId = displayId;
    this.maxSize = maxSize;
    this.bitRate = bitRate;
    this.maxFps = maxFps;
    this.customCrop = customCrop;
    this.nativeSize = nativeSize;
    this.secondaryDisplayId = secondaryDisplayId;
    this.cover = cover;
  }

  /** scrcpy only downscales when the longest side exceeds max_size, so round up to 16 and stop. */
  static int capFor(int displayWidth, int displayHeight) {
    int longest = Math.max(displayWidth, displayHeight);
    int rounded = Math.min(8192, (longest + 15) / 16 * 16);
    return Math.max(256, rounded);
  }

  public static MirrorSettings defaultForDisplay(int width, int height) {
    return new MirrorSettings(width == 5120 && height == 1600 ? Region.MAIN : Region.FULL,
        0, 2560, 8000000, 30, null);
  }

  public MirrorOptions resolve(int displayWidth, int displayHeight) {
    CropRect crop = null;
    int target = displayId;
    if ((region == Region.MAIN || region == Region.SECONDARY) && secondaryDisplayId >= 0) {
      // Two real panels: mirror each one whole instead of cropping a joined desktop.
      target = region == Region.SECONDARY ? secondaryDisplayId : displayId;
    } else if (region == Region.MAIN || region == Region.SECONDARY) {
      if (displayWidth <= 0 || displayHeight <= 0)
        throw new IllegalArgumentException("Read display dimensions before selecting a crop");
      int half = displayWidth / 2;
      if (displayWidth % 2 != 0) throw new IllegalArgumentException("Display cannot be split equally");
      crop = new CropRect(half, displayHeight, region == Region.SECONDARY ? half : 0, 0);
    } else if (region == Region.CUSTOM) {
      if (customCrop == null) throw new IllegalArgumentException("Enter a custom crop");
      crop = customCrop;
    }
    if (crop != null) crop.validateWithin(displayWidth, displayHeight);
    int cap = nativeSize && displayWidth > 0 && displayHeight > 0
        ? capFor(displayWidth, displayHeight)
        : maxSize;
    return new MirrorOptions(target, crop, cap, bitRate, maxFps, cover);
  }
}
