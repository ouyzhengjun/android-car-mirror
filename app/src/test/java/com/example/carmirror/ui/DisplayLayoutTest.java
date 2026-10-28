package com.example.carmirror.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DisplayLayoutTest {

  @Test
  public void joinedDualPanelLooksDual() {
    assertTrue(DisplayLayout.looksDual(5120, 1600));
  }

  @Test
  public void ordinaryPanelsStaySingle() {
    assertFalse(DisplayLayout.looksDual(1920, 1080));
    assertFalse(DisplayLayout.looksDual(2560, 1600));
    assertFalse(DisplayLayout.looksDual(1280, 800));
    assertFalse(DisplayLayout.looksDual(3840, 2160));
  }

  @Test
  public void unknownSizeIsNeverDual() {
    assertFalse(DisplayLayout.looksDual(0, 0));
    assertFalse(DisplayLayout.looksDual(5120, 0));
  }
}
