package com.example.carmirror.ui;
import android.content.Context;
import android.widget.*;
import com.example.carmirror.adb.*;
import java.util.*;
/** Explicit selection: spinner population never initiates connections. */
public final class DeviceSection extends LinearLayout {
  private final Spinner picker;
  private List<DeviceEntry> entries=Collections.emptyList();
  public DeviceSection(Context c){super(c);setOrientation(VERTICAL);TextView heading=new TextView(c);heading.setText("1 · ADB 设备");heading.setTextSize(22);addView(heading);picker=new Spinner(c);picker.setContentDescription("USB 与无线设备选择");addView(picker);}
  public void refresh(List<DeviceEntry> next,String selected){String keep=selected;if(picker.getSelectedItemPosition()>=0&&picker.getSelectedItemPosition()<entries.size())keep=entries.get(picker.getSelectedItemPosition()).id;entries=next;List<String> labels=new ArrayList<>();int index=0;for(DeviceEntry e:entries){if(e.id.equals(keep))index=labels.size();labels.add((e.usb==null?"无线 · ":"USB · ")+e.label+" — "+e.message);}if(labels.isEmpty())labels.add("未发现 USB ADB 设备；请接 OTG 调试口或添加无线地址");ArrayAdapter<String> a=new ArrayAdapter<>(getContext(),android.R.layout.simple_spinner_dropdown_item,labels);picker.setAdapter(a);picker.setSelection(index);}
  public DeviceEntry selected(){int i=picker.getSelectedItemPosition();return i>=0&&i<entries.size()?entries.get(i):null;}
}
