package com.example.carmirror.scrcpy;

/** Crop coordinates in the original display, before server scaling. */
public final class CropRect {
  public final int width, height, x, y;

  public CropRect(int width, int height, int x, int y) {
    if (width <= 0 || height <= 0 || (width & 1) != 0 || (height & 1) != 0 || x < 0 || y < 0)
      throw new IllegalArgumentException("Crop needs positive even dimensions and nonnegative offsets");
    this.width = width;
    this.height = height;
    this.x = x;
    this.y = y;
  }

  public void validateWithin(int displayWidth, int displayHeight) {
    if (displayWidth <= 0 || displayHeight <= 0)
      throw new IllegalArgumentException("Read display dimensions before selecting a crop");
    if ((long) x + width > displayWidth || (long) y + height > displayHeight)
      throw new IllegalArgumentException("Crop exceeds display dimensions");
  }

  @Override public String toString() { return width + ":" + height + ":" + x + ":" + y; }
}
