package com.example.carmirror;

/** A validated classic TCP ADB IPv4 endpoint. */
public final class Endpoint {
    private final String host;
    private final int port;

    private Endpoint(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public static Endpoint parse(String ip, String portText) {
        if (ip == null || portText == null) {
            throw new IllegalArgumentException("请输入 IPv4 地址和端口");
        }
        String[] octets = ip.trim().split("\\.", -1);
        if (octets.length != 4) {
            throw new IllegalArgumentException("请输入有效的 IPv4 地址");
        }
        StringBuilder normalized = new StringBuilder();
        for (String octet : octets) {
            if (!octet.matches("[0-9]{1,3}") || Integer.parseInt(octet) > 255) {
                throw new IllegalArgumentException("IPv4 地址每段必须为 0–255");
            }
            if (normalized.length() > 0) normalized.append('.');
            normalized.append(Integer.parseInt(octet));
        }
        String text = portText.trim();
        if (!text.matches("[0-9]{1,5}")) {
            throw new IllegalArgumentException("端口必须为 1–65535");
        }
        int port = Integer.parseInt(text);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("端口必须为 1–65535");
        }
        return new Endpoint(normalized.toString(), port);
    }

    public String host() { return host; }
    public int port() { return port; }
    public String serial() { return host + ":" + port; }
}
