package com.example.carmirror;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import com.example.carmirror.adb.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Connection editor; ADB transport is connected in the next implementation task. */
public class MainActivity extends Activity {
  private static final String DEFAULT_HOST = "192.168.42.147";
  private static final String DEFAULT_PORT = "5555";
  private AdbSession adb;
  private Endpoint selectedEndpoint;
  private final ExecutorService commands = Executors.newSingleThreadExecutor();
  private TextView commandOutput;
  private SharedPreferences preferences;
  private EditText hostInput;
  private EditText portInput;
  private TextView status;
  private Spinner savedDevices;
  private final ArrayList<String> devices = new ArrayList<>();
  private ArrayAdapter<String> adapter;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    adb = ConnectionOwner.get(this);
    preferences = getSharedPreferences("connections", MODE_PRIVATE);
    ScrollView scroll = new ScrollView(this);
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    int padding = (int) (24 * getResources().getDisplayMetrics().density);
    content.setPadding(padding, padding, padding, padding);
    scroll.addView(content);
    setContentView(scroll);

    TextView title = new TextView(this);
    title.setText("车机投屏 · 设备连接");
    title.setTextSize(24);
    content.addView(title);
    TextView hint = new TextView(this);
    hint.setText("请输入已开启无线 ADB 的车机 IPv4 地址和端口。");
    content.addView(hint);
    hostInput = field(content, "车机 IPv4 地址", "host", InputType.TYPE_CLASS_PHONE);
    portInput = field(content, "ADB 端口", "port", InputType.TYPE_CLASS_NUMBER);
    hostInput.setText(preferences.getString("host", DEFAULT_HOST));
    portInput.setText(preferences.getString("port", DEFAULT_PORT));
    savedDevices = new Spinner(this);
    savedDevices.setContentDescription("已保存的连接项");
    content.addView(savedDevices);
    devices.addAll(preferences.getStringSet("devices", Collections.emptySet()));
    Collections.sort(devices);
    adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, devices);
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    savedDevices.setAdapter(adapter);
    button(
        content,
        "使用选中连接项",
        view -> {
          if (savedDevices.getSelectedItem() == null) return;
          String serial = (String) savedDevices.getSelectedItem();
          int separator = serial.lastIndexOf(':');
          hostInput.setText(serial.substring(0, separator));
          portInput.setText(serial.substring(separator + 1));
        });
    button(content, "保存连接项", view -> saveEndpoint(false));
    button(content, "连接", view -> saveEndpoint(true));
    status = new TextView(this);
    status.setText("未连接");
    status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    content.addView(status);
    button(content, "断开", view -> adb.disconnect());
    button(content, "开始投屏", view -> {
      if (!ConnectionOwner.isConnected()) { status.setText("请先连接车机"); return; }
      startActivity(new android.content.Intent(this, MirrorActivity.class));
    });
    EditText command = field(content, "ADB 命令", "command", InputType.TYPE_CLASS_TEXT);
    command.setHint("adb shell wm size");
    button(content, "执行命令", view -> executeCommand(command.getText().toString()));
    commandOutput = new TextView(this);
    commandOutput.setTextIsSelectable(true);
    content.addView(commandOutput);
  }

  private EditText field(LinearLayout parent, String label, String tag, int inputType) {
    TextView caption = new TextView(this);
    caption.setText(label);
    parent.addView(caption);
    EditText field = new EditText(this);
    field.setTag(tag);
    field.setSingleLine(true);
    field.setInputType(inputType);
    field.setContentDescription(label);
    parent.addView(field);
    return field;
  }

  private void button(LinearLayout parent, String label, View.OnClickListener listener) {
    Button button = new Button(this);
    button.setText(label);
    button.setOnClickListener(listener);
    parent.addView(button);
  }

  private void saveEndpoint(boolean connect) {
    final Endpoint endpoint;
    try {
      endpoint = Endpoint.parse(hostInput.getText().toString(), portInput.getText().toString());
    } catch (IllegalArgumentException error) {
      status.setText(error.getMessage());
      return;
    }
    Set<String> saved = new HashSet<>(devices);
    saved.add(endpoint.serial());
    preferences
        .edit()
        .putString("host", endpoint.host())
        .putString("port", String.valueOf(endpoint.port()))
        .putStringSet("devices", saved)
        .apply();
    devices.clear();
    devices.addAll(saved);
    Collections.sort(devices);
    adapter.notifyDataSetChanged();
    savedDevices.setSelection(devices.indexOf(endpoint.serial()));
    if (connect) onEndpointSelected(endpoint);
    else status.setText("已保存：" + endpoint.serial());
  }

  /** Hook for the ADB session integration. Never runs until endpoint validation succeeds. */
  protected void onEndpointSelected(Endpoint endpoint) {
    selectedEndpoint = endpoint;
    ConnectionOwner.connect(endpoint);
  }

  private void executeCommand(String input) {
    String value = input.trim();
    if (value.equals("adb disconnect")) {
      adb.disconnect();
      return;
    }
    if (value.equals("adb connect")) {
      saveEndpoint(true);
      return;
    }
    if (value.startsWith("adb connect ")) {
      try {
        String address = value.substring(12).trim();
        int colon = address.lastIndexOf(':');
        Endpoint target =
            Endpoint.parse(
                colon < 0 ? address : address.substring(0, colon),
                colon < 0 ? "5555" : address.substring(colon + 1));
        hostInput.setText(target.host());
        portInput.setText(String.valueOf(target.port()));
        saveEndpoint(true);
      } catch (IllegalArgumentException error) {
        commandOutput.setText(error.getMessage());
      }
      return;
    }
    if (value.equals("adb devices")) {
      commands.execute(
          () -> {
            try (AdbChannel probe = adb.openService("shell:")) {
              runOnUiThread(
                  () ->
                      commandOutput.setText(
                          "List of devices attached\n" + (selectedEndpoint == null ? preferences.getString("host", DEFAULT_HOST) + ":" + preferences.getString("port", DEFAULT_PORT) : selectedEndpoint.serial()) + "\tdevice"));
            } catch (Exception error) {
              runOnUiThread(() -> commandOutput.setText("List of devices attached\n"));
            }
          });
      return;
    }
    final String shell;
    if (value.startsWith("adb shell ")) shell = value.substring(10);
    else if (value.startsWith("adb ") || value.equals("adb shell") || value.isEmpty()) {
      commandOutput.setText("支持 adb shell <命令>、adb devices、adb connect [IP:端口]、adb disconnect");
      return;
    } else shell = value;
    commandOutput.setText("执行中…");
    commands.execute(
        () -> {
          try {
            CommandResult result = adb.shell(shell, 15000);
            runOnUiThread(
                () ->
                    commandOutput.setText(
                        result.output()
                            + "\n"
                            + (result.timedOut() ? "命令超时" : "退出码：" + result.exitCode())));
          } catch (Exception error) {
            runOnUiThread(() -> commandOutput.setText("命令失败：" + error.getMessage()));
          }
        });
  }

  @Override
  protected void onResume() {
    super.onResume();
    status.setText(ConnectionOwner.message());
    ConnectionOwner.observe((state, message) -> status.setText(message));
  }

  @Override
  protected void onPause() {
    ConnectionOwner.observe(null);
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    commands.shutdownNow();
    super.onDestroy();
  }
}
