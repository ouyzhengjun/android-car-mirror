package com.example.carmirror.ui;
import org.junit.Test;
import static org.junit.Assert.*;
public class RegionDefaultPolicyTest {
 @Test public void untouchedT26UsesMain(){assertEquals(1,new RegionDefaultPolicy(false).resolve(0,0,5120,1600));}
 @Test public void explicitFullSecondaryAndCustomSurviveFirstRead(){for(int region:new int[]{0,2,3}){RegionDefaultPolicy p=new RegionDefaultPolicy(false);p.userEdited();assertEquals(region,p.resolve(region,0,5120,1600));}}
 @Test public void savedAndOtherDisplaysKeepTheirSelection(){assertEquals(0,new RegionDefaultPolicy(true).resolve(0,0,5120,1600));assertEquals(0,new RegionDefaultPolicy(false).resolve(0,1,5120,1600));}
}
