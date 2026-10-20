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
}
