package com.example.carmirror.ui;
/** Only normalize IME dot punctuation; Endpoint remains the strict IPv4 validator. */
public final class Ipv4Input {
  private Ipv4Input(){}
  public static String sanitize(String value){return value==null?null:value.replace('\u3002','.').replace('\uff0e','.').replace('\uff61','.');}
}
