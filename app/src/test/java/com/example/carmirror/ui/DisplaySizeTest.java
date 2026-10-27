package com.example.carmirror.ui;
import org.junit.Test;
import static org.junit.Assert.*;
public class DisplaySizeTest {
 @Test public void usesOverrideWhenPresent(){assertArrayEquals(new int[]{2560,1600},DisplaySize.parse("Physical size: 5120x1600\nOverride size: 2560x1600"));}
 @Test public void t26Physical(){assertArrayEquals(new int[]{5120,1600},DisplaySize.parse("Physical size: 5120x1600"));}
 @Test public void errorsAreUnknown(){assertArrayEquals(new int[]{0,0},DisplaySize.parse("Error: display 9 not found"));}
}
