package com.example.carmirror;

import org.junit.Test;
import static org.junit.Assert.*;

public class EndpointTest {
    @Test public void parsesDefaultAddress() {
        Endpoint endpoint = Endpoint.parse("192.168.42.147", "5555");
        assertEquals("192.168.42.147", endpoint.host());
        assertEquals(5555, endpoint.port());
        assertEquals("192.168.42.147:5555", endpoint.serial());
    }

    @Test public void rejectsBlankInputs() {
        rejects("", "5555");
        rejects("  ", "5555");
        rejects("192.168.42.147", " ");
        rejects(null, "5555");
        rejects("192.168.42.147", null);
    }

    @Test public void rejectsHostnamesAndMalformedIpv4() {
        rejects("localhost", "5555");
        rejects("256.168.42.147", "5555");
        rejects("192.168.-1.147", "5555");
        rejects("192.168.42", "5555");
        rejects("192.168.42.147.1", "5555");
        rejects("192.168.42.a", "5555");
    }

    @Test public void rejectsInvalidPorts() {
        rejects("192.168.42.147", "0");
        rejects("192.168.42.147", "65536");
        rejects("192.168.42.147", "-1");
        rejects("192.168.42.147", "adb");
        rejects("192.168.42.147", "999999999999999");
    }

    @Test public void acceptsPortBoundaries() {
        assertEquals(1, Endpoint.parse("0.0.0.0", "1").port());
        assertEquals(65535, Endpoint.parse("255.255.255.255", "65535").port());
    }

    private static void rejects(String host, String port) {
        assertThrows(IllegalArgumentException.class, () -> Endpoint.parse(host, port));
    }
}
