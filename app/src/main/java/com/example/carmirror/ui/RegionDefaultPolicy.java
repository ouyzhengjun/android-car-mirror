package com.example.carmirror.ui;
/** A discovered default must never replace an explicitly edited or saved region. */
public final class RegionDefaultPolicy {
  private boolean pending;
  public RegionDefaultPolicy(boolean saved) { pending = !saved; }
  public void userEdited() { pending = false; }
  public int resolve(int selected, int display, int width, int height) {
    boolean apply = pending && selected == 0 && display == 0 && width == 5120 && height == 1600;
    pending = false;
    return apply ? 1 : selected;
  }
}
