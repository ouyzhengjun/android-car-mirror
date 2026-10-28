package com.example.carmirror;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.example.carmirror.adb.ConnectionOwner;
import com.example.carmirror.adb.DeviceEntry;
import com.example.carmirror.scrcpy.CropRect;
import com.example.carmirror.scrcpy.MirrorSettings;
import com.example.carmirror.ui.*;

/** Two-column mirror configuration: crop on the left with a live preview, quality on the right. */
public final class MirrorSettingsActivity extends Activity {
  private static final int CAR_WIDTH = 5120, CAR_HEIGHT = 1600;
  private MirrorSettingsStore store;
  private int region = 0;
  private boolean dual;
  private final View[] regionOptions = new View[4];
  private TextView singleLabel, dualLabel;
  private boolean nativeSize = true;
  private int secondaryDisplayId = -1;
  private boolean cover;
  private boolean coverTouched;
  private TextView coverLabel, fitLabel;
  private TextView nativeLabel, customSizeLabel, detected;
  private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
  private final java.util.concurrent.ExecutorService worker =
      java.util.concurrent.Executors.newSingleThreadExecutor();
  private EditText display, max, rate, fps, width, height, x, y;
  private TextView previewNote;
  private final TextView[] options = new TextView[4];
  private CropPreview preview;

  @Override protected void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    worker.shutdownNow();
    super.onDestroy();
  }

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    // Touching the catalog or connecting needs the process-scoped session to exist.
    ConnectionOwner.get(this);
    store = new MirrorSettingsStore(this);
    SavedDevices.seed(this);
    final DeviceEntry entry = ConnectionOwner.catalog().selected();
    dual = store.hasDual(entry) ? store.dual(entry) : false;
    MirrorSettings settings = store.load(entry);
    nativeSize = settings.nativeSize;
    cover = settings.cover;
    region = settings.region.ordinal();
    if (!dual && region > 0 && region < 3) region = 0;
    setContentView(buildUi(settings));
    renderSizeMode();
    renderFillMode();
    refreshPreview();
    detectDisplay(entry);
  }

  private View buildUi(MirrorSettings settings) {
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
    TextView title = Ui.text(this, "投屏设置", 25, Ui.ON);
    title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
    LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    tp.leftMargin = Ui.dp(this, 16);
    nav.addView(title, tp);
    nav.addView(new View(this), Ui.lp(0, 0, 1));
    DeviceEntry entry = ConnectionOwner.catalog().selected();
    if (entry != null) nav.addView(Ui.pill(this, entry.label, 0, Ui.ON_VAR));
    root.addView(nav);

    LinearLayout columns = Ui.row(this);
    columns.setGravity(Gravity.TOP);
    columns.setPadding(Ui.dp(this, 56), Ui.dp(this, 10), Ui.dp(this, 56), Ui.dp(this, 28));
    root.addView(columns, Ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

    LinearLayout left = Ui.column(this);
    columns.addView(left, Ui.lp(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));

    left.addView(sectionLabel("屏幕类型"));
    LinearLayout layouts = Ui.row(this);
    layouts.addView(layoutOption(true));
    LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0,
        LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    lp2.leftMargin = Ui.dp(this, 14);
    layouts.addView(layoutOption(false), lp2);
    left.addView(layouts);

    left.addView(sectionLabel("投屏区域"));
    LinearLayout segmented = Ui.row(this);
    String[] labels = {"全屏", "仅主屏（左）", "仅副屏（右）", "自定义"};
    for (int i = 0; i < labels.length; i++) {
      final int index = i;
      LinearLayout option = Ui.column(this);
      option.setGravity(Gravity.CENTER);
      option.setPadding(Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14));
      CropPreview mini = new CropPreview(this);
      mini.setMode(index, null);
      option.addView(mini, new LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 64)));
      TextView label = Ui.text(this, labels[i], 15, Ui.ON_VAR);
      label.setGravity(Gravity.CENTER);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      lp.topMargin = Ui.dp(this, 10);
      option.addView(label, lp);
      option.setClickable(true);
      option.setFocusable(true);
      option.setOnClickListener(v -> {
        region = index;
        refreshPreview();
      });
      options[i] = label;
      regionOptions[i] = option;
      LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(0,
          LinearLayout.LayoutParams.WRAP_CONTENT, 1);
      op.rightMargin = i < labels.length - 1 ? Ui.dp(this, 14) : 0;
      segmented.addView(option, op);
      option.setTag(mini);
    }
    left.addView(segmented);

    previewNote = Ui.text(this, "", 14, Ui.ON_VAR);
    LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    np.leftMargin = Ui.dp(this, 4);
    np.topMargin = Ui.dp(this, 14);
    left.addView(previewNote, np);

    left.addView(sectionLabel("画面预览"));
    preview = new CropPreview(this);
    preview.setBackground(Ui.fill(0x00000000, 12, this));
    LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 400));
    left.addView(preview, pp);

    LinearLayout right = Ui.column(this);
    LinearLayout.LayoutParams rp = Ui.lp(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
    rp.leftMargin = Ui.dp(this, 40);
    columns.addView(right, rp);

    right.addView(sectionLabel("分辨率"));
    LinearLayout sizeModes = Ui.row(this);
    sizeModes.addView(sizeMode(true));
    LinearLayout.LayoutParams smp = new LinearLayout.LayoutParams(0,
        LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    smp.leftMargin = Ui.dp(this, 14);
    sizeModes.addView(sizeMode(false), smp);
    right.addView(sizeModes);
    detected = Ui.text(this, "正在读取车机分辨率…", 14, Ui.ON_VAR);
    LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    dp.topMargin = Ui.dp(this, 10);
    right.addView(detected, dp);

    right.addView(sectionLabel("画面填充"));
    LinearLayout fillModes = Ui.row(this);
    fillModes.addView(fillMode(false));
    LinearLayout.LayoutParams fmp = new LinearLayout.LayoutParams(0,
        LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    fmp.leftMargin = Ui.dp(this, 14);
    fillModes.addView(fillMode(true), fmp);
    right.addView(fillModes);

    right.addView(sectionLabel("画质"));
    LinearLayout qualityRow = Ui.row(this);
    display = number(right, qualityRow, "显示器 ID", settings.displayId);
    max = number(right, qualityRow, "最长边（自定义时生效）", settings.maxSize);
    right.addView(qualityRow);
    LinearLayout rateRow = Ui.row(this);
    rate = number(right, rateRow, "视频码率（Mbps）", settings.bitRate / 1000000);
    fps = number(right, rateRow, "帧率", settings.maxFps);
    right.addView(rateRow);

    right.addView(sectionLabel("高级"));
    LinearLayout advanced = Ui.card(this);
    advanced.setPadding(Ui.dp(this, 22), Ui.dp(this, 6), Ui.dp(this, 22), Ui.dp(this, 6));
    CropRect crop = settings.customCrop;
    LinearLayout customSize = Ui.row(this);
    width = number(advanced, customSize, "自定义宽", crop == null ? CAR_WIDTH / 2 : crop.width);
    height = number(advanced, customSize, "自定义高", crop == null ? CAR_HEIGHT : crop.height);
    advanced.addView(customSize);
    LinearLayout customOrigin = Ui.row(this);
    customOrigin.setPadding(0, Ui.dp(this, 14), 0, 0);
    x = number(advanced, customOrigin, "自定义 x", crop == null ? 0 : crop.x);
    y = number(advanced, customOrigin, "自定义 y", crop == null ? 0 : crop.y);
    advanced.addView(customOrigin);
    TextView reset = Ui.textButton(this, "恢复默认参数", v -> {
      region = MirrorSettings.Region.MAIN.ordinal();
      display.setText("0");
      max.setText("2560");
      rate.setText("8");
      fps.setText("30");
      width.setText(String.valueOf(CAR_WIDTH / 2));
      height.setText(String.valueOf(CAR_HEIGHT));
      x.setText("0");
      y.setText("0");
      refreshPreview();
    });
    LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    resetParams.topMargin = Ui.dp(this, 12);
    advanced.addView(reset, resetParams);
    right.addView(advanced);

    TextView error = Ui.text(this, "", 15, Ui.ERROR);
    right.addView(error);
    right.addView(new View(this), Ui.lp(0, 0, 1));
    TextView save = Ui.button(this, "保存到这台设备", true, v -> {
      try {
        if (ConnectionOwner.catalog().selected() == null)
          throw new IllegalArgumentException("请先在设备管理里选择一台设备");
        MirrorSettings next = collect();
        store.dual(ConnectionOwner.catalog().selected(), dual);
        store.save(ConnectionOwner.catalog().selected(), next);
        MirrorLog.info("已保存投屏设置：" + summarize(next));
        error.setText("已保存");
        error.setTextColor(Ui.OK);
      } catch (IllegalArgumentException e) {
        error.setTextColor(Ui.ERROR);
        error.setText(e.getMessage());
      }
    });
    save.setLayoutParams(new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 68)));
    right.addView(save);
    return root;
  }

  /** Two big choices; the split regions only exist on a joined panel. */
  private View layoutOption(final boolean single) {
    LinearLayout option = Ui.column(this);
    option.setGravity(Gravity.CENTER);
    option.setPadding(Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16));
    TextView title = Ui.text(this, single ? "单屏（整屏投放）" : "双联屏（左右各半）", 17, Ui.ON_VAR);
    title.setGravity(Gravity.CENTER);
    option.addView(title);
    TextView hint = Ui.text(this, single ? "一台屏幕，整幅画面" : "左右两块屏，需要选主屏或副屏", 13,
        Ui.ON_VAR);
    hint.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    hp.topMargin = Ui.dp(this, 6);
    option.addView(hint, hp);
    option.setClickable(true);
    option.setFocusable(true);
    if (single) singleLabel = title; else dualLabel = title;
    option.setOnClickListener(v -> {
      dual = !single;
      if (!dual && region > 0 && region < 3) region = 0;
      // 联屏默认铺满（裁切本来就是取景），单屏默认完整，除非用户已经手动选过。
      if (!coverTouched) {
        cover = dual;
        renderFillMode();
      }
      refreshPreview();
    });
    return option;
  }

  private void renderLayoutChoice() {
    if (singleLabel == null) return;
    singleLabel.setTextColor(dual ? Ui.ON_VAR : Ui.PRIMARY);
    dualLabel.setTextColor(dual ? Ui.PRIMARY : Ui.ON_VAR);
    singleLabel.setTypeface(singleLabel.getTypeface(),
        dual ? android.graphics.Typeface.NORMAL : android.graphics.Typeface.BOLD);
    dualLabel.setTypeface(dualLabel.getTypeface(),
        dual ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    View single = (View) singleLabel.getParent();
    View dualView = (View) dualLabel.getParent();
    single.setBackground(dual ? Ui.outline(Ui.SURFACE_C, Ui.OUTLINE_V, 18, this)
        : Ui.outline(Ui.SELECTED, Ui.PRIMARY, 18, this));
    dualView.setBackground(dual ? Ui.outline(Ui.SELECTED, Ui.PRIMARY, 18, this)
        : Ui.outline(Ui.SURFACE_C, Ui.OUTLINE_V, 18, this));
    // Split regions would crop half the picture away on a single-screen unit.
    for (int i = 1; i <= 2; i++) {
      if (regionOptions[i] != null) regionOptions[i].setVisibility(dual ? View.VISIBLE : View.GONE);
    }
  }

  /** 「完整」保证车机画面一点不丢；「铺满」用裁切换满屏。 */
  private View fillMode(final boolean fill) {
    LinearLayout option = Ui.column(this);
    option.setGravity(Gravity.CENTER);
    option.setPadding(Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14));
    TextView title = Ui.text(this, fill ? "铺满（裁切）" : "完整（留黑边）", 16, Ui.ON_VAR);
    title.setGravity(Gravity.CENTER);
    option.addView(title);
    TextView hint = Ui.text(this, fill ? "填满屏幕，切掉溢出的两侧" : "整幅画面都显示，上下留黑边", 13,
        Ui.ON_VAR);
    hint.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    hp.topMargin = Ui.dp(this, 5);
    option.addView(hint, hp);
    option.setClickable(true);
    option.setFocusable(true);
    if (fill) coverLabel = title; else fitLabel = title;
    option.setOnClickListener(v -> {
      cover = fill;
      coverTouched = true;
      renderFillMode();
    });
    return option;
  }

  private void renderFillMode() {
    if (coverLabel == null) return;
    coverLabel.setTextColor(cover ? Ui.PRIMARY : Ui.ON_VAR);
    fitLabel.setTextColor(cover ? Ui.ON_VAR : Ui.PRIMARY);
    coverLabel.setTypeface(coverLabel.getTypeface(),
        cover ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    fitLabel.setTypeface(fitLabel.getTypeface(),
        cover ? android.graphics.Typeface.NORMAL : android.graphics.Typeface.BOLD);
    ((View) coverLabel.getParent()).setBackground(cover
        ? Ui.outline(Ui.SELECTED, Ui.PRIMARY, 18, this)
        : Ui.outline(Ui.SURFACE_C, Ui.OUTLINE_V, 18, this));
    ((View) fitLabel.getParent()).setBackground(cover
        ? Ui.outline(Ui.SURFACE_C, Ui.OUTLINE_V, 18, this)
        : Ui.outline(Ui.SELECTED, Ui.PRIMARY, 18, this));
  }

  /** 「跟随车机」把编码尺寸交给实测分辨率，避免人为缩放。 */
  private View sizeMode(final boolean nativeMode) {
    LinearLayout option = Ui.column(this);
    option.setGravity(Gravity.CENTER);
    option.setPadding(Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14));
    TextView title = Ui.text(this, nativeMode ? "跟随车机分辨率" : "自定义最长边", 16, Ui.ON_VAR);
    title.setGravity(Gravity.CENTER);
    option.addView(title);
    TextView hint = Ui.text(this, nativeMode ? "按实测屏幕尺寸原样投屏" : "手动限制编码的最长边", 13,
        Ui.ON_VAR);
    hint.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    hp.topMargin = Ui.dp(this, 5);
    option.addView(hint, hp);
    option.setClickable(true);
    option.setFocusable(true);
    if (nativeMode) nativeLabel = title; else customSizeLabel = title;
    option.setOnClickListener(v -> {
      nativeSize = nativeMode;
      renderSizeMode();
    });
    return option;
  }

  private void renderSizeMode() {
    if (nativeLabel == null) return;
    nativeLabel.setTextColor(nativeSize ? Ui.PRIMARY : Ui.ON_VAR);
    customSizeLabel.setTextColor(nativeSize ? Ui.ON_VAR : Ui.PRIMARY);
    nativeLabel.setTypeface(nativeLabel.getTypeface(),
        nativeSize ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    customSizeLabel.setTypeface(customSizeLabel.getTypeface(),
        nativeSize ? android.graphics.Typeface.NORMAL : android.graphics.Typeface.BOLD);
    ((View) nativeLabel.getParent()).setBackground(nativeSize
        ? Ui.outline(Ui.SELECTED, Ui.PRIMARY, 18, this)
        : Ui.outline(Ui.SURFACE_C, Ui.OUTLINE_V, 18, this));
    ((View) customSizeLabel.getParent()).setBackground(nativeSize
        ? Ui.outline(Ui.SURFACE_C, Ui.OUTLINE_V, 18, this)
        : Ui.outline(Ui.SELECTED, Ui.PRIMARY, 18, this));
    max.setEnabled(!nativeSize);
    max.setAlpha(nativeSize ? 0.45f : 1f);
  }

  /** Reads the real display size so both the resolution mode and the screen type can follow it. */
  private void detectDisplay(final DeviceEntry entry) {
    if (!ConnectionOwner.isConnected()) {
      detected.setText("未连接，无法读取车机显示信息");
      return;
    }
    worker.execute(() -> {
      String text;
      int[] size = {0, 0};
      boolean separate = false;
      int secondId = -1;
      try {
        com.example.carmirror.adb.AdbSession adb = ConnectionOwner.get(this);
        size = DisplaySize.parse(adb.shell("wm size", 5000).output());
        // Only the panel lines matter; the full dump is several pages on a slow IVI.
        com.example.carmirror.adb.CommandResult dumps =
            adb.shell("dumpsys display | grep DisplayDeviceInfo", 10000);
        java.util.List<DisplayProbe.Info> panels =
            DisplayProbe.physical(DisplayProbe.parse(dumps.output()));
        MirrorLog.info("显示探测：" + panels.size() + " 块物理屏"
            + (dumps.timedOut() ? "（命令超时，结果可能不完整）" : ""));
        if (panels.size() >= 2) {
          separate = true;
          secondId = panels.get(1).id;
          text = "检测到 " + panels.size() + " 块显示屏：" + describe(panels)
              + "；副屏将使用 " + secondId + " 号屏整屏投放";
        } else if (panels.size() == 1) {
          DisplayProbe.Info only = panels.get(0);
          text = "检测到 1 块显示屏：" + only.width + " × " + only.height
              + (DisplayProbe.looksLikeJoinedPanel(only.width, only.height)
                  ? "，比例像联屏（左右各半），请确认"
                  : "，按单屏投放");
        } else if (size[0] > 0) {
          text = "已检测：" + size[0] + " × " + size[1] + "（未能列出显示屏）";
        } else {
          text = "读取失败，请手动选择";
        }
      } catch (Exception e) {
        text = "读取失败，请手动选择";
        MirrorLog.failure("显示探测失败：" + e);
      }
      final String label = text;
      final int[] detectedSize = size;
      final boolean hasSecond = separate;
      final int second = secondId;
      handler.post(() -> {
        if (isFinishing()) return;
        secondaryDisplayId = hasSecond ? second : -1;
        detected.setText(label);
        if (!store.hasDual(entry)) {
          // Two physical panels is proof; a single wide panel is only a hint.
          dual = hasSecond
              || (detectedSize[0] > 0 && DisplayProbe.looksLikeJoinedPanel(detectedSize[0], detectedSize[1]));
          refreshPreview();
        }
      });
    });
  }

  private static String describe(java.util.List<DisplayProbe.Info> panels) {
    StringBuilder text = new StringBuilder();
    for (DisplayProbe.Info panel : panels) {
      if (text.length() > 0) text.append("、");
      text.append(panel.id).append(" 号 ").append(panel.width).append("×").append(panel.height);
    }
    return text.toString();
  }

  private String summarize(MirrorSettings settings) {
    String region;
    switch (settings.region) {
      case FULL: region = "全屏"; break;
      case MAIN: region = "仅主屏"; break;
      case SECONDARY: region = "仅副屏"; break;
      default: region = "自定义"; break;
    }
    return region + " · " + settings.maxSize + " 边 · " + settings.bitRate / 1000000 + " Mbps";
  }

  private MirrorSettings collect() {
    int maxValue = nativeSize ? safeNumber(max, 2560) : number(max, 256, 8192);
    maxValue = Math.max(256, Math.min(8192, (maxValue + 15) / 16 * 16));
    return new MirrorSettings(MirrorSettings.Region.values()[region], number(display, 0, 255),
        maxValue, number(rate, 1, 50) * 1000000, number(fps, 1, 60),
        region == MirrorSettings.Region.CUSTOM.ordinal()
            ? new CropRect(number(width, 2, CAR_WIDTH), number(height, 2, CAR_HEIGHT),
                number(x, 0, CAR_WIDTH), number(y, 0, CAR_HEIGHT))
            : null,
        nativeSize, secondaryDisplayId, cover);
  }

  private int number(EditText field, int min, int max) {
    int value;
    try {
      value = Integer.parseInt(field.getText().toString().trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("请输入有效整数");
    }
    if (value < min || value > max) throw new IllegalArgumentException("数值应在 " + min + "–" + max);
    return value;
  }

  private EditText number(LinearLayout column, LinearLayout row, String label, int value) {
    LinearLayout cell = Ui.column(this);
    cell.addView(Ui.text(this, label, 14, Ui.ON_VAR));
    EditText field = new EditText(this);
    field.setSingleLine(true);
    field.setInputType(InputType.TYPE_CLASS_NUMBER);
    field.setText(String.valueOf(value));
    field.setTextSize(22);
    field.setTextColor(Ui.ON);
    field.setContentDescription(label);
    field.setBackground(Ui.outline(Ui.SURFACE_C, Ui.OUTLINE_V, 16, this));
    field.setPadding(Ui.dp(this, 20), Ui.dp(this, 15), Ui.dp(this, 20), Ui.dp(this, 15));
    cell.addView(field);
    if (row == null) {
      LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      column.addView(cell, cp);
    } else {
      LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0,
          LinearLayout.LayoutParams.WRAP_CONTENT, 1);
      cp.rightMargin = Ui.dp(this, 18);
      row.addView(cell, cp);
    }
    return field;
  }

  private void refreshPreview() {
    renderLayoutChoice();
    for (int i = 0; i < options.length; i++) {
      boolean on = i == region;
      options[i].setTextColor(on ? Ui.PRIMARY : Ui.ON_VAR);
      options[i].setTypeface(options[i].getTypeface(),
          on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
      View option = (View) options[i].getParent();
      option.setBackground(on
          ? Ui.outline(Ui.SELECTED, Ui.PRIMARY, 18, this)
          : Ui.outline(Ui.SURFACE_C, Ui.OUTLINE_V, 18, this));
      CropPreview mini = (CropPreview) option.getTag();
      mini.setMode(i, i == region);
      mini.invalidate();
    }
    CropRect custom = region == MirrorSettings.Region.CUSTOM.ordinal()
        ? new CropRect(safeNumber(width, CAR_WIDTH / 2), safeNumber(height, CAR_HEIGHT),
            safeNumber(x, 0), safeNumber(y, 0))
        : null;
    preview.setMode(region, custom);
    preview.invalidate();
    previewNote.setText(cropSummary());
  }

  private int safeNumber(EditText field, int fallback) {
    if (field == null) return fallback;
    try {
      return Integer.parseInt(field.getText().toString().trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  /** Explains how much of the chosen region will be cropped to fill a 1536 x 1024 dp screen. */
  private String cropSummary() {
    if (!dual) {
      return cover
          ? "铺满：填满屏幕，按比例裁掉两侧（16:9 车机在 3:2 平板上约裁 16% 宽度）"
          : "完整：整幅画面都显示，16:9 车机在 3:2 平板上会上下留黑边（约 8% 高度）";
    }
    float regionWidth, regionHeight;
    switch (MirrorSettings.Region.values()[region]) {
      case MAIN:
      case SECONDARY:
        regionWidth = CAR_WIDTH / 2f;
        regionHeight = CAR_HEIGHT;
        break;
      case CUSTOM:
        regionWidth = Math.max(1, safeNumber(width, CAR_WIDTH / 2));
        regionHeight = Math.max(1, safeNumber(height, CAR_HEIGHT));
        break;
      default:
        regionWidth = CAR_WIDTH;
        regionHeight = CAR_HEIGHT;
        break;
    }
    if (!cover) return "完整：整幅画面都显示，上下留黑边，不裁切";
    float screenRatio = 1536f / 1024f;
    float regionRatio = regionWidth / regionHeight;
    int cropped;
    if (Math.abs(regionRatio - screenRatio) < 0.01f) {
      cropped = 0;
    } else if (regionRatio > screenRatio) {
      cropped = Math.round((1 - screenRatio / regionRatio) * 100);
      return "将裁掉约 " + cropped + "% 宽度";
    } else {
      cropped = Math.round((1 - regionRatio / screenRatio) * 100);
      return "将裁掉约 " + cropped + "% 高度";
    }
    return cropped == 0 ? "比例与屏幕一致，无裁切" : "将裁掉约 " + cropped + "%";
  }

  private TextView sectionLabel(String text) {
    TextView t = Ui.label(this, text);
    t.setPadding(Ui.dp(this, 8), Ui.dp(this, 22), 0, Ui.dp(this, 10));
    return t;
  }

  /** Draws the 5120 x 1600 car canvas and the rectangle that will actually be mirrored. */
  static final class CropPreview extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int mode;
    private CropRect custom;
    private boolean small;

    CropPreview(Context c) {
      super(c);
    }

    void setMode(int mode, CropRect custom) {
      this.mode = mode;
      this.custom = custom;
    }

    void setMode(int mode, boolean small) {
      this.mode = mode;
      this.small = small;
    }

    @Override protected void onDraw(Canvas canvas) {
      int w = getWidth(), h = getHeight();
      if (w == 0 || h == 0) return;
      float scale = Math.min(w / (float) CAR_WIDTH, h / (float) CAR_HEIGHT);
      float cw = CAR_WIDTH * scale, ch = CAR_HEIGHT * scale;
      float left = (w - cw) / 2f, top = (h - ch) / 2f;
      RectF all = new RectF(left, top, left + cw, top + ch);

      paint.setStyle(Paint.Style.FILL);
      paint.setColor(0xFF2A2731);
      canvas.drawRect(all, paint);

      // Region highlight.
      RectF selected = selectedRect(all);
      paint.setColor(0xFF3B3546);
      canvas.drawRect(selected, paint);

      // Half divider for the two-screen car.
      paint.setColor(0x33FFFFFF);
      paint.setStrokeWidth(Math.max(1, h * 0.006f));
      canvas.drawLine(left + cw / 2, top, left + cw / 2, top + ch, paint);

      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(Math.max(2, h * 0.015f));
      paint.setColor(small ? 0xFFD0BCFF : 0xFFD0BCFF);
      canvas.drawRect(selected, paint);
      paint.setColor(0x55FFFFFF);
      paint.setStrokeWidth(1);
      canvas.drawRect(all, paint);
    }

    private RectF selectedRect(RectF all) {
      float w = all.width(), h = all.height();
      switch (mode) {
        case 1: return new RectF(all.left, all.top, all.left + w / 2, all.bottom);
        case 2: return new RectF(all.left + w / 2, all.top, all.right, all.bottom);
        case 3:
          if (custom == null) return new RectF(all);
          float sx = w / CAR_WIDTH, sy = h / CAR_HEIGHT;
          return new RectF(all.left + custom.x * sx, all.top + custom.y * sy,
              all.left + (custom.x + custom.width) * sx, all.top + (custom.y + custom.height) * sy);
        default: return new RectF(all);
      }
    }
  }
}
