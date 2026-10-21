package com.example.carmirror.scrcpy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Objects;

/** scrcpy v4.0 control_msg.c wire format; all integers are big-endian.
 * Pass Streams.control().output(). This writer does not own the session stream.
 * Each message is serialized into one buffer: every separate write costs an ADB
 * flow-control round trip, so byte-at-a-time DataOutputStream calls are far too slow. */
public final class ControlMessageWriter {
 public static final int KEY_BACK=4, KEY_HOME=3, KEY_RECENT=187;
 private final OutputStream output;
 public ControlMessageWriter(OutputStream output){this.output=Objects.requireNonNull(output);}
 public synchronized void touch(int action,long id,int x,int y,int width,int height,float pressure)throws IOException {
  touch(action,id,x,y,width,height,pressure,0,0);
 }
 public synchronized void touch(int action,long id,int x,int y,int width,int height,float pressure,int actionButton,int buttons)throws IOException {
  if(width<=0||width>65535||height<=0||height>65535||x<0||x>=width||y<0||y>=height||(Float.isNaN(pressure)||Float.isInfinite(pressure)))throw new IllegalArgumentException("Invalid touch position or pressure");
  ByteBuffer message=ByteBuffer.allocate(32);
  message.put((byte)2).put((byte)action).putLong(id);
  message.putInt(x).putInt(y).putShort((short)width).putShort((short)height);
  // Upstream sc_float_to_u16fp multiplies by 2^16, saturating 1 to 0xffff.
  message.putShort((short)Math.min(65535,(int)(Math.max(0f,Math.min(1f,pressure))*65536f)));
  message.putInt(actionButton).putInt(buttons);
  output.write(message.array(),0,message.position());output.flush();
 }
 public synchronized void keycode(int action,int keyCode)throws IOException {
  if(action!=0&&action!=1)throw new IllegalArgumentException("Invalid key action");
  ByteBuffer message=ByteBuffer.allocate(14);
  message.put((byte)0).put((byte)action).putInt(keyCode).putInt(0).putInt(0);
  output.write(message.array(),0,message.position());output.flush();
 }
}
