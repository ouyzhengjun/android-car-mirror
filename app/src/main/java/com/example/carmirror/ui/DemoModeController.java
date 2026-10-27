package com.example.carmirror.ui;
import java.util.*;
/** Main-thread policy. Only explicit USB candidates can produce automatic work. */
public final class DemoModeController {
  public enum Action { NONE, WAIT, CHOOSE, CONNECT, MIRROR }
  private boolean enabled, foreground, busy, connected, launched, failed;
  private String target;
  private final Set<String> usb=new LinkedHashSet<>();
  public void enabled(boolean value){enabled=value;if(!value)reset();}
  public boolean enabled(){return enabled;}
  public void foreground(boolean value){foreground=value;}
  public void candidates(List<String> ids){usb.clear();for(String id:ids)if(id.startsWith("usb:"))usb.add(id);if(target!=null&&!usb.contains(target))reset();}
  private void reset(){target=null;busy=false;connected=false;launched=false;failed=false;}
  public void select(String id){if(!usb.contains(id))return;reset();target=id;}
  public String target(){return target;}
  public void connected(String id){if(Objects.equals(target,id)){busy=false;connected=true;}}
  public void failed(){busy=false;connected=false;launched=false;failed=true;}
  public void userExit(){enabled(false);}
  public Action next(){
    if(!enabled||!foreground)return Action.NONE;
    if(usb.isEmpty())return Action.WAIT;
    if(target==null){if(usb.size()>1)return Action.CHOOSE;target=usb.iterator().next();}
    if(failed||busy||launched)return Action.NONE;
    if(connected){launched=true;return Action.MIRROR;}
    busy=true;return Action.CONNECT;
  }
}
