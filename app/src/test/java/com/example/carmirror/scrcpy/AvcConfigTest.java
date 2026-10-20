package com.example.carmirror.scrcpy;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
public class AvcConfigTest {
 @Test public void separatesCombinedSpsPpsAndNormalizesStartCodes() throws Exception {
   AvcConfig c=AvcConfig.parse(new byte[]{0,0,0,1,0x67,0x42,0,0,1,0x68,7});
   assertArrayEquals(new byte[]{0,0,0,1,0x67,0x42},c.sps);
   assertArrayEquals(new byte[]{0,0,0,1,0x68,7},c.pps);
 }
 @Test(expected=IOException.class) public void rejectsMissingPps() throws Exception {AvcConfig.parse(new byte[]{0,0,1,0x67,0x42});}
 @Test(expected=IOException.class) public void rejectsNonAnnexB() throws Exception {AvcConfig.parse(new byte[]{0x67,0x42,0x68,7});}

 @Test public void acceptsSeparateParameterSetPacketsInEitherOrder() throws Exception {
   AvcConfig.Accumulator first=new AvcConfig.Accumulator();
   assertTrue(first.accept(new byte[]{0,0,1,0x67,0x42}));
   assertNull(first.complete());
   assertTrue(first.accept(new byte[]{0,0,0,1,0x68,7}));
   assertArrayEquals(new byte[]{0,0,0,1,0x67,0x42},first.complete().sps);
   assertArrayEquals(new byte[]{0,0,0,1,0x68,7},first.complete().pps);

   AvcConfig.Accumulator reverse=new AvcConfig.Accumulator();
   assertTrue(reverse.accept(new byte[]{0,0,0,1,0x68,7}));
   assertNull(reverse.complete());
   assertTrue(reverse.accept(new byte[]{0,0,1,0x67,0x42}));
   assertNotNull(reverse.complete());
 }

 @Test public void duplicatesDoNotRestartAndChangedSetsWaitForFreshPair() throws Exception {
   AvcConfig.Accumulator accumulator=new AvcConfig.Accumulator();
   byte[] sps={0,0,0,1,0x67,0x42};
   byte[] pps={0,0,1,0x68,7};
   assertTrue(accumulator.accept(sps));
   assertTrue(accumulator.accept(pps));
   assertFalse(accumulator.accept(sps));
   assertFalse(accumulator.accept(pps));
   assertNotNull(accumulator.complete());
   assertTrue(accumulator.accept(new byte[]{0,0,1,0x67,0x43}));
   assertNull(accumulator.complete());
   assertTrue(accumulator.accept(pps));
   assertNotNull(accumulator.complete());
 }

 @Test public void resetDiscardsOldSessionConfiguration() throws Exception {
   AvcConfig.Accumulator accumulator=new AvcConfig.Accumulator();
   accumulator.accept(new byte[]{0,0,1,0x67,0x42});
   accumulator.accept(new byte[]{0,0,1,0x68,7});
   assertNotNull(accumulator.complete());
   accumulator.reset();
   assertNull(accumulator.complete());
   accumulator.accept(new byte[]{0,0,1,0x68,8});
   assertNull(accumulator.complete());
 }

 @Test public void rejectsOversizeNalWithoutRetainingIt() throws Exception {
   AvcConfig.Accumulator accumulator=new AvcConfig.Accumulator();
   byte[] huge=new byte[AvcConfig.MAX_CONFIG_BYTES+1];
   huge[2]=1; huge[3]=0x67;
   try {accumulator.accept(huge);fail("oversize config accepted");}
   catch(IOException expected) {assertNull(accumulator.complete());}
   assertTrue(accumulator.accept(new byte[]{0,0,1,0x67,0x42}));
   assertTrue(accumulator.accept(new byte[]{0,0,1,0x68,7}));
   assertNotNull(accumulator.complete());
 }

 @Test public void rejectsCombinedRetainedSetsOverLimit() throws Exception {
   AvcConfig.Accumulator accumulator=new AvcConfig.Accumulator();
   byte[] sps=new byte[AvcConfig.MAX_CONFIG_BYTES/2];
   byte[] pps=new byte[AvcConfig.MAX_CONFIG_BYTES/2];
   sps[2]=1;sps[3]=0x67;
   pps[2]=1;pps[3]=0x68;
   accumulator.accept(sps);
   try {accumulator.accept(pps);fail("combined oversize config accepted");}
   catch(IOException expected) {assertNull(accumulator.complete());}
   accumulator.accept(new byte[]{0,0,1,0x68,7});
   assertNotNull(accumulator.complete());
 }

 @Test public void rejectsMalformedPacketWithoutCorruptingPreviousSet() throws Exception {
   AvcConfig.Accumulator accumulator=new AvcConfig.Accumulator();
   accumulator.accept(new byte[]{0,0,1,0x67,0x42});
   try {accumulator.accept(new byte[]{0x68,7});fail("non Annex B accepted");}
   catch(IOException expected) {assertNull(accumulator.complete());}
   accumulator.accept(new byte[]{0,0,1,0x68,7});
   assertNotNull(accumulator.complete());
 }
}
