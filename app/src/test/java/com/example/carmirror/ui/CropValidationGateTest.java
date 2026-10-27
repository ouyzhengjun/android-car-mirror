package com.example.carmirror.ui;
import org.junit.Test;
import static org.junit.Assert.*;
import com.example.carmirror.scrcpy.CropRect;
public class CropValidationGateTest {
 private final CropRect secondary=new CropRect(2560,1600,2560,0);
 @Test public void everyGeometryChangeBlocksTouchUntilOriginalBoundsValidated(){CropValidationGate g=new CropValidationGate(secondary);assertFalse(g.allowsTouch());long first=g.begin();g.validate(first,5120,1600);assertTrue(g.allowsTouch());long rotation=g.begin();assertFalse(g.allowsTouch());g.validate(rotation,5120,1600);assertTrue(g.allowsTouch());}
 @Test public void originalDisplayShrinkRejectsSecondaryAndKeepsTouchBlocked(){CropValidationGate g=new CropValidationGate(secondary);long token=g.begin();try{g.validate(token,2560,1600);fail("invalid original bounds");}catch(IllegalArgumentException expected){}assertFalse(g.allowsTouch());}
 @Test public void staleValidationCannotUnlockNewGeometry(){CropValidationGate g=new CropValidationGate(secondary);long old=g.begin();long latest=g.begin();assertFalse(g.validate(old,5120,1600));assertFalse(g.allowsTouch());assertTrue(g.validate(latest,5120,1600));}
 @Test public void queuedTouchFromOldGeometryRemainsBlockedAfterRevalidation(){CropValidationGate g=new CropValidationGate(secondary);long old=g.begin();g.validate(old,5120,1600);long next=g.begin();g.validate(next,5120,1600);assertFalse(g.allowsTouch(old));assertTrue(g.allowsTouch(next));}
 @Test public void unavailableBoundsKeepCropBlocked(){CropValidationGate g=new CropValidationGate(secondary);try{g.validate(g.begin(),0,0);fail();}catch(IllegalArgumentException expected){}assertFalse(g.allowsTouch());}
}
