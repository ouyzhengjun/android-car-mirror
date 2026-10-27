package com.example.carmirror.ui;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class DemoModeControllerTest {
  private DemoModeController ready(String... ids) { DemoModeController c=new DemoModeController();c.foreground(true);c.candidates(Arrays.asList(ids));c.enabled(true);return c; }
  @Test public void oneUsbConnectsThenMirrorsOnlyOnce(){DemoModeController c=ready("usb:a");assertEquals(DemoModeController.Action.CONNECT,c.next());assertEquals(DemoModeController.Action.NONE,c.next());c.connected("usb:a");assertEquals(DemoModeController.Action.MIRROR,c.next());assertEquals(DemoModeController.Action.NONE,c.next());}
  @Test public void multipleWaitForExplicitChoice(){DemoModeController c=ready("usb:a","usb:b");assertEquals(DemoModeController.Action.CHOOSE,c.next());c.select("usb:b");assertEquals(DemoModeController.Action.CONNECT,c.next());assertEquals("usb:b",c.target());}
  @Test public void denialRequiresRetry(){DemoModeController c=ready("usb:a");c.next();c.failed();assertEquals(DemoModeController.Action.NONE,c.next());c.select("usb:a");assertEquals(DemoModeController.Action.CONNECT,c.next());}
  @Test public void wirelessNeverAutoConnects(){assertEquals(DemoModeController.Action.WAIT,ready("tcp:1.2.3.4:5555").next());}
  @Test public void backgroundCannotLaunchEvenAfterConnection(){DemoModeController c=ready("usb:a");c.next();c.foreground(false);c.connected("usb:a");assertEquals(DemoModeController.Action.NONE,c.next());c.foreground(true);assertEquals(DemoModeController.Action.MIRROR,c.next());}
  @Test public void longHoldDisablesDemo(){DemoModeController c=ready("usb:a");c.next();c.connected("usb:a");c.next();c.userExit();assertFalse(c.enabled());assertEquals(DemoModeController.Action.NONE,c.next());}
  @Test public void detachWaitsThenReinsertConnects(){DemoModeController c=ready("usb:a");c.next();c.connected("usb:a");c.next();c.candidates(Collections.emptyList());assertEquals(DemoModeController.Action.WAIT,c.next());c.candidates(Arrays.asList("usb:b"));assertEquals(DemoModeController.Action.CONNECT,c.next());}
  @Test public void staleConnectedResultDoesNotLaunch(){DemoModeController c=ready("usb:a");c.next();c.candidates(Arrays.asList("usb:b"));c.connected("usb:a");assertEquals(DemoModeController.Action.CONNECT,c.next());}
}
