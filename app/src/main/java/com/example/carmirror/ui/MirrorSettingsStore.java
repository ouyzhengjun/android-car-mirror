package com.example.carmirror.ui;

import android.content.Context;
import android.content.SharedPreferences;
import com.example.carmirror.adb.DeviceEntry;
import com.example.carmirror.scrcpy.CropRect;
import com.example.carmirror.scrcpy.MirrorSettings;

/**
 * Per-device mirror preferences, keyed exactly like {@link MirrorSettingsSection} so settings
 * saved by either screen stay interchangeable.
 */
public final class MirrorSettingsStore {
  private static final String[] NAMES = {"display", "max", "rate", "fps", "w", "h", "x", "y"};
  private static final String[] DEFAULTS = {"0", "2560", "8", "30", "2560", "1600", "0", "0"};
  private final SharedPreferences prefs;

  public MirrorSettingsStore(Context c) {
    prefs = c.getApplicationContext().getSharedPreferences("mirror-settings", 0);
  }

  /**
   * Stable per-device key. USB falls back to the device type when the serial is unavailable, so
   * two identical head units can share a profile; the live selection still distinguishes them.
   */
  public static String key(DeviceEntry entry) {
    if (entry == null) return "none";
    if (entry.tcp != null) return entry.id;
    return "usb:" + entry.usb.vendorId + ":" + entry.usb.productId + ":"
        + (entry.usb.serialNumber == null ? "type" : entry.usb.serialNumber);
  }

  /** True when this device has an explicit single/dual choice; otherwise it is auto-detected. */
  public boolean hasDual(DeviceEntry entry) {
    return prefs.contains(key(entry) + "dual");
  }

  public MirrorSettings load(DeviceEntry entry) {
    String key = key(entry);
    int region = prefs.getInt(key + "region", -1);
    if (region < 0 || region >= MirrorSettings.Region.values().length) return defaultSettings();
    int[] value = new int[NAMES.length];
    for (int i = 0; i < NAMES.length; i++) {
      try {
        value[i] = Integer.parseInt(prefs.getString(key + NAMES[i], DEFAULTS[i]));
      } catch (NumberFormatException e) {
        return defaultSettings();
      }
    }
    CropRect crop =
        region == MirrorSettings.Region.CUSTOM.ordinal()
            ? new CropRect(value[4], value[5], value[6], value[7])
            : null;
    MirrorSettings.Region selected = MirrorSettings.Region.values()[region];
    // A split region only exists on a joined panel; on a single-screen unit it would crop half
    // the picture away and blow the rest up, so fall back to the full display.
    if (!dual(entry) && (selected == MirrorSettings.Region.MAIN
        || selected == MirrorSettings.Region.SECONDARY)) {
      selected = MirrorSettings.Region.FULL;
      crop = null;
    }
    try {
      boolean nativeSize = prefs.getBoolean(key(entry) + "native", true);
      return new MirrorSettings(selected, value[0], value[1], value[2] * 1000000, value[3], crop,
          nativeSize);
    } catch (IllegalArgumentException e) {
      return defaultSettings();
    }
  }

  /** Single-screen default: whole display, encoded at the head unit's own resolution. */
  public static MirrorSettings defaultSettings() {
    return new MirrorSettings(MirrorSettings.Region.FULL, 0, 2560, 8000000, 30, null, true);
  }

  /** True when this device was explicitly configured as a joined dual-screen panel. */
  public boolean dual(DeviceEntry entry) {
    return prefs.getBoolean(key(entry) + "dual", false);
  }

  public void dual(DeviceEntry entry, boolean dual) {
    prefs.edit().putBoolean(key(entry) + "dual", dual).apply();
  }

  public void save(DeviceEntry entry, MirrorSettings settings) {
    String key = key(entry);
    SharedPreferences.Editor edit = prefs.edit().putInt(key + "region", settings.region.ordinal());
    edit.putString(key + "display", String.valueOf(settings.displayId));
    edit.putString(key + "max", String.valueOf(settings.maxSize));
    edit.putString(key + "rate", String.valueOf(settings.bitRate / 1000000));
    edit.putString(key + "fps", String.valueOf(settings.maxFps));
    CropRect crop = settings.customCrop;
    edit.putString(key + "w", String.valueOf(crop == null ? 2560 : crop.width));
    edit.putString(key + "h", String.valueOf(crop == null ? 1600 : crop.height));
    edit.putString(key + "x", String.valueOf(crop == null ? 0 : crop.x));
    edit.putString(key + "y", String.valueOf(crop == null ? 0 : crop.y));
    edit.putBoolean(key + "native", settings.nativeSize);
    edit.apply();
  }
}
