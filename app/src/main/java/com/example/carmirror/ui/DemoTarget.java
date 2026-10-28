package com.example.carmirror.ui;

import android.content.Context;
import android.content.SharedPreferences;
import com.example.carmirror.Endpoint;

/** The wireless device demo mode should try before falling back to USB. */
public final class DemoTarget {
  private static final String FILE = "demo";
  private static final String SERIAL = "preferred";

  private DemoTarget() {}

  public static Endpoint preferred(Context c) {
    SharedPreferences p = c.getApplicationContext().getSharedPreferences(FILE, 0);
    String serial = p.getString(SERIAL, null);
    if (serial == null) return null;
    int colon = serial.lastIndexOf(':');
    if (colon <= 0) return null;
    try {
      return Endpoint.parse(serial.substring(0, colon), serial.substring(colon + 1));
    } catch (RuntimeException e) {
      return null;
    }
  }

  public static String preferredSerial(Context c) {
    SharedPreferences p = c.getApplicationContext().getSharedPreferences(FILE, 0);
    return p.getString(SERIAL, null);
  }

  public static void prefer(Context c, Endpoint endpoint) {
    c.getApplicationContext().getSharedPreferences(FILE, 0).edit()
        .putString(SERIAL, endpoint == null ? null : endpoint.serial()).commit();
  }

  public static void clear(Context c) {
    c.getApplicationContext().getSharedPreferences(FILE, 0).edit().remove(SERIAL).commit();
  }
}
