package com.example.carmirror.ui;

/** Guesses whether a head unit is a joined dual-screen panel from its display size. */
public final class DisplayLayout {
  /** 5120 x 1600 is 3.2:1; a single 16:9 panel is 1.78:1, so 2.8 separates them safely. */
  public static final float DUAL_MIN_ASPECT = 2.8f;

  private DisplayLayout() {}

  public static boolean looksDual(int width, int height) {
    if (width <= 0 || height <= 0) return false;
    return width / (float) height >= DUAL_MIN_ASPECT;
  }
}
