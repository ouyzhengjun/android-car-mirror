package com.example.carmirror;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.example.carmirror.adb.CommandResult;
import com.example.carmirror.adb.ConnectionOwner;
import com.example.carmirror.adb.DeviceEntry;
import com.example.carmirror.scrcpy.MirrorSettings;
import com.example.carmirror.ui.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Two-column overview: current device and management on the left, diagnostics on the right. */
public final class SettingsActivity extends Activity {
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private MirrorSettingsStore store;

  private LinearLayout deviceSlot;
  private LinearLayout diagnosticsSlot;
  private TextView dimensionsRow;
  private String dimensionsText = "尚未读取";

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    store = new MirrorSettingsStore(this);
    SavedDevices.seed(this);
    LinearLayout root = Ui.column(this);
    root.setBackgroundColor(Ui.SURFACE);

    LinearLayout nav = Ui.row(this);
    nav.setPadding(Ui.dp(this, 56), Ui.dp(this, 12), Ui.dp(this, 56), 0);
    TextView back = Ui.text(this, "\u2190", 26, Ui.ON_VAR);
    back.setGravity(Gravity.CENTER);
    back.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
    back.setClickable(true);
    back.setOnClickListener(v -> finish());
    nav.addView(back);
    TextView title = Ui.text(this, "设置", 25, Ui.ON);
    title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
    LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    tp.leftMargin = Ui.dp(this, 16);
    nav.addView(title, tp);
    root.addView(nav);

    LinearLayout columns = Ui.row(this);
    columns.setGravity(Gravity.TOP);
    columns.setPadding(Ui.dp(this, 56), Ui.dp(this, 16), Ui.dp(this, 56), Ui.dp(this, 24));
    root.addView(columns, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

    LinearLayout left = Ui.column(this);
    LinearLayout right = Ui.column(this);
    LinearLayout.LayoutParams lp = Ui.lp(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
    columns.addView(left, lp);
    LinearLayout.LayoutParams rp = Ui.lp(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
    rp.leftMargin = Ui.dp(this, 36);
    columns.addView(right, rp);

    deviceSlot = left;
    diagnosticsSlot = right;
    setContentView(root);
    refresh();
  }

  @Override protected void onResume() {
    super.onResume();
    refresh();
  }

  @Override protected void onDestroy() {
    worker.shutdownNow();
    super.onDestroy();
  }

  private void refresh() {
    buildLeft();
    buildRight();
  }

  /* ---------------------------------------------------------------- left */

  private void buildLeft() {
    deviceSlot.removeAllViews();
    DeviceEntry selected = ConnectionOwner.catalog().selected();
    boolean connected = ConnectionOwner.isConnected();

    String sub = selected == null ? "在设备管理里选择一台车机" : ConnectionOwner.message();
    LinearLayout deviceCard = Ui.card(this);
    LinearLayout deviceRow = Ui.listRow(this,
        connected ? "\u26D3" : "\u25CB",
        selected == null ? "未选择设备" : selected.label,
        sub,
        connected ? Ui.textButton(this, "断开", v -> {
          ConnectionOwner.disconnect();
          refresh();
        }) : null);
    deviceCard.addView(deviceRow);
    deviceSlot.addView(deviceCard);

    deviceSlot.addView(sectionLabel("管理"));
    LinearLayout manage = Ui.card(this);
    manage.addView(nav("\u25A4", "设备管理", "USB 候选、已保存的无线设备、连接与断开",
        DeviceManagerActivity.class));
    manage.addView(Ui.divider(this));
    manage.addView(clickable(nav("\u2248", "无线连接", "输入车机 IP 与端口，加入已保存列表",
        null), v -> WirelessDialog.show(SettingsActivity.this, endpoint -> refresh())));
    manage.addView(Ui.divider(this));
    manage.addView(nav("\u25EB", "投屏设置", settingsSummary(), MirrorSettingsActivity.class));
    manage.addView(Ui.divider(this));
    manage.addView(nav("\u25B6", "进入演示模式", "插上 USB 或按首选设备自动投屏", DemoActivity.class));
    deviceSlot.addView(manage);

    LinearLayout stats = Ui.row(this);
    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    sp.topMargin = Ui.dp(this, 24);
    stats.addView(stat(String.valueOf(SavedDevices.endpoints(this).size()), "已保存无线设备"));
    stats.addView(stat(String.valueOf(usbCandidates()), "当前 USB 候选"));
    stats.addView(stat(MirrorLog.lastFailure().equals("还没有失败记录") ? "—" : "有", "最近失败记录"));
    deviceSlot.addView(stats, sp);
  }

  private String settingsSummary() {
    DeviceEntry entry = ConnectionOwner.catalog().selected();
    MirrorSettings s = store.load(entry);
    String region;
    switch (s.region) {
      case FULL: region = "全屏"; break;
      case MAIN: region = "仅主屏"; break;
      case SECONDARY: region = "仅副屏"; break;
      default: region = "自定义"; break;
    }
    return region + " · " + s.maxSize + " 边 · " + s.bitRate / 1000000 + " Mbps · " + s.maxFps + " fps";
  }

  private int usbCandidates() {
    int count = 0;
    for (DeviceEntry e : ConnectionOwner.catalog().entries()) if (e.usb != null) count++;
    return count;
  }

  private View stat(String value, String label) {
    LinearLayout card = Ui.column(this);
    card.addView(Ui.text(this, value, 32, Ui.ON));
    card.addView(Ui.text(this, label, 14, Ui.ON_VAR));
    card.setBackground(Ui.fill(Ui.SURFACE_C, 20, this));
    card.setPadding(Ui.dp(this, 24), Ui.dp(this, 20), Ui.dp(this, 24), Ui.dp(this, 20));
    LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    cp.rightMargin = Ui.dp(this, 16);
    card.setLayoutParams(cp);
    return card;
  }

  /* --------------------------------------------------------------- right */

  private void buildRight() {
    diagnosticsSlot.removeAllViews();
    diagnosticsSlot.addView(sectionLabel("诊断"));
    LinearLayout diagnostics = Ui.card(this);
    dimensionsRow = Ui.textButton(this, "读取", v -> readDimensions());
    diagnostics.addView(Ui.listRow(this, "\u25B1", "读取显示尺寸", dimensionsText, dimensionsRow));
    diagnostics.addView(Ui.divider(this));
    diagnostics.addView(clickable(Ui.listRow(this, "\u2261", "投屏日志", MirrorLog.lastFailure(),
        Ui.chevron(this)), v -> InfoDialogs.log(this)));
    diagnostics.addView(Ui.divider(this));
    diagnostics.addView(clickable(Ui.listRow(this, "\u24D8", "关于", "版本 0.2.0 · scrcpy 4.0 · 仅前台运行",
        Ui.chevron(this)), v -> InfoDialogs.about(this)));
    diagnosticsSlot.addView(diagnostics);

    diagnosticsSlot.addView(sectionLabel("当前运行"));
    LinearLayout running = Ui.card(this);
    String preferred = DemoTarget.preferredSerial(this);
    running.addView(Ui.listRow(this, "\u25D0", "演示首选",
        preferred == null ? "未设置，演示模式会先等 USB" : "无线 " + preferred,
        Ui.pill(this, preferred == null ? "未设置" : "使用中", 0,
            preferred == null ? Ui.ON_VAR : Ui.PRIMARY)));
    running.addView(Ui.divider(this));
    running.addView(Ui.listRow(this, "\u23F1", "最近一次投屏", lastMirrorText(), null));
    diagnosticsSlot.addView(running);
  }

  private String lastMirrorText() {
    List<MirrorLog.Entry> entries = MirrorLog.entries();
    for (MirrorLog.Entry e : entries) {
      if (!e.failure) return e.line();
    }
    return "还没有投屏记录";
  }

  private void readDimensions() {
    if (!ConnectionOwner.isConnected()) {
      dimensionsText = "请先连接设备";
      buildRight();
      return;
    }
    dimensionsRow.setText("读取中…");
    worker.execute(() -> {
      String text;
      try {
        CommandResult result = ConnectionOwner.get(this).shell("wm size", 5000);
        int[] size = DisplaySize.parse(result.output());
        text = size[0] > 0 ? size[0] + " × " + size[1] : "无法读取";
      } catch (Exception e) {
        text = "无法读取";
      }
      final String value = text;
      handler.post(() -> {
        if (isFinishing()) return;
        dimensionsText = value;
        buildRight();
      });
    });
  }

  /* ------------------------------------------------------------- helpers */

  private TextView sectionLabel(String text) {
    TextView t = Ui.label(this, text);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    lp.topMargin = Ui.dp(this, 22);
    lp.bottomMargin = Ui.dp(this, 10);
    lp.leftMargin = Ui.dp(this, 8);
    t.setLayoutParams(lp);
    return t;
  }

  private View nav(String glyph, String title, String sub, Class<?> target) {
    View row = Ui.listRow(this, glyph, title, sub, Ui.chevron(this));
    return target == null ? row : clickable(row, v -> startActivity(new Intent(this, target)));
  }

  private View clickable(View row, View.OnClickListener click) {
    row.setClickable(true);
    row.setFocusable(true);
    row.setOnClickListener(click);
    return row;
  }
}
