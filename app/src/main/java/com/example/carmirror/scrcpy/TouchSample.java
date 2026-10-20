package com.example.carmirror.scrcpy;

/** One pointer sample, independent of Android. For MOVE emit one sample per pointer;
 * for DOWN/UP or POINTER_DOWN/POINTER_UP emit only the action-index pointer.
 * A single CANCEL sample cancels every tracked pointer. Coordinates are View-local. */
public final class TouchSample {
 public static final int DOWN=0, UP=1, MOVE=2, CANCEL=3, POINTER_DOWN=5, POINTER_UP=6;
 public final int action;
 public final long pointerId;
 public final float x,y,pressure;
 public TouchSample(int action,long pointerId,float x,float y,float pressure){this.action=action;this.pointerId=pointerId;this.x=x;this.y=y;this.pressure=pressure;}
}
