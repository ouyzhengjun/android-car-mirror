package com.example.carmirror.ui;
import android.content.*;
import android.text.InputType;
import android.widget.*;
import com.example.carmirror.adb.DeviceEntry;
import com.example.carmirror.scrcpy.*;
/** Per-device preferences; crop is resolved against freshly queried dimensions at launch. */
public final class MirrorSettingsSection extends LinearLayout {
 private final SharedPreferences prefs;
 private final Spinner region;
 private final EditText display,max,rate,fps,w,h,x,y;
 private final TextView dimensions;
 private String key="none";
 private RegionDefaultPolicy defaults=new RegionDefaultPolicy(false);
 private String attachment;
 public MirrorSettingsSection(Context c){super(c);setOrientation(VERTICAL);prefs=c.getSharedPreferences("mirror-settings",0);TextView heading=new TextView(c);heading.setText("3 · 投屏设置");heading.setTextSize(22);addView(heading);dimensions=new TextView(c);addView(dimensions);region=new Spinner(c){
   @Override public boolean performClick(){defaults.userEdited();return super.performClick();}
   @Override public boolean onKeyDown(int code,android.view.KeyEvent event){defaults.userEdited();return super.onKeyDown(code,event);}
 };region.setContentDescription("投屏区域");region.setAdapter(new ArrayAdapter<>(c,android.R.layout.simple_spinner_dropdown_item,new String[]{"全屏","仅主屏（左）","仅副屏（右）","自定义"}));addView(region);display=field("显示器 ID（0–255；T26FL 主副屏均为 0）","0");max=field("最长边（256–8192，16 的倍数）","2560");rate=field("视频码率（Mbps，1–50）","8");fps=field("帧率（1–60）","30");w=field("自定义宽（偶数）","2560");h=field("自定义高（偶数）","1600");x=field("自定义 x","0");y=field("自定义 y","0");}
 private EditText field(String label,String value){TextView t=new TextView(getContext());t.setText(label);addView(t);EditText e=new EditText(getContext());e.setSingleLine(true);e.setInputType(InputType.TYPE_CLASS_NUMBER);e.setContentDescription(label);e.setText(value);addView(e);return e;}
 public static String key(DeviceEntry e){if(e==null)return "none";return e.tcp!=null?e.id:"usb:"+e.usb.vendorId+":"+e.usb.productId+":"+(e.usb.serialNumber==null?"type":e.usb.serialNumber);}
 public void select(DeviceEntry e){String next=key(e);if(next.equals(key))return;
 // Permission can reveal a serial for the same attachment. Keep the visible draft;
 // the next explicit save uses the more stable key instead of reloading preferences.
 String nextAttachment=e==null?null:e.id;
 if(nextAttachment!=null&&nextAttachment.equals(attachment)){key=next;return;}
 attachment=nextAttachment;key=next;defaults=new RegionDefaultPolicy(prefs.contains(key+"region"));region.setSelection(prefs.getInt(key+"region",0));EditText[] fields={display,max,rate,fps,w,h,x,y};String[] names={"display","max","rate","fps","w","h","x","y"},defaults={"0","2560","8","30","2560","1600","0","0"};for(int i=0;i<fields.length;i++)fields[i].setText(prefs.getString(key+names[i],defaults[i]));dimensions.setText("尚未读取显示尺寸；裁切前会重新读取");}
 private int number(EditText e){try{return Integer.parseInt(e.getText().toString().trim());}catch(NumberFormatException ex){throw new IllegalArgumentException("请输入有效整数");}}
 public int displayId(){int n=number(display);if(n<0||n>255)throw new IllegalArgumentException("显示器 ID 应为 0–255");return n;}
 public void dimensions(int width,int height){dimensions.setText(width>0?"当前显示尺寸："+width+" × "+height:"无法读取显示尺寸；仅全屏可用");region.setSelection(defaults.resolve(region.getSelectedItemPosition(),displayId(),width,height));}
 public MirrorSettings settings(){CropRect crop=region.getSelectedItemPosition()==3?new CropRect(number(w),number(h),number(x),number(y)):null;int mbps=number(rate);if(mbps<1||mbps>50)throw new IllegalArgumentException("码率应为 1–50 Mbps");return new MirrorSettings(MirrorSettings.Region.values()[region.getSelectedItemPosition()],displayId(),number(max),mbps*1000000,number(fps),crop);}
 public void save(){settings();SharedPreferences.Editor edit=prefs.edit().putInt(key+"region",region.getSelectedItemPosition());EditText[] fields={display,max,rate,fps,w,h,x,y};String[] names={"display","max","rate","fps","w","h","x","y"};for(int i=0;i<fields.length;i++)edit.putString(key+names[i],fields[i].getText().toString());edit.apply();defaults.userEdited();}
}
