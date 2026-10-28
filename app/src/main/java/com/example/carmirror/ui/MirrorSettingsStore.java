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
    try {
      return new MirrorSettings(MirrorSettings.Region.values()[region], value[0], value[1],
          value[2] * 1000000, value[3], crop);
    } catch (IllegalArgumentException e) {
      return defaultSettings();
    }
  }

  public static MirrorSettings defaultSettings() {
    return new MirrorSettings(MirrorSettings.Region.MAIN, 0, 2560, 8000000, 30, null);
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
    edit.apply();
  }
}
