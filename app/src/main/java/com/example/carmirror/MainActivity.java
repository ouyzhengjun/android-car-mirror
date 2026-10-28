package com.example.carmirror;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.example.carmirror.ui.SavedDevices;
import com.example.carmirror.ui.Ui;

/** Entry screen: exactly two targets, demo mode and settings. */
public final class MainActivity extends Activity {

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    SavedDevices.seed(this);
    LinearLayout root = Ui.row(this);
    root.setBackgroundColor(Ui.SURFACE);
    root.setGravity(Gravity.CENTER);
    int pad = Ui.dp(this, 40), gap = Ui.dp(this, 48);
    root.setPadding(pad, pad, pad, pad);
    root.addView(card("\u25B6", "演示模式", "插上 USB 自动投屏；设置里选中的设备优先。", true, DemoActivity.class), cardParams(gap));
    root.addView(card("\u2699", "设置", "管理 ADB 设备、无线连接与投屏参数。", false, SettingsActivity.class), cardParams(0));
    setContentView(root);
  }

  private LinearLayout.LayoutParams cardParams(int gap) {
    LinearLayout.LayoutParams p =
        new LinearLayout.LayoutParams(Ui.dp(this, 690), Ui.dp(this, 790));
    p.rightMargin = gap;
    return p;
  }

  private View card(String glyph, String title, String detail, boolean primary, final Class<?> next) {
    FrameLayout frame = new FrameLayout(this);
    frame.setBackground(primary
        ? Ui.gradient(0xFF60489F, 0xFF3B2870, 36, this)
        : Ui.outline(Ui.SURFACE_C, Ui.OUTLINE_V, 36, this));
    frame.setClickable(true);
    frame.setFocusable(true);
    frame.setOnClickListener(v -> startActivity(new Intent(this, next)));

    LinearLayout content = Ui.column(this);
    content.setGravity(Gravity.CENTER);
    content.setPadding(Ui.dp(this, 44), Ui.dp(this, 56), Ui.dp(this, 44), Ui.dp(this, 56));

    TextView icon = Ui.text(this, glyph, 88, primary ? 0xFFE6DFFF : Ui.ON);
    icon.setGravity(Gravity.CENTER);
    icon.setBackground(Ui.fill(primary ? 0x29FFFFFF : Ui.SURFACE_CHH, 50, this));
    int iconSize = Ui.dp(this, 196);
    content.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));

    TextView heading = Ui.text(this, title, 60, primary ? 0xFFE6DFFF : Ui.ON);
    heading.setTypeface(heading.getTypeface(), android.graphics.Typeface.BOLD);
    heading.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    hp.topMargin = Ui.dp(this, 48);
    content.addView(heading, hp);

    TextView body = Ui.text(this, detail, 23, primary ? 0xE6E6DFFF : Ui.ON_VAR);
    body.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    bp.topMargin = Ui.dp(this, 16);
    bp.leftMargin = Ui.dp(this, 20);
    bp.rightMargin = Ui.dp(this, 20);
    content.addView(body, bp);

    frame.addView(content, new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

    TextView chevron = Ui.text(this, "\u203A", 40, primary ? 0xB3E6DFFF : 0xB3E6E1E5);
    FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
    cp.gravity = Gravity.BOTTOM | Gravity.END;
    cp.rightMargin = Ui.dp(this, 40);
    cp.bottomMargin = Ui.dp(this, 34);
    frame.addView(chevron, cp);
    return frame;
  }
}
