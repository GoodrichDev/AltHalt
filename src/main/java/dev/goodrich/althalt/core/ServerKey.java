package dev.goodrich.althalt.core;

import java.net.IDN;
import java.net.InetAddress;
import java.util.Locale;

public final class ServerKey {
    private ServerKey() {}

    public static String of(String address) {
        String value = address.strip();
        if (value.isEmpty()) throw new IllegalArgumentException("Enter a server address.");
        String host;
        int port = 25565;
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            if (end < 0) throw new IllegalArgumentException("Invalid IPv6 server address.");
            host = value.substring(1, end);
            if (!host.contains(":")) throw new IllegalArgumentException("Brackets require an IPv6 address.");
            if (end + 1 < value.length()) {
                if (value.charAt(end + 1) != ':') throw new IllegalArgumentException("Invalid server port.");
                port = port(value.substring(end + 2));
            }
        } else {
            int colon = value.indexOf(':');
            if (colon >= 0 && colon == value.lastIndexOf(':')) {
                host = value.substring(0, colon);
                port = port(value.substring(colon + 1));
            } else {
                host = value;
            }
        }
        if (host.contains(":")) {
            if (host.contains("%")) throw new IllegalArgumentException("Scoped IPv6 addresses are unsupported.");
            host = "[" + InetAddress.ofLiteral(host).getHostAddress() + "]";
        } else {
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            host = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (host.isEmpty()) throw new IllegalArgumentException("Enter a server address.");
        }
        return host + ":" + port;
    }

    private static int port(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port > 0 && port <= 65535) return port;
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException("Server port must be between 1 and 65535.");
    }
}
