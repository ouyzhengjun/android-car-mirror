package com.example.carmirror.scrcpy;

import static org.junit.Assert.*;
import org.junit.Test;

public class MirrorSettingsTest {
  MirrorSettings settings(MirrorSettings.Region region, CropRect crop) {
    return new MirrorSettings(region, 0, 2560, 8000000, 30, crop);
  }
  @Test public void defaultsAndPresetsUseOriginalDisplayCoordinates() {
    assertEquals("2560:1600:0:0", MirrorSettings.defaultForDisplay(5120,1600).resolve(5120,1600).crop.toString());
    assertNull(MirrorSettings.defaultForDisplay(1920,1080).resolve(1920,1080).crop);
    assertEquals("2560:1600:2560:0", settings(MirrorSettings.Region.SECONDARY,null).resolve(5120,1600).crop.toString());
    assertNull(settings(MirrorSettings.Region.FULL,null).resolve(5120,1600).crop);
  }
  @Test public void fullScreenWorksWithoutDimensionsButCropsRequireThem() {
    assertNull(settings(MirrorSettings.Region.FULL,null).resolve(0,0).crop);
    rejected(() -> settings(MirrorSettings.Region.MAIN,null).resolve(0,0));
    rejected(() -> settings(MirrorSettings.Region.SECONDARY,null).resolve(5118,1600));
  }
  @Test public void rejectsInvalidCropsIncludingOverflow() {
    rejected(() -> new CropRect(3,2,0,0));
    rejected(() -> new CropRect(2,3,0,0));
    rejected(() -> new CropRect(0,2,0,0));
    rejected(() -> new CropRect(2,2,-1,0));
    rejected(() -> settings(MirrorSettings.Region.CUSTOM,new CropRect(2560,1600,2562,0)).resolve(5120,1600));
    rejected(() -> settings(MirrorSettings.Region.CUSTOM,new CropRect(2,2,Integer.MAX_VALUE,0)).resolve(5120,1600));
    rejected(() -> settings(MirrorSettings.Region.CUSTOM,null).resolve(5120,1600));
  }
  @Test public void validatesNumericSettingsAndAllowsBoundaries() {
    int[][] invalid={{-1,2560,8000000,30},{256,2560,8000000,30},{0,255,8000000,30},{0,272+1,8000000,30},{0,8208,8000000,30},{0,2560,999999,30},{0,2560,50000001,30},{0,2560,8000000,0},{0,2560,8000000,61}};
    for(int[] v:invalid) rejected(() -> new MirrorSettings(MirrorSettings.Region.FULL,v[0],v[1],v[2],v[3],null));
    MirrorOptions low=new MirrorSettings(MirrorSettings.Region.FULL,0,256,1000000,1,null).resolve(0,0);
    MirrorOptions high=new MirrorSettings(MirrorSettings.Region.FULL,255,8192,50000000,60,null).resolve(0,0);
    assertEquals(256,low.maxSize); assertEquals(60,high.maxFps);
  }
  static void rejected(Runnable action) { try { action.run(); fail("invalid settings accepted"); } catch(IllegalArgumentException expected) {} }
}
