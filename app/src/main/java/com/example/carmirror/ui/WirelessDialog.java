package com.example.carmirror.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.example.carmirror.Endpoint;

/** The two-field wireless endpoint prompt; validating and saving is the caller's job. */
public final class WirelessDialog {

  public interface OnSaved {
    void saved(Endpoint endpoint);
  }

  private WirelessDialog() {}

  public static void show(final Activity activity, final OnSaved onSaved) {
    final int pad = Ui.dp(activity, 28);
    LinearLayout body = Ui.column(activity);
    body.setPadding(pad, pad, pad, pad);
    body.addView(Ui.text(activity, "输入车机已开启的经典 TCP ADB 地址。", 16, Ui.ON_VAR));

    final EditText host = field(activity, body, "IPv4 地址",
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    final EditText port = field(activity, body, "端口", InputType.TYPE_CLASS_NUMBER);
    port.setText("5555");
    host.setText("192.168.43.160");
    body.addView(Ui.text(activity, "全角句点会自动纠正；连接成功后加入已保存列表。", 14, Ui.ON_VAR));

    final TextView error = Ui.text(activity, "", 15, Ui.ERROR);
    LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    ep.topMargin = Ui.dp(activity, 12);
    body.addView(error, ep);

    AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
        .setTitle("添加无线设备")
        .setView(body)
        .setNegativeButton("取消", null)
        .setPositiveButton("连接", null)
        .create();
    dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        .setOnClickListener(v -> {
          try {
            Endpoint endpoint = Endpoint.parse(
                Ipv4Input.sanitize(host.getText().toString()), port.getText().toString());
            saved(activity, endpoint);
            dialog.dismiss();
            if (onSaved != null) onSaved.saved(endpoint);
          } catch (IllegalArgumentException e) {
            error.setText(e.getMessage() == null ? "地址无效" : e.getMessage());
          }
        }));
    dialog.show();
    if (dialog.getWindow() != null) {
      dialog.getWindow().setBackgroundDrawable(Ui.fill(Ui.SURFACE_CHH, 24, activity));
    }
  }

  private static void saved(Activity activity, Endpoint endpoint) {
    SavedDevices.add(activity, endpoint);
    DemoTarget.prefer(activity, endpoint);
  }

  private static EditText field(Activity activity, LinearLayout parent, String label, int type) {
    TextView caption = Ui.text(activity, label, 14, Ui.ON_VAR);
    LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    cp.topMargin = Ui.dp(activity, 14);
    parent.addView(caption, cp);
    EditText field = new EditText(activity);
    field.setSingleLine(true);
    field.setInputType(type);
    field.setTextSize(20);
    field.setTextColor(Ui.ON);
    field.setBackground(Ui.outline(Ui.SURFACE_C, Ui.OUTLINE_V, 16, activity));
    field.setPadding(Ui.dp(activity, 20), Ui.dp(activity, 14),
        Ui.dp(activity, 20), Ui.dp(activity, 14));
    LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    fp.topMargin = Ui.dp(activity, 8);
    parent.addView(field, fp);
    return field;
  }
}
