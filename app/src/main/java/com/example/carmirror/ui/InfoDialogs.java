package com.example.carmirror.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/** Read-only list dialogs used by the diagnostics rows. */
public final class InfoDialogs {

  private InfoDialogs() {}

  public static void log(Activity activity) {
    StringBuilder text = new StringBuilder();
    List<MirrorLog.Entry> entries = MirrorLog.entries();
    if (entries.isEmpty()) text.append("还没有记录。");
    for (MirrorLog.Entry e : entries) {
      if (text.length() > 0) text.append('\n');
      text.append(e.line());
    }
    show(activity, "投屏日志", text.toString());
  }

  public static void about(Activity activity) {
    show(activity, "关于",
        "车机投屏 0.2.0\n\n"
            + "内置 scrcpy 4.0 server，无需电脑中转、root 或系统悬浮窗权限。\n"
            + "ADB 私钥保存在应用私有目录。\n"
            + "演示模式仅在应用前台运行；USB 每次重新插入都需要系统授权。");
  }

  public static void show(Activity activity, String title, String message) {
    int pad = Ui.dp(activity, 24);
    TextView body = Ui.text(activity, message, 16, Ui.ON);
    body.setLineSpacing(Ui.dp(activity, 4), 1f);
    ScrollView scroll = new ScrollView(activity);
    scroll.setPadding(pad, pad, pad, pad);
    scroll.addView(body);
    LinearLayout wrap = Ui.column(activity);
    wrap.addView(scroll, new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(activity, 420)));
    new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
        .setTitle(title)
        .setView(wrap)
        .setPositiveButton("好", null)
        .show();
  }
}
