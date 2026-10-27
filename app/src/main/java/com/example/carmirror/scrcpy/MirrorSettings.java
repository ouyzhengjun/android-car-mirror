package com.example.carmirror.scrcpy;

import java.util.Objects;

/** Saved selection; resolve again against current display dimensions before each session. */
public final class MirrorSettings {
  public enum Region { FULL, MAIN, SECONDARY, CUSTOM }
  public final Region region;
  public final int displayId, maxSize, bitRate, maxFps;
  public final CropRect customCrop;

  public MirrorSettings(Region region, int displayId, int maxSize, int bitRate, int maxFps, CropRect customCrop) {
    this.region = Objects.requireNonNull(region);
    new MirrorOptions(displayId, customCrop, maxSize, bitRate, maxFps);
    this.displayId = displayId;
    this.maxSize = maxSize;
    this.bitRate = bitRate;
    this.maxFps = maxFps;
    this.customCrop = customCrop;
  }

  public static MirrorSettings defaultForDisplay(int width, int height) {
    return new MirrorSettings(width == 5120 && height == 1600 ? Region.MAIN : Region.FULL,
        0, 2560, 8000000, 30, null);
  }

  public MirrorOptions resolve(int displayWidth, int displayHeight) {
    CropRect crop = null;
    if (region == Region.MAIN || region == Region.SECONDARY) {
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
    return new MirrorOptions(displayId, crop, maxSize, bitRate, maxFps);
  }
}
