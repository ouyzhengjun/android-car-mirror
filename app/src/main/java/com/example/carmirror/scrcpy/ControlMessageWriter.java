package com.example.carmirror.scrcpy;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/** scrcpy v4.0 control_msg.c wire format; all integers are big-endian.
 * Pass Streams.control().output(). This writer does not own the session stream. */
public final class ControlMessageWriter {
 public static final int KEY_BACK=4, KEY_HOME=3, KEY_RECENT=187;
 private final DataOutputStream output;
 public ControlMessageWriter(OutputStream output){this.output=new DataOutputStream(Objects.requireNonNull(output));}
 public synchronized void touch(int action,long id,int x,int y,int width,int height,float pressure)throws IOException {
  touch(action,id,x,y,width,height,pressure,0,0);
 }
 public synchronized void touch(int action,long id,int x,int y,int width,int height,float pressure,int actionButton,int buttons)throws IOException {
  if(width<=0||width>65535||height<=0||height>65535||x<0||x>=width||y<0||y>=height||(Float.isNaN(pressure)||Float.isInfinite(pressure)))throw new IllegalArgumentException("Invalid touch position or pressure");
  output.writeByte(2); output.writeByte(action); output.writeLong(id);
  output.writeInt(x);output.writeInt(y);output.writeShort(width);output.writeShort(height);
  // Upstream sc_float_to_u16fp multiplies by 2^16, saturating 1 to 0xffff.
  output.writeShort(Math.min(65535,(int)(Math.max(0f,Math.min(1f,pressure))*65536f)));
  output.writeInt(actionButton);output.writeInt(buttons);output.flush();
 }
 public synchronized void keycode(int action,int keyCode)throws IOException {
  if(action!=0&&action!=1)throw new IllegalArgumentException("Invalid key action");
  output.writeByte(0);output.writeByte(action);output.writeInt(keyCode);output.writeInt(0);output.writeInt(0);output.flush();
 }
}
