package com.example.carmirror.ui;
import com.example.carmirror.Endpoint;
import org.junit.Test;
import static org.junit.Assert.*;
public class Ipv4InputTest {
 @Test public void normalizesChineseAndFullwidthDots(){assertEquals("192.168.43.160",Endpoint.parse(Ipv4Input.sanitize("192。168．43｡160"),"5555").host());}
 @Test public void asciiUnchanged(){assertEquals("192.168.1.1",Ipv4Input.sanitize("192.168.1.1"));}
 @Test public void strictValidationStillRejectsBadAddresses(){for(String value:new String[]{"999。1。1。1","192。168。。1","192。168。a。1","192。168。1。1;echo x"})try{Endpoint.parse(Ipv4Input.sanitize(value),"5555");fail(value);}catch(IllegalArgumentException expected){}}
}
