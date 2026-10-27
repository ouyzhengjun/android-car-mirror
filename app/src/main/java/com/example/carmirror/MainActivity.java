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
import android.widget.Switch;
import com.example.carmirror.ui.*;
import com.example.carmirror.scrcpy.*;
import com.example.carmirror.adb.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Connection editor and simple ADB command console. */
public class MainActivity extends Activity {
  private static final String DEFAULT_HOST = "192.168.43.160";
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

  private ForegroundDevices foreground;
  private DeviceSection deviceSection;
  private MirrorSettingsSection settings;
  private Switch demoSwitch;
  private TextView demoStatus;
  private boolean resumed, preparing;
  private int preparation;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    adb = ConnectionOwner.get(this);
    foreground=ForegroundDevices.get(this);
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
    deviceSection=new DeviceSection(this);content.addView(deviceSection);
    button(content,"选择设备并连接 / 重试",v->{DeviceEntry e=deviceSection.selected();if(e==null)return;preparation++;preparing=false;settings.select(e);if(e.usb!=null&&foreground.demo.enabled()){foreground.demo.select(e.id);foreground.pump();}else foreground.connect(e);});
    hostInput = field(content, "车机 IPv4 地址", "host", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    portInput = field(content, "ADB 端口", "port", InputType.TYPE_CLASS_NUMBER);
    hostInput.setText(preferences.getString("host", DEFAULT_HOST));
    portInput.setText(preferences.getString("port", DEFAULT_PORT));
    savedDevices = new Spinner(this);
    savedDevices.setContentDescription("已保存的连接项");
    content.addView(savedDevices);
    devices.addAll(preferences.getStringSet("devices", Collections.emptySet()));
    Collections.sort(devices);
    for(String address:devices){try{int colon=address.lastIndexOf(':');ConnectionOwner.catalog().saveTcp(Endpoint.parse(address.substring(0,colon),address.substring(colon+1)));}catch(RuntimeException ignored){}}
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
    button(content, "断开", view -> {preparation++;preparing=false;foreground.cancel();});
    EditText command = field(content, "ADB 命令", "command", InputType.TYPE_CLASS_TEXT);
    command.setHint("adb shell wm size");
    button(content, "执行命令", view -> executeCommand(command.getText().toString()));
    commandOutput = new TextView(this);
    commandOutput.setTextIsSelectable(true);
    content.addView(commandOutput);
    TextView modeHeading=new TextView(this);modeHeading.setText("2 · 模式选择");modeHeading.setTextSize(22);content.addView(modeHeading);
    demoSwitch=new Switch(this);demoSwitch.setText("USB 演示模式（仅应用前台）");content.addView(demoSwitch);
    demoStatus=new TextView(this);content.addView(demoStatus);
    demoSwitch.setOnCheckedChangeListener((b,on)->{if(on!=foreground.demo.enabled()){foreground.demo.enabled(on);foreground.pump();}});
    settings=new MirrorSettingsSection(this);content.addView(settings);
    button(content,"读取显示尺寸",v->prepareMirror(false));
    button(content,"保存当前设备投屏设置",v->{try{if(ConnectionOwner.catalog().selected()==null)throw new IllegalArgumentException("请先选择并连接设备");settings.save();status.setText("已保存当前设备设置");}catch(IllegalArgumentException e){status.setText(e.getMessage());}});
    button(content,"开始投屏",v->prepareMirror(true));
    refreshDashboard();
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
    ConnectionOwner.catalog().saveTcp(endpoint);
    deviceSection.refresh(ConnectionOwner.catalog().entries(),ConnectionOwner.catalog().selectedId());
    if (connect) onEndpointSelected(endpoint);
    else status.setText("已保存：" + endpoint.serial());
  }

  /** Never runs until endpoint validation succeeds. */
  protected void onEndpointSelected(Endpoint endpoint) {
    selectedEndpoint = endpoint;
    preparation++;preparing=false;
    foreground.demo.enabled(false);
    settings.select(newSelected(endpoint));
    foreground.connect(newSelected(endpoint));
  }

  private void executeCommand(String input) {
    String value = input.trim();
    if (value.equals("adb disconnect")) {
      foreground.cancel();
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
                          "List of devices attached\n" + (ConnectionOwner.catalog().selected()==null?"未知设备":ConnectionOwner.catalog().selected().label) + "\tdevice"));
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


  private DeviceEntry newSelected(Endpoint endpoint){String id=ConnectionOwner.catalog().saveTcp(endpoint);for(DeviceEntry e:ConnectionOwner.catalog().entries())if(e.id.equals(id))return e;throw new IllegalStateException();}
  private void refreshDashboard(){
    deviceSection.refresh(ConnectionOwner.catalog().entries(),ConnectionOwner.catalog().selectedId());
    settings.select(ConnectionOwner.catalog().selected());
    status.setText(ConnectionOwner.message());demoStatus.setText(foreground.message);
    demoSwitch.setChecked(foreground.demo.enabled());
  }
  /** Query the selected physical/logical display on the worker before resolving any crop. */
  private void prepareMirror(boolean launch){
    if(preparing)return;
    if(!ConnectionOwner.isConnected()){status.setText("请先连接车机");foreground.demo.failed();return;}
    final int display;try{display=settings.displayId();}catch(IllegalArgumentException e){status.setText(e.getMessage());foreground.demo.failed();return;}
    final String device=ConnectionOwner.catalog().selectedId();final int ticket=++preparation;
    preparing=true;status.setText("正在读取显示尺寸并校验投屏设置…");
    commands.execute(()->{int[] size={0,0};try{CommandResult r=adb.shell("wm size -d "+display,5000);if(!r.timedOut())size=DisplaySize.parse(r.output());}catch(Exception ignored){}final int[] result=size;
      runOnUiThread(()->{if(ticket!=preparation||isDestroyed())return;preparing=false;if(!resumed||!java.util.Objects.equals(device,ConnectionOwner.catalog().selectedId())||!ConnectionOwner.isConnected()){foreground.demo.failed();return;}
        try{if(settings.displayId()!=display)throw new IllegalArgumentException("显示器 ID 已更改，请重新读取尺寸");settings.dimensions(result[0],result[1]);MirrorOptions options=settings.settings().resolve(result[0],result[1]);settings.save();if(launch)startActivityForResult(MirrorIntent.create(this,options),7);else status.setText(result[0]>0?"显示尺寸已更新":"尺寸读取失败；可选择全屏");}
        catch(IllegalArgumentException error){status.setText("投屏设置错误："+error.getMessage());foreground.demo.failed();}
      });
    });
  }
  @Override protected void onStart(){super.onStart();foreground.start();}
  @Override protected void onResume(){super.onResume();resumed=true;foreground.changed=this::refreshDashboard;foreground.launch=()->prepareMirror(true);refreshDashboard();foreground.home(true);}
  @Override protected void onPause(){resumed=false;foreground.home(false);foreground.changed=null;foreground.launch=null;super.onPause();}
  @Override protected void onStop(){foreground.stop();super.onStop();}
  @Override protected void onActivityResult(int request,int result,android.content.Intent data){super.onActivityResult(request,result,data);if(request==7&&result==RESULT_OK)foreground.demo.userExit();}
  @Override protected void onDestroy(){preparation++;commands.shutdownNow();super.onDestroy();}
}
