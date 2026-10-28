package com.example.carmirror.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class DisplayProbeTest {

  private static final String SINGLE_JOINED =
      "Displays:\n"
          + "Display id 0: DisplayInfo{\"iCockpit\", displayId 0, displayGroupId 0, FLAG_SECURE, "
          + "real 5120 x 1600, largest app 5120 x 1600, type INTERNAL, state ON, "
          + "uniqueId \"local:1\"}\n";

  private static final String TWO_PANELS =
      "Displays:\n"
          + "Display id 0: DisplayInfo{\"cluster\", displayId 0, displayGroupId 0, FLAG_SECURE, "
          + "real 1920 x 1080, largest app 1920 x 1080, type INTERNAL, state ON, "
          + "uniqueId \"local:1\"}\n"
          + "Display id 2: DisplayInfo{\"center\", displayId 2, displayGroupId 0, FLAG_SECURE, "
          + "real 2560 x 1600, largest app 2560 x 1600, type INTERNAL, state ON, "
          + "uniqueId \"local:2\"}\n";

  private static final String WITH_VIRTUAL =
      "Displays:\n"
          + "Display id 0: DisplayInfo{\"内置屏幕\", displayId 0, displayGroupId 0, FLAG_SECURE, "
          + "real 3840 x 2560, largest app 3840 x 3840, type INTERNAL, state ON, "
          + "uniqueId \"local:1\"}\n"
          + "Display id 15: DisplayInfo{\"scrcpy\", displayId 15, displayGroupId 0, FLAG_PRIVATE, "
          + "real 3840 x 2560, largest app 3840 x 3840, type VIRTUAL, state ON, "
          + "uniqueId \"virtual:com.android.shell,2000,scrcpy,13\"}\n";

  @Test public void parsesIdSizeAndType() {
    List<DisplayProbe.Info> joined = DisplayProbe.parse(SINGLE_JOINED);
    assertEquals(1, joined.size());
    assertEquals(0, joined.get(0).id);
    assertEquals(5120, joined.get(0).width);
    assertEquals(1600, joined.get(0).height);
    assertEquals("INTERNAL", joined.get(0).type);
  }

  @Test public void detectsTwoPhysicalPanels() {
    List<DisplayProbe.Info> two = DisplayProbe.parse(TWO_PANELS);
    assertEquals(2, two.size());
    assertTrue(DisplayProbe.separatePanels(two));
    assertEquals(2, DisplayProbe.physical(two).get(1).id);
  }

  @Test public void joinedPanelIsAsingleDisplayNotTwo() {
    List<DisplayProbe.Info> joined = DisplayProbe.parse(SINGLE_JOINED);
    assertFalse(DisplayProbe.separatePanels(joined));
    // It still hints at a joined panel by its shape, but only as a suggestion.
    assertTrue(DisplayProbe.looksLikeJoinedPanel(5120, 1600));
  }

  @Test public void virtualDisplaysAreIgnored() {
    List<DisplayProbe.Info> parsed = DisplayProbe.parse(WITH_VIRTUAL);
    assertEquals(2, parsed.size());
    assertEquals(1, DisplayProbe.physical(parsed).size());
    assertFalse(DisplayProbe.separatePanels(parsed));
  }

  /** Real output from the single-screen car at 192.168.43.86 (API 29). */
  private static final String CAR_API29 =
      "  DisplayDeviceInfo{\"内置屏幕\": uniqueId=\"local:0\", 1920 x 1080, modeId 1, "
          + "defaultModeId 1, density 160, touch INTERNAL, rotation 0, type BUILT_IN, "
          + "address {port=0}, state ON, FLAG_DEFAULT_DISPLAY}\n"
          + "  DisplayDeviceInfo{\"Share Display cluster\": "
          + "uniqueId=\"virtual:com.semidrive.multidisplay:sharing:cluster\", 1920 x 720, "
          + "modeId 2, density 160, touch VIRTUAL, rotation 0, type VIRTUAL, state ON, "
          + "owner com.semidrive.multidisplay (uid 1000)}\n";

  @Test public void readsDumpsysOnAndroid10AndIgnoresVirtualPanels() {
    List<DisplayProbe.Info> parsed = DisplayProbe.parse(CAR_API29);
    assertEquals(2, parsed.size());
    // The cluster share display must not turn a single-screen car into a dual-screen one.
    assertEquals(1, DisplayProbe.physical(parsed).size());
    assertFalse(DisplayProbe.separatePanels(parsed));
    DisplayProbe.Info panel = DisplayProbe.physical(parsed).get(0);
    assertEquals(0, panel.id);
    assertEquals(1920, panel.width);
    assertEquals(1080, panel.height);
    assertFalse(DisplayProbe.looksLikeJoinedPanel(panel.width, panel.height));
  }

  @Test public void twoBuiltInPanelsInDumpsysAreSeparateScreens() {
    String dual = "  DisplayDeviceInfo{\"left\": uniqueId=\"local:0\", 1920 x 1080, type BUILT_IN}\n"
        + "  DisplayDeviceInfo{\"right\": uniqueId=\"local:1\", 1920 x 1080, type BUILT_IN}\n";
    List<DisplayProbe.Info> parsed = DisplayProbe.parse(dual);
    assertTrue(DisplayProbe.separatePanels(parsed));
    assertEquals(1, DisplayProbe.physical(parsed).get(1).id);
  }

  @Test public void ordinarySingleScreenStaysSingle() {
    assertFalse(DisplayProbe.looksLikeJoinedPanel(1920, 1080));
    assertFalse(DisplayProbe.looksLikeJoinedPanel(2560, 1600));
  }

  @Test public void emptyOrUnparsableOutputIsSafe() {
    assertTrue(DisplayProbe.parse("").isEmpty());
    assertTrue(DisplayProbe.parse(null).isEmpty());
    assertFalse(DisplayProbe.separatePanels(DisplayProbe.parse("garbage")));
  }
}
