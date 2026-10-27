package com.example.carmirror.ui;
import java.util.*;
/** Compares visible values with their loaded/saved baseline, not focus or popup events. */
public final class SettingsDraft {
  private List<String> baseline=Collections.emptyList();
  public void loaded(List<String> values){baseline=new ArrayList<>(values);}
  public void defaultRegion(String region){if(!baseline.isEmpty())baseline.set(0,region);}
  public boolean preserve(String oldAttachment,String nextAttachment,List<String> visible){
    return nextAttachment!=null&&nextAttachment.equals(oldAttachment)&&!baseline.equals(visible);
  }
}
