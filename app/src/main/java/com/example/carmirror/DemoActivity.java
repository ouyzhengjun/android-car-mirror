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
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import com.example.carmirror.adb.*;
import com.example.carmirror.adb.usb.UsbCandidate;
import com.example.carmirror.adb.usb.UsbDeviceRepository;
import com.example.carmirror.adb.usb.UsbIo;
import com.example.carmirror.scrcpy.MirrorOptions;
import com.example.carmirror.scrcpy.MirrorSettings;
import com.example.carmirror.ui.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Unattended start: use the preferred wireless device when it answers, otherwise wait for USB and
 * connect as soon as it appears. Every failure is explained on screen with the next action.
 */
public final class DemoActivity extends Activity {
  private static final int MIRROR_REQUEST = 7;
  private static final int WAITING = 0, CONNECTING = 1, FAILED = 2;
  private static final long RETRY_MS = 3000;

  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private MirrorSettingsStore settingsStore;
  private AdbSession adb;
  private UsbDeviceRepository usbRepository;
  private int phase = WAITING;
  private int step;
  private int usbCount = -1;
  private boolean paused;
  private boolean launching;
  private String targetLabel = "";
  private String failure = "";
  private String waitingHint = "插上 OTG 线就会自动开始投屏，不需要再点任何按钮。";
  private long retryAt;

  private LinearLayout copy;
  private FrameLayout visual;
  private TextView note;
  private android.widget.ScrollView copyScroll;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    adb = ConnectionOwner.get(this);
    settingsStore = new MirrorSettingsStore(this);
    SavedDevices.seed(this);
    setContentView(buildUi());
    usbRepository = new UsbDeviceRepository(this);
    usbRepository.setListener(this::onCandidates);
    render();
  }

  /* ---------------------------------------------------------------- layout */

  private View buildUi() {
    LinearLayout root = Ui.column(this);
    root.setBackgroundColor(Ui.SURFACE);

    LinearLayout nav = Ui.row(this);
    nav.setPadding(Ui.dp(this, 56), Ui.dp(this, 12), Ui.dp(this, 56), 0);
    TextView back = Ui.text(this, "\u2190", 26, Ui.ON_VAR);
    back.setGravity(Gravity.CENTER);
    int touch = Ui.dp(this, 52);
    back.setLayoutParams(new LinearLayout.LayoutParams(touch, touch));
    back.setClickable(true);
    back.setOnClickListener(v -> finish());
    nav.addView(back);
    TextView title = Ui.text(this, "演示模式", 25, Ui.ON);
    title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
    LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    tp.leftMargin = Ui.dp(this, 16);
    nav.addView(title, tp);
    nav.addView(new View(this), Ui.lp(0, 0, 1));
    TextView gear = Ui.text(this, "\u2699", 23, Ui.ON_VAR);
    gear.setGravity(Gravity.CENTER);
    gear.setLayoutParams(new LinearLayout.LayoutParams(touch, touch));
    gear.setClickable(true);
    gear.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
    nav.addView(gear);
    root.addView(nav);

    LinearLayout content = Ui.row(this);
    content.setPadding(Ui.dp(this, 96), 0, Ui.dp(this, 96), 0);
    visual = new FrameLayout(this);
    content.addView(visual, new LinearLayout.LayoutParams(Ui.dp(this, 380), Ui.dp(this, 380)));
    copy = Ui.column(this);
    copy.setPadding(Ui.dp(this, 96), 0, 0, 0);
    copyScroll = new ScrollView(this);
    copyScroll.addView(copy);
    content.addView(copyScroll, Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    root.addView(content, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

    note = Ui.text(this, "", 15, Ui.ON_VAR);
    note.setBackground(Ui.fill(0xFF1A1820, 16, this));
    note.setPadding(Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 18));
    LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    np.leftMargin = Ui.dp(this, 56);
    np.rightMargin = Ui.dp(this, 56);
    np.bottomMargin = Ui.dp(this, 34);
    root.addView(note, np);
    return root;
  }

  private void render() {
    renderVisual();
    copy.removeAllViews();
    if (phase == FAILED) {
      renderFailed();
    } else {
      renderProgress();
    }
    note.setVisibility(phase == CONNECTING ? View.GONE : View.VISIBLE);
  }

  private void renderVisual() {
    visual.removeAllViews();
    if (phase == FAILED) {
      TextView error = Ui.text(this, "!", 104, Ui.ERROR);
      error.setGravity(Gravity.CENTER);
      error.setBackground(Ui.fill(Ui.ERROR_C, 999, this));
      visual.addView(error, new FrameLayout.LayoutParams(
          FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
      return;
    }
    FrameLayout ring = new FrameLayout(this);
    ring.setBackground(Ui.outline(Ui.SURFACE_C, Ui.OUTLINE_V, 999, this));
    if (phase == CONNECTING) {
      ProgressBar spinner = new ProgressBar(this);
      spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.WARN));
      int size = Ui.dp(this, 96);
      FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(size, size);
      sp.gravity = Gravity.CENTER;
      ring.addView(spinner, sp);
    } else {
      TextView glyph = Ui.text(this, "\u2913", 118, Ui.PRIMARY);
      glyph.setGravity(Gravity.CENTER);
      ring.addView(glyph, new FrameLayout.LayoutParams(
          FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }
    int size = Ui.dp(this, 300);
    FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(size, size);
    rp.gravity = Gravity.CENTER;
    visual.addView(ring, rp);
  }

  private void renderProgress() {
    TextView title = Ui.text(this,
        phase == CONNECTING ? "正在连接" : (paused ? "投屏已退出" : "等待 USB 设备接入"),
        46, Ui.ON);
    title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
    copy.addView(title);

    String hint = phase == CONNECTING
        ? targetLabel
        : (paused ? "需要再次投屏时点重试，或重新插拔 OTG 线。" : waitingHint);
    TextView hintView = Ui.text(this, hint, 21, Ui.ON_VAR);
    LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    hp.topMargin = Ui.dp(this, 16);
    copy.addView(hintView, hp);

    if (phase == CONNECTING) {
      addSteps();
      TextView cancel = Ui.button(this, "取消", false, v -> {
        ConnectionOwner.disconnect();
        phase = WAITING;
        paused = true;
        render();
      });
      LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      cp.topMargin = Ui.dp(this, 30);
      copy.addView(cancel, cp);
      return;
    }

    // Waiting: show the preferred wireless target that is being polled, if any.
    String preferred = DemoTarget.preferredSerial(this);
    if (preferred != null && !paused) {
      TextView pill = Ui.pill(this, "首选：无线 " + preferred + " · 暂不可达", Ui.WARN, Ui.ON_VAR);
      LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      pp.topMargin = Ui.dp(this, 30);
      copy.addView(pill, pp);
    }

    LinearLayout buttons = Ui.row(this);
    TextView retry = Ui.button(this, "重试", false, v -> {
      paused = false;
      evaluate(true);
    });
    buttons.addView(retry);
    TextView openSettings = Ui.textButton(this, "打开设置", v ->
        startActivity(new Intent(this, SettingsActivity.class)));
    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    sp.leftMargin = Ui.dp(this, 18);
    buttons.addView(openSettings, sp);
    LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    bp.topMargin = Ui.dp(this, 38);
    copy.addView(buttons, bp);

    note.setText("首次插入会在平板上弹系统授权框，在车机上可能还要确认一次 ADB 授权；之后不再询问。");
  }

  private void renderFailed() {
    TextView title = Ui.text(this, "连接失败", 46, Ui.ON);
    title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
    copy.addView(title);
    TextView hint = Ui.text(this, failure, 21, Ui.ON_VAR);
    LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    hp.topMargin = Ui.dp(this, 16);
    copy.addView(hint, hp);

    LinearLayout fixes = Ui.column(this);
    for (String line : failureFixes()) {
      TextView row = Ui.text(this, "· " + line, 18, Ui.ON_VAR);
      LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      rp.topMargin = Ui.dp(this, 12);
      fixes.addView(row, rp);
    }
    LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    fp.topMargin = Ui.dp(this, 24);
    copy.addView(fixes, fp);

    LinearLayout buttons = Ui.row(this);
    buttons.addView(Ui.button(this, "重试", true, v -> {
      paused = false;
      evaluate(true);
    }));
    TextView openSettings = Ui.button(this, "打开设置", false, v ->
        startActivity(new Intent(this, SettingsActivity.class)));
    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    sp.leftMargin = Ui.dp(this, 18);
    buttons.addView(openSettings, sp);
    LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    bp.topMargin = Ui.dp(this, 30);
    copy.addView(buttons, bp);
    note.setText("插上 USB 线会自动改用有线连接，无需手动切换。");
  }

  private void addSteps() {
    String[] labels = {"已找到设备", "建立 ADB 连接", "读取显示尺寸", "启动投屏服务"};
    for (int i = 0; i < labels.length; i++) {
      LinearLayout row = Ui.row(this);
      boolean done = i < step, active = i == step;
      TextView badge = Ui.text(this, done ? "\u2713" : String.valueOf(i + 1), 15,
          done ? Ui.OK : (active ? Ui.ON_PRIMARY : Ui.ON_VAR));
      badge.setGravity(Gravity.CENTER);
      badge.setBackground(Ui.fill(done ? 0xFF1E4B2A : (active ? Ui.PRIMARY : Ui.SURFACE_CH), 999, this));
      row.addView(badge, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
      TextView label = Ui.text(this, labels[i], 19, active ? Ui.ON : Ui.ON_VAR);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      lp.leftMargin = Ui.dp(this, 18);
      row.addView(label, lp);
      LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      rp.topMargin = Ui.dp(this, 16);
      copy.addView(row, rp);
    }
  }

  /* ------------------------------------------------------------- lifecycle */

  @Override protected void onResume() {
    super.onResume();
    ConnectionOwner.observe((state, text) -> runOnUiThread(() -> onState(state, text)));
    evaluate(false);
    handler.post(tick);
  }

  @Override protected void onPause() {
    ConnectionOwner.observe(null);
    handler.removeCallbacks(tick);
    super.onPause();
  }

  @Override protected void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    if (usbRepository != null) usbRepository.close();
    worker.shutdownNow();
    super.onDestroy();
  }

  @Override protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == MIRROR_REQUEST) {
      launching = false;
      paused = true;
      phase = WAITING;
      step = 0;
      render();
    }
  }

  private final Runnable tick = new Runnable() {
    @Override public void run() {
      if (isFinishing()) return;
      if (phase == CONNECTING && ConnectionOwner.isConnected()) {
        onAdbConnected();
        return;
      }
      if (phase == WAITING && !paused && System.currentTimeMillis() >= retryAt) {
        evaluate(false);
      }
      handler.postDelayed(this, 500);
    }
  };

  /* ---------------------------------------------------------------- flow */

  private void evaluate(boolean force) {
    if (launching || phase == CONNECTING) return;
    phase = WAITING;
    // A live ADB session must not re-enter the mirror right after the user left it: they are
    // waiting for an explicit retry or a fresh USB attach.
    if (paused && !force) {
      render();
      return;
    }
    if (ConnectionOwner.isConnected()) {
      onAdbConnected();
      return;
    }
    com.example.carmirror.Endpoint preferred = DemoTarget.preferred(this);
    if (preferred != null) {
      connectWireless(preferred);
      return;
    }
    List<UsbCandidate> candidates = usbRepository == null
        ? java.util.Collections.<UsbCandidate>emptyList() : usbRepository.scan();
    if (!candidates.isEmpty()) {
      connectUsb(candidates.get(0));
      return;
    }
    waitingHint = "插上 OTG 线就会自动开始投屏，不需要再点任何按钮。";
    retryAt = System.currentTimeMillis() + RETRY_MS;
    phase = WAITING;
    step = 0;
    render();
  }

  private void connectWireless(com.example.carmirror.Endpoint endpoint) {
    DemoTarget.prefer(this, endpoint);
    targetLabel = "无线 " + endpoint.serial();
    phase = CONNECTING;
    step = 1;
    render();
    ConnectionOwner.connect(endpoint);
  }

  private void connectUsb(final UsbCandidate candidate) {
    targetLabel = "USB " + candidate.toString();
    phase = WAITING;
    waitingHint = "请在平板上允许 USB 设备访问：" + candidate;
    render();
    // Already-granted devices call back immediately, so no separate permission probe is needed.
    usbRepository.requestPermission(candidate, (device, granted) -> runOnUiThread(() -> {
      if (isFinishing()) return;
      if (!granted) {
        failure = "系统 USB 授权被拒绝或设备已拔出";
        phase = FAILED;
        render();
        return;
      }
      openUsb(device);
    }));
  }

  private void openUsb(final UsbCandidate candidate) {
    phase = CONNECTING;
    step = 1;
    render();
    worker.execute(() -> {
      try {
        final UsbIo io = usbRepository.open(candidate);
        runOnUiThread(() -> {
          if (isFinishing()) {
            worker.execute(() -> closeQuietly(io));
            return;
          }
          ConnectionOwner.connectUsb(candidate, io);
        });
      } catch (final Exception error) {
        runOnUiThread(() -> {
          if (isFinishing()) return;
          fail("USB 连接失败：" + error.getMessage());
        });
      }
    });
  }

  private static void closeQuietly(UsbIo io) {
    try {
      io.close();
    } catch (Exception ignored) {
    }
  }

  private void onState(ConnectionState state, String text) {
    if (isFinishing()) return;
    if (phase == CONNECTING) {
      if (state == ConnectionState.CONNECTED) {
        onAdbConnected();
      } else if (state == ConnectionState.ERROR) {
        fail(text);
      }
    }
  }

  private void onAdbConnected() {
    if (launching) return;
    launching = true;
    step = 2;
    render();
    final DeviceEntry entry = ConnectionOwner.catalog().selected();
    if (entry == null) {
      fail("已连接，但设备信息丢失，请返回后重试");
      return;
    }
    worker.execute(() -> {
      int[] size = {0, 0};
      try {
        CommandResult result = adb.shell("wm size -d 0", 5000);
        if (!result.timedOut()) size = DisplaySize.parse(result.output());
      } catch (Exception ignored) {
      }
      final int[] display = size;
      runOnUiThread(() -> {
        if (isFinishing()) return;
        step = 3;
        render();
        MirrorSettings settings = settingsStore.load(entry);
        MirrorOptions options;
        try {
          options = settings.resolve(display[0], display[1]);
        } catch (IllegalArgumentException e) {
          // Unknown dimensions only allow the complete display.
          options = new MirrorOptions(settings.displayId, null, settings.maxSize,
              settings.bitRate, settings.maxFps);
          MirrorLog.info("未读到显示尺寸，已按全屏投屏");
        }
        MirrorLog.info("开始投屏 " + targetLabel + " · " + options.maxSize + " 边 · "
            + options.bitRate / 1000000 + " Mbps");
        startActivityForResult(MirrorIntent.create(this, options), MIRROR_REQUEST);
      });
    });
  }

  private void fail(String reason) {
    failure = reason == null || reason.trim().isEmpty() ? "未知错误" : reason;
    MirrorLog.failure("演示模式：" + failure);
    phase = FAILED;
    launching = false;
    step = 0;
    render();
  }

  private String[] failureFixes() {
    if (targetLabel.startsWith("USB")) {
      return new String[] {
        "确认 OTG 线接的是车机的 USB 调试口，而不是普通充电口",
        "在平板上允许系统 USB 设备访问，在车机上确认 ADB 授权",
        "拔下重插后点重试"
      };
    }
    return new String[] {
      "确认车机已开启经典 TCP ADB（端口 5555）",
      "确认平板与车机在同一网络",
      "车机重启过的话，需要重新执行 adb tcpip 5555"
    };
  }

  private void onCandidates(List<UsbCandidate> candidates) {
    runOnUiThread(() -> {
      if (isFinishing()) return;
      boolean appeared = usbCount >= 0 && candidates.size() > usbCount;
      usbCount = candidates.size();
      if (appeared) paused = false;
      if (!paused && phase == WAITING) evaluate(false);
    });
  }
}
