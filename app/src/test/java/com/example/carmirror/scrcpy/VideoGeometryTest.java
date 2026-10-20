package com.example.carmirror.scrcpy;
import org.junit.Test;
import static org.junit.Assert.*;
public class VideoGeometryTest {
 @Test public void landscapeLetterboxWithOffset(){VideoGeometry g=new VideoGeometry(1920,1080,10,20,1000,1000);assertEquals(10,g.contentLeft,0.01);assertEquals(238.75,g.contentTop,0.01);assertEquals(1000,g.contentWidth(),0.01);assertEquals(562.5,g.contentHeight(),0.01);assertFalse(g.contains(20,30));}
 @Test public void portraitPillarbox(){VideoGeometry g=new VideoGeometry(1080,1920,0,0,1000,1000);assertEquals(218.75,g.contentLeft,0.01);assertEquals(0,g.contentTop,0.01);assertTrue(g.contains(500,500));assertFalse(g.contains(781.25f,500));}
 @Test(expected=IllegalArgumentException.class) public void rejectsZeroSize(){new VideoGeometry(0,1080,0,0,100,100);}
}
