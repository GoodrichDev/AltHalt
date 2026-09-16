package dev.goodrich.althalt.core;

import java.net.Inet6Address;
import java.net.InetAddress;

public final class IpAddresses {
    private IpAddresses() {}

    public static String normalize(String input) {
        String value = input.strip();
        if (value.isEmpty() || value.contains("%") || value.contains("[") || value.contains("]")) {
            throw new IllegalArgumentException("Expected a plain IP address.");
        }
        // ofLiteral never performs DNS, unlike getByName.
        return InetAddress.ofLiteral(value).getHostAddress();
    }

    public static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (address instanceof Inet6Address) {
            // Global unicast only; exclude documentation addresses too.
            return (bytes[0] & 0xe0) == 0x20
                    && !(bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && bytes[3] == (byte) 0xb8);
        }
        int first = bytes[0] & 255;
        int second = bytes[1] & 255;
        return first != 0 && first < 224
                && !(first == 100 && second >= 64 && second <= 127)
                && !(first == 192 && second == 0)
                && !(first == 198 && (second == 18 || second == 19 || (second == 51 && bytes[2] == 100)))
                && !(first == 203 && second == 0 && bytes[2] == 113);
    }
}
