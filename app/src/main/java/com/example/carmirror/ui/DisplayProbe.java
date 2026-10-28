package com.example.carmirror.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the display list a head unit exposes. A joined panel reports one very wide display; a
 * car with two separate screens reports two — but many cars also publish virtual displays
 * (cluster sharing, remote display), so only physical panels may be counted. Aspect ratio alone
 * is not a decision: ultrawide single panels match it too.
 */
public final class DisplayProbe {
  public static final class Info {
    public final int id, width, height;
    public final String name, type;

    Info(int id, String name, int width, int height, String type) {
      this.id = id;
      this.name = name;
      this.width = width;
      this.height = height;
      this.type = type;
    }

    public boolean physical() {
      return "BUILT_IN".equals(type) || "EXTERNAL".equals(type) || "HDMI".equals(type)
          || "INTERNAL".equals(type);
    }

    @Override public String toString() {
      return id + ": " + width + "×" + height;
    }
  }

  /** {@code cmd display get-displays}, Android 11+. */
  private static final Pattern CMD_ENTRY = Pattern.compile(
      "Display id (\\d+):\\s*DisplayInfo\\{\"([^\"]*)\".*?real (\\d+) x (\\d+).*?type (\\w+)",
      Pattern.DOTALL);

  /** {@code dumpsys display} on Android 5–10, which has no cmd display command. */
  private static final Pattern DUMP_ENTRY = Pattern.compile(
      "DisplayDeviceInfo\\{\"([^\"]*)\":\\s*uniqueId=\"([^\"]*)\".*?(\\d+) x (\\d+).*?type (\\w+)",
      Pattern.DOTALL);

  private static final Pattern LOCAL_ID = Pattern.compile("local:(\\d+)");

  private DisplayProbe() {}

  /** Parses either shell format, chosen by content so both API levels work. */
  public static List<Info> parse(String output) {
    List<Info> displays = new ArrayList<>();
    if (output == null || output.isEmpty()) return displays;
    if (output.contains("DisplayDeviceInfo{")) return parseDumpsys(output);
    Matcher m = CMD_ENTRY.matcher(output);
    while (m.find()) {
      try {
        displays.add(new Info(Integer.parseInt(m.group(1)), m.group(2),
            Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), m.group(5)));
      } catch (NumberFormatException ignored) {
        // A malformed entry should not hide the others.
      }
    }
    return displays;
  }

  private static List<Info> parseDumpsys(String output) {
    List<Info> displays = new ArrayList<>();
    Matcher m = DUMP_ENTRY.matcher(output);
    int index = 0;
    while (m.find()) {
      try {
        String uniqueId = m.group(2);
        Matcher local = LOCAL_ID.matcher(uniqueId);
        int id = local.find() ? Integer.parseInt(local.group(1)) : index;
        displays.add(new Info(id, m.group(1),
            Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), m.group(5)));
      } catch (NumberFormatException ignored) {
        // A malformed entry should not hide the others.
      }
      index++;
    }
    return displays;
  }

  /** Physical panels only; scrcpy mirrors and cluster/remote sharing are excluded. */
  public static List<Info> physical(List<Info> displays) {
    List<Info> out = new ArrayList<>();
    for (Info d : displays) if (d.physical()) out.add(d);
    return out;
  }

  /**
   * True when the car reports two or more physical panels. A single joined panel still needs the
   * user's confirmation, because it is indistinguishable from an ultrawide single screen.
   */
  public static boolean separatePanels(List<Info> displays) {
    return physical(displays).size() >= 2;
  }

  /** Joining two identical halves is what a 3.2:1 class panel looks like; a hint, not proof. */
  public static boolean looksLikeJoinedPanel(int width, int height) {
    return DisplayLayout.looksDual(width, height);
  }
}
