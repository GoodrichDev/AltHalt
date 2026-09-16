package dev.goodrich.althalt.core;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.net.ssl.HttpsURLConnection;

public final class PublicIpLookup {
    public Set<String> lookup() throws BlockedException {
        try {
            Set<String> before = interfaceAddresses();
            Set<String> addresses = new LinkedHashSet<>();
            addresses.add(fetch("https://api.ipify.org", false));
            // Never interpret a failed IPv6 request as proof that IPv6 is unavailable.
            if (before.stream().map(InetAddress::ofLiteral).anyMatch(address ->
                    address instanceof Inet6Address && IpAddresses.isPublic(address))) {
                addresses.add(fetch("https://api6.ipify.org", true));
            }
            if (!before.equals(interfaceAddresses())) throw new IOException("Network changed during lookup.");
            return Set.copyOf(addresses);
        } catch (IOException | RuntimeException error) {
            throw new BlockedException("Your public IP could not be verified, or your network changed during the check. Login is blocked. Check your connection and try again.", error);
        }
    }

    private static Set<String> interfaceAddresses() throws IOException {
        Set<String> addresses = new LinkedHashSet<>();
        for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!network.isUp() || network.isLoopback()) continue;
            for (InetAddress address : Collections.list(network.getInetAddresses())) {
                addresses.add(address.getHostAddress().split("%", 2)[0]);
            }
        }
        return addresses;
    }

    private static String fetch(String endpoint, boolean ipv6) throws IOException {
        // A fresh direct socket avoids an HTTP proxy or a pooled connection surviving a VPN switch.
        HttpsURLConnection connection = (HttpsURLConnection) URI.create(endpoint).toURL().openConnection(Proxy.NO_PROXY);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestProperty("Connection", "close");
        connection.setRequestProperty("User-Agent", "AltHalt/1.0.0");
        try {
            if (connection.getResponseCode() != 200) throw new IOException("IP service unavailable.");
            String value;
            try (var input = connection.getInputStream()) {
                byte[] bytes = input.readNBytes(65);
                if (bytes.length > 64) throw new IOException("Oversized IP response.");
                value = IpAddresses.normalize(new String(bytes, StandardCharsets.UTF_8));
            }
            InetAddress address = InetAddress.ofLiteral(value);
            if ((address instanceof Inet6Address) != ipv6 || !IpAddresses.isPublic(address)) {
                throw new IOException("Unexpected public address family or scope.");
            }
            return value;
        } finally {
            connection.disconnect();
        }
    }
}
