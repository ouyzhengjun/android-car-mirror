package com.example.carmirror.ui;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class SettingsDraftTest {
 @Test public void untouchedFallbackMustLoadSavedSerialValues(){SettingsDraft d=new SettingsDraft();d.loaded(Arrays.asList("0","8"));assertFalse(d.preserve("usb:path","usb:path",Arrays.asList("0","8")));}
 @Test public void editedRegionOrNumericDraftSurvivesSerialReveal(){SettingsDraft d=new SettingsDraft();d.loaded(Arrays.asList("0","8"));assertTrue(d.preserve("usb:path","usb:path",Arrays.asList("2","8")));assertTrue(d.preserve("usb:path","usb:path",Arrays.asList("0","12")));}
 @Test public void differentAttachmentLoadsItsOwnSettings(){SettingsDraft d=new SettingsDraft();d.loaded(Arrays.asList("0","8"));assertFalse(d.preserve("usb:a","usb:b",Arrays.asList("2","12")));}
 @Test public void automaticDefaultDoesNotBecomeAnEdit(){SettingsDraft d=new SettingsDraft();d.loaded(Arrays.asList("0","8"));d.defaultRegion("1");assertFalse(d.preserve("usb:a","usb:a",Arrays.asList("1","8")));}
}
