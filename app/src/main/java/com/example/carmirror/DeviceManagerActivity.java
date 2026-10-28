package com.example.carmirror;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.example.carmirror.adb.ConnectionOwner;
import com.example.carmirror.adb.DeviceEntry;
import com.example.carmirror.adb.usb.UsbCandidate;
import com.example.carmirror.adb.usb.UsbDeviceRepository;
import com.example.carmirror.adb.usb.UsbIo;
import com.example.carmirror.Endpoint;
import com.example.carmirror.ui.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** List-detail device manager: USB candidates on the left, the selected device on the right. */
public final class DeviceManagerActivity extends Activity {
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private UsbDeviceRepository usb;
  private LinearLayout list;
  private LinearLayout detail;
  private String selectedId;

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    SavedDevices.seed(this);
    setContentView(buildUi());
    usb = new UsbDeviceRepository(this);
    usb.setListener(candidates -> runOnUiThread(() -> {
      if (isFinishing()) return;
      ConnectionOwner.updateUsb(candidates);
      render();
    }));
    render();
  }

  @Override protected void onResume() {
    super.onResume();
    render();
  }

  @Override protected void onDestroy() {
    if (usb != null) usb.close();
    worker.shutdownNow();
    super.onDestroy();
  }

  private View buildUi() {
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
    TextView title = Ui.text(this, "设备管理", 25, Ui.ON);
    title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
    LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    tp.leftMargin = Ui.dp(this, 16);
    nav.addView(title, tp);
    nav.addView(new View(this), Ui.lp(0, 0, 1));
    TextView refresh = Ui.text(this, "\u21BB", 23, Ui.ON_VAR);
    refresh.setGravity(Gravity.CENTER);
    refresh.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
    refresh.setClickable(true);
    refresh.setOnClickListener(v -> {
      ConnectionOwner.updateUsb(usb.scan());
      render();
    });
    nav.addView(refresh);
    root.addView(nav);

    LinearLayout pane = Ui.row(this);
    pane.setBackground(Ui.outline(Ui.SURFACE_C, Ui.OUTLINE_V, 24, this));
    pane.setClipToOutline(true);
    LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
    pp.leftMargin = Ui.dp(this, 32);
    pp.rightMargin = Ui.dp(this, 32);
    pp.bottomMargin = Ui.dp(this, 28);
    pp.topMargin = Ui.dp(this, 12);
    root.addView(pane, pp);

    LinearLayout left = Ui.column(this);
    left.setBackgroundColor(0xFF1D1B20);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 480),
        LinearLayout.LayoutParams.MATCH_PARENT);
    pane.addView(left, lp);
    list = Ui.column(this);
    left.addView(list, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

    // The add action stays pinned to the bottom of the list column.
    TextView add = Ui.text(this, "＋ 添加无线设备", 16, Ui.ON_VAR);
    add.setGravity(Gravity.CENTER);
    android.graphics.drawable.GradientDrawable dash = Ui.outline(0x00000000, Ui.OUTLINE_V, 16, this);
    dash.setStroke(Ui.dp(this, 1), Ui.OUTLINE_V, Ui.dp(this, 8), Ui.dp(this, 6));
    add.setBackground(dash);
    add.setClickable(true);
    LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 60));
    ap.leftMargin = Ui.dp(this, 18);
    ap.rightMargin = Ui.dp(this, 18);
    ap.bottomMargin = Ui.dp(this, 16);
    add.setLayoutParams(ap);
    add.setOnClickListener(v -> WirelessDialog.show(this, endpoint -> {
      selectedId = DeviceEntry.tcpId(endpoint);
      render();
    }));
    left.addView(add);

    detail = Ui.column(this);
    detail.setBackgroundColor(Ui.SURFACE_C);
    detail.setPadding(Ui.dp(this, 38), Ui.dp(this, 34), Ui.dp(this, 38), Ui.dp(this, 30));
    pane.addView(detail, Ui.lp(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
    return root;
  }

  private void render() {
    renderList();
    renderDetail();
  }

  private void renderList() {
    list.removeAllViews();
    List<DeviceEntry> entries = ConnectionOwner.catalog().entries();
    String active = selectedId != null ? selectedId : ConnectionOwner.catalog().selectedId();

    int usbCount = 0, tcpCount = 0;
    for (DeviceEntry e : entries) {
      if (e.usb != null) usbCount++;
      else tcpCount++;
    }
    list.addView(listHeader("USB 设备（" + usbCount + "）"));
    boolean anyUsb = false;
    for (DeviceEntry e : entries) {
      if (e.usb == null) continue;
      anyUsb = true;
      list.addView(deviceRow(e, e.id.equals(active), e.usb.toString()));
    }
    if (!anyUsb) list.addView(emptyRow("未发现 USB ADB 设备，请接 OTG 调试口"));

    list.addView(listHeader("已保存的无线（" + tcpCount + "）"));
    boolean anyTcp = false;
    for (DeviceEntry e : entries) {
      if (e.usb != null) continue;
      anyTcp = true;
      list.addView(deviceRow(e, e.id.equals(active), e.message));
    }
    if (!anyTcp) list.addView(emptyRow("还没有保存的无线设备，点下方按钮添加"));
  }

  private View listHeader(String text) {
    TextView t = Ui.label(this, text);
    t.setPadding(Ui.dp(this, 26), Ui.dp(this, 16), Ui.dp(this, 26), Ui.dp(this, 8));
    return t;
  }

  private View emptyRow(String text) {
    TextView t = Ui.text(this, text, 14, Ui.ON_VAR);
    t.setPadding(Ui.dp(this, 26), Ui.dp(this, 10), Ui.dp(this, 26), Ui.dp(this, 18));
    return t;
  }

  private View deviceRow(final DeviceEntry entry, boolean active, String detailText) {
    LinearLayout row = Ui.column(this);
    row.setPadding(Ui.dp(this, 26), Ui.dp(this, 19), Ui.dp(this, 26), Ui.dp(this, 19));
    if (active) {
      row.setBackgroundColor(Ui.SELECTED);
      View bar = new View(this);
      bar.setBackgroundColor(Ui.PRIMARY);
      LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(Ui.dp(this, 3),
          LinearLayout.LayoutParams.MATCH_PARENT);
      bp.rightMargin = Ui.dp(this, 22);
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.addView(bar, bp);
      LinearLayout text = Ui.column(this);
      row.addView(text, Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
      text.addView(Ui.text(this, entry.label, 18, Ui.PRIMARY));
      TextView sub = Ui.text(this, detailText, 14, Ui.ON_VAR);
      sub.setMaxLines(1);
      sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
      text.addView(sub);
    } else {
      row.addView(Ui.text(this, entry.label, 18, Ui.ON));
      TextView sub = Ui.text(this, detailText, 14, Ui.ON_VAR);
      sub.setMaxLines(1);
      sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
      row.addView(sub);
    }
    row.setClickable(true);
    row.setFocusable(true);
    row.setOnClickListener(v -> {
      selectedId = entry.id;
      render();
    });
    return row;
  }

  private void renderDetail() {
    detail.removeAllViews();
    String active = selectedId != null ? selectedId : ConnectionOwner.catalog().selectedId();
    DeviceEntry entry = null;
    for (DeviceEntry e : ConnectionOwner.catalog().entries()) if (e.id.equals(active)) entry = e;
    if (entry == null) {
      detail.addView(Ui.text(this, "选择左侧设备查看详情", 18, Ui.ON_VAR));
      return;
    }
    final DeviceEntry target = entry;
    boolean connected = ConnectionOwner.isConnected()
        && entry.id.equals(ConnectionOwner.catalog().selectedId());

    LinearLayout pills = Ui.row(this);
    String preferred = DemoTarget.preferredSerial(this);
    if (entry.tcp != null && entry.tcp.serial().equals(preferred)) {
      pills.addView(Ui.pill(this, "演示首选", 0, Ui.PRIMARY));
      LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      pp.rightMargin = Ui.dp(this, 12);
      pills.getChildAt(0).setLayoutParams(pp);
    }
    pills.addView(Ui.pill(this, connected ? "已连接" : entry.message,
        connected ? Ui.OK : 0, connected ? Ui.ON : Ui.ON_VAR));
    detail.addView(pills);

    TextView name = Ui.text(this, entry.tcp != null ? entry.tcp.serial() : entry.label, 36, Ui.ON);
    name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
    LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    np.topMargin = Ui.dp(this, 16);
    detail.addView(name, np);
    detail.addView(Ui.text(this, entry.tcp != null ? "无线 · 经典 TCP ADB" : "USB · OTG 直连", 16,
        Ui.ON_VAR));

    MirrorSettingsStore store = new MirrorSettingsStore(this);
    com.example.carmirror.scrcpy.MirrorSettings settings = store.load(entry);
    detail.addView(kv("投屏区域", regionName(settings.region)));
    detail.addView(kv("画质", settings.maxSize + " 边 · " + settings.bitRate / 1000000
        + " Mbps · " + settings.maxFps + " fps"));
    detail.addView(kv("ADB 授权", entry.tcp != null ? "首次连接需在车机确认" : "需系统 USB 授权 + 车机 ADB 授权"));
    detail.addView(kv("状态", entry.message));

    LinearLayout actions = Ui.row(this);
    LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    ap.topMargin = Ui.dp(this, 26);
    if (connected) {
      actions.addView(Ui.button(this, "断开", true, v -> {
        ConnectionOwner.disconnect();
        render();
      }));
    } else {
      actions.addView(Ui.button(this, "连接", true, v -> connect(target)));
    }
    TextView settingsButton = Ui.button(this, "投屏设置", false, v -> {
      selectedId = target.id;
      if (target.tcp != null) ConnectionOwner.catalog().saveTcp(target.tcp);
      ConnectionOwner.catalog().select(target.id);
      startActivity(new Intent(this, MirrorSettingsActivity.class));
    });
    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    sp.leftMargin = Ui.dp(this, 14);
    actions.addView(settingsButton, sp);
    if (entry.tcp != null) {
      TextView prefer = Ui.textButton(this, connected ? "已是演示首选" : "设为演示首选",
          v -> {
            DemoTarget.prefer(this, target.tcp);
            render();
          });
      LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      pp.leftMargin = Ui.dp(this, 14);
      actions.addView(prefer, pp);
    }
    detail.addView(actions);

    if (entry.tcp != null) {
      TextView remove = Ui.textButton(this, "删除此设备", v -> {
        SavedDevices.remove(this, target.tcp);
        if (target.tcp.serial().equals(DemoTarget.preferredSerial(this))) DemoTarget.clear(this);
        selectedId = null;
        render();
      });
      LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      rp.topMargin = Ui.dp(this, 12);
      detail.addView(remove, rp);
    }

    TextView note = Ui.text(this, entry.usb != null
        ? "USB 设备每次重新插拔都会重新枚举，系统授权框会再次出现。"
        : "无线设备不受 USB 枚举影响，车机重启后需要重新执行 adb tcpip 5555。", 14, Ui.ON_VAR);
    note.setBackground(Ui.fill(0xFF1A1820, 14, this));
    note.setPadding(Ui.dp(this, 18), Ui.dp(this, 15), Ui.dp(this, 18), Ui.dp(this, 15));
    LinearLayout.LayoutParams np2 = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    np2.topMargin = Ui.dp(this, 24);
    detail.addView(note, np2);
  }

  private String regionName(com.example.carmirror.scrcpy.MirrorSettings.Region region) {
    switch (region) {
      case FULL: return "全屏";
      case MAIN: return "仅主屏（左）";
      case SECONDARY: return "仅副屏（右）";
      default: return "自定义";
    }
  }

  private View kv(String key, String value) {
    LinearLayout row = Ui.row(this);
    row.setPadding(0, Ui.dp(this, 17), 0, Ui.dp(this, 17));
    row.addView(Ui.text(this, key, 17, Ui.ON_VAR), Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    TextView v = Ui.text(this, value, 17, Ui.ON);
    v.setGravity(Gravity.END);
    row.addView(v, Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    LinearLayout wrap = Ui.column(this);
    wrap.addView(row);
    wrap.addView(Ui.divider(this));
    return wrap;
  }

  private void connect(final DeviceEntry entry) {
    ConnectionOwner.catalog().select(entry.id);
    if (entry.tcp != null) {
      ConnectionOwner.connect(entry.tcp);
      DemoTarget.prefer(this, entry.tcp);
      render();
      return;
    }
    final UsbCandidate candidate = entry.usb;
    usb.requestPermission(candidate, (device, granted) -> runOnUiThread(() -> {
      if (isFinishing()) return;
      if (!granted) {
        MirrorLog.failure("系统 USB 授权被拒绝");
        render();
        return;
      }
      openUsb(device);
    }));
  }

  private void openUsb(final UsbCandidate candidate) {
    worker.execute(() -> {
      try {
        final UsbIo io = usb.open(candidate);
        runOnUiThread(() -> {
          if (isFinishing()) {
            worker.execute(() -> {
              try {
                io.close();
              } catch (Exception ignored) {
              }
            });
            return;
          }
          ConnectionOwner.connectUsb(candidate, io);
          handler.postDelayed(this::render, 400);
        });
      } catch (Exception e) {
        MirrorLog.failure("USB 连接失败：" + e.getMessage());
        runOnUiThread(this::render);
      }
    });
  }
}
