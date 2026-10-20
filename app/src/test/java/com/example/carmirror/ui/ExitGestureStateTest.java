package com.example.carmirror.ui;
import org.junit.Test;
import static org.junit.Assert.*;
public class ExitGestureStateTest {
 @Test public void boundaryAndOneShot(){ExitGestureState s=new ExitGestureState(8);s.onDown(100,2,3);assertFalse(s.shouldExit(3099));assertTrue(s.shouldExit(3100));assertFalse(s.shouldExit(3101));}
 @Test public void releaseCancels(){ExitGestureState s=new ExitGestureState(8);s.onDown(0,0,0);s.onUp(2999);assertFalse(s.shouldExit(4000));}
 @Test public void dragNeverExitsEvenAfterReturning(){ExitGestureState s=new ExitGestureState(8);s.onDown(0,0,0);s.onMove(100,9,0);s.onMove(200,0,0);assertTrue(s.isDragging());assertFalse(s.shouldExit(5000));}
 @Test public void withinSlopStillHolds(){ExitGestureState s=new ExitGestureState(8);s.onDown(0,0,0);s.onMove(100,4,4);assertTrue(s.shouldExit(3000));}
 @Test public void newGestureResets(){ExitGestureState s=new ExitGestureState(8);s.onDown(0,0,0);s.onMove(1,9,0);s.onUp(2);s.onDown(10,0,0);assertTrue(s.shouldExit(3010));}
}
