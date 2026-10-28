package com.example.carmirror.ui;

import android.content.Context;
import android.content.SharedPreferences;
import com.example.carmirror.Endpoint;
import com.example.carmirror.adb.ConnectionOwner;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Saved wireless endpoints live in preferences; the catalog is rebuilt from them per process. */
public final class SavedDevices {
  private static final String FILE = "connections";
  private static final String KEY_SET = "devices";

  private SavedDevices() {}

  public static List<Endpoint> endpoints(Context c) {
    SharedPreferences p = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    Set<String> serials = new HashSet<>(p.getStringSet(KEY_SET, Collections.<String>emptySet()));
    List<String> sorted = new ArrayList<>(serials);
    Collections.sort(sorted);
    List<Endpoint> out = new ArrayList<>();
    for (String serial : sorted) {
      int colon = serial.lastIndexOf(':');
      if (colon <= 0) continue;
      try {
        out.add(Endpoint.parse(serial.substring(0, colon), serial.substring(colon + 1)));
      } catch (RuntimeException ignored) {
        // A malformed saved entry should not hide the valid ones.
      }
    }
    return out;
  }

  /** Registers every saved endpoint in the process catalog, so pickers can list them. */
  public static void seed(Context c) {
    for (Endpoint e : endpoints(c)) ConnectionOwner.catalog().saveTcp(e);
  }

  public static void add(Context c, Endpoint endpoint) {
    SharedPreferences p = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    Set<String> serials = new HashSet<>(p.getStringSet(KEY_SET, Collections.<String>emptySet()));
    serials.add(endpoint.serial());
    p.edit().putString("host", endpoint.host())
        .putString("port", String.valueOf(endpoint.port()))
        .putStringSet(KEY_SET, serials).commit();
    ConnectionOwner.catalog().saveTcp(endpoint);
  }

  public static void remove(Context c, Endpoint endpoint) {
    SharedPreferences p = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    Set<String> serials = new HashSet<>(p.getStringSet(KEY_SET, Collections.<String>emptySet()));
    if (!serials.remove(endpoint.serial())) return;
    p.edit().putStringSet(KEY_SET, serials).commit();
    ConnectionOwner.catalog().removeTcp(endpoint.serial());
  }
}
