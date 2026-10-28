package com.example.carmirror.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Material 3 dark palette and the small view builders the screens share. */
public final class Ui {
  public static final int SURFACE = 0xFF1C1B1F;
  public static final int SURFACE_C = 0xFF211F26;
  public static final int SURFACE_CH = 0xFF2B2930;
  public static final int SURFACE_CHH = 0xFF36343B;
  public static final int ON = 0xFFE6E1E5;
  public static final int ON_VAR = 0xFFCAC4D0;
  public static final int OUTLINE = 0xFF938F99;
  public static final int OUTLINE_V = 0xFF49454F;
  public static final int PRIMARY = 0xFFD0BCFF;
  public static final int ON_PRIMARY = 0xFF381E72;
  public static final int PRIMARY_C = 0xFF4F378B;
  public static final int SECONDARY_C = 0xFF4A4458;
  public static final int ON_SECONDARY_C = 0xFFE8DEF8;
  public static final int OK = 0xFF9BE8A6;
  public static final int WARN = 0xFFFFD27D;
  public static final int ERROR = 0xFFFFB4AB;
  public static final int ERROR_C = 0xFF8C1D18;
  public static final int SELECTED = 0xFF2B2637;

  private Ui() {}

  public static int dp(Context c, float value) {
    return Math.round(value * c.getResources().getDisplayMetrics().density);
  }

  public static GradientDrawable fill(int color, float radiusDp, Context c) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(c, radiusDp));
    return d;
  }

  public static GradientDrawable outline(int fill, int stroke, float radiusDp, Context c) {
    GradientDrawable d = fill(fill, radiusDp, c);
    d.setStroke(Math.max(1, dp(c, 1.5f)), stroke);
    return d;
  }

  public static GradientDrawable gradient(int from, int to, float radiusDp, Context c) {
    GradientDrawable d = new GradientDrawable(
        GradientDrawable.Orientation.TL_BR, new int[] {from, to});
    d.setCornerRadius(dp(c, radiusDp));
    return d;
  }

  public static TextView text(Context c, String value, float sizeDp, int color) {
    TextView t = new TextView(c);
    t.setText(value);
    t.setTextSize(sizeDp);
    t.setTextColor(color);
    return t;
  }

  public static TextView heading(Context c, String value) {
    return text(c, value, 25, ON);
  }

  public static TextView label(Context c, String value) {
    TextView t = text(c, value, 14, ON_VAR);
    t.setLetterSpacing(0.06f);
    return t;
  }

  /** Pill button; primary fills with the accent, tonal uses the secondary container. */
  public static TextView button(Context c, String label, boolean primary, View.OnClickListener click) {
    TextView b = text(c, label, 19, primary ? ON_PRIMARY : ON_SECONDARY_C);
    b.setGravity(Gravity.CENTER);
    b.setTypeface(b.getTypeface(), android.graphics.Typeface.BOLD);
    b.setBackground(fill(primary ? PRIMARY : SECONDARY_C, 999, c));
    int h = dp(c, 68), w = dp(c, 40);
    b.setPadding(w, 0, w, 0);
    b.setMinHeight(h);
    b.setMinWidth(dp(c, 120));
    b.setGravity(Gravity.CENTER);
    b.setClickable(true);
    b.setFocusable(true);
    if (click != null) b.setOnClickListener(click);
    return b;
  }

  public static TextView textButton(Context c, String label, View.OnClickListener click) {
    TextView b = text(c, label, 17, PRIMARY);
    b.setGravity(Gravity.CENTER);
    int h = dp(c, 56), w = dp(c, 18);
    b.setPadding(w, 0, w, 0);
    b.setMinHeight(h);
    b.setClickable(true);
    b.setFocusable(true);
    if (click != null) b.setOnClickListener(click);
    return b;
  }

  public static TextView pill(Context c, String label, int dotColor, int textColor) {
    TextView t = text(c, dotColor == 0 ? label : "● " + label, 15, textColor);
    t.setBackground(fill(SURFACE_CH, 999, c));
    t.setGravity(Gravity.CENTER_VERTICAL);
    int h = dp(c, 44), w = dp(c, 18);
    t.setPadding(w, 0, w, 0);
    t.setMinHeight(h);
    return t;
  }

  /** Fixed-size rounded panel holding a glyph, used for list and card icons. */
  public static TextView icon(Context c, String glyph, float sizeDp, float radiusDp, int bg, int color) {
    TextView t = text(c, glyph, sizeDp * 0.42f, color);
    t.setGravity(Gravity.CENTER);
    t.setBackground(fill(bg, radiusDp, c));
    int s = dp(c, sizeDp);
    t.setLayoutParams(new LinearLayout.LayoutParams(s, s));
    return t;
  }

  public static LinearLayout column(Context c) {
    LinearLayout v = new LinearLayout(c);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  public static LinearLayout row(Context c) {
    LinearLayout v = new LinearLayout(c);
    v.setOrientation(LinearLayout.HORIZONTAL);
    v.setGravity(Gravity.CENTER_VERTICAL);
    return v;
  }

  public static LinearLayout.LayoutParams lp(int w, int h, float weight) {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
    p.weight = weight;
    return p;
  }

  public static void gap(Context c, View v, int dp) {
    LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) v.getLayoutParams();
    p.topMargin = dp(c, dp);
    v.setLayoutParams(p);
  }

  /** Rounded surface panel that groups rows. */
  public static LinearLayout card(Context c) {
    LinearLayout v = column(c);
    v.setBackground(fill(SURFACE_C, 24, c));
    return v;
  }

  /** One settings/device row: glyph, title, subtitle and an optional trailing view. */
  public static LinearLayout listRow(Context c, String glyph, String title, String sub, View trailing) {
    LinearLayout row = row(c);
    row.setPadding(dp(c, 26), dp(c, 22), dp(c, 26), dp(c, 22));
    row.addView(icon(c, glyph, 56, 18, SURFACE_CHH, ON_VAR));
    LinearLayout text = column(c);
    TextView t = text(c, title, 19, ON);
    t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
    text.addView(t);
    TextView s = text(c, sub, 15, ON_VAR);
    s.setMaxLines(1);
    s.setEllipsize(android.text.TextUtils.TruncateAt.END);
    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    sp.topMargin = dp(c, 3);
    text.addView(s, sp);
    LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    tp.leftMargin = dp(c, 22);
    row.addView(text, tp);
    if (trailing != null) row.addView(trailing);
    return row;
  }

  /** Thin separator matching the dark surface containers. */
  public static View divider(Context c) {
    View v = new View(c);
    v.setBackgroundColor(0xFF322F39);
    v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
    return v;
  }

  public static TextView chevron(Context c) {
    return text(c, "\u203A", 24, OUTLINE);
  }

  public static int alpha(int color, float a) {
    return Color.argb(Math.round(255 * a), Color.red(color), Color.green(color), Color.blue(color));
  }
}
