package com.example.carmirror.ui;
import java.util.regex.*;
public final class DisplaySize {
 private DisplaySize(){}
 public static int[] parse(String output){int[] result={0,0};Matcher m=Pattern.compile("(?:Physical|Override) size:\\s*(\\d+)x(\\d+)").matcher(output);while(m.find()){try{result=new int[]{Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2))};}catch(NumberFormatException ignored){return new int[]{0,0};}}return result;}
}
