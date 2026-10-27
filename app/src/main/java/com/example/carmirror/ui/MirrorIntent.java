package com.example.carmirror.ui;
import android.content.*;
import com.example.carmirror.MirrorActivity;
import com.example.carmirror.scrcpy.*;
public final class MirrorIntent {
 private MirrorIntent(){}
 public static Intent create(Context c,MirrorOptions o){Intent i=new Intent(c,MirrorActivity.class);i.putExtra("display",o.displayId).putExtra("max",o.maxSize).putExtra("rate",o.bitRate).putExtra("fps",o.maxFps);if(o.crop!=null)i.putExtra("crop",new int[]{o.crop.width,o.crop.height,o.crop.x,o.crop.y});return i;}
 public static MirrorOptions read(Intent i){if(!i.hasExtra("display"))throw new IllegalArgumentException("缺少投屏参数，请返回主页启动");int[] r=i.getIntArrayExtra("crop");if(r!=null&&r.length!=4)throw new IllegalArgumentException("无效裁切参数");return new MirrorOptions(i.getIntExtra("display",-1),r==null?null:new CropRect(r[0],r[1],r[2],r[3]),i.getIntExtra("max",0),i.getIntExtra("rate",0),i.getIntExtra("fps",0));}
}
