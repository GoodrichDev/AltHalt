package dev.goodrich.althalt.core;

import static org.junit.jupiter.api.Assertions.*;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class AddressTest {
    @Test void defaultPortCaseAndTrailingDotShareLock() {
        assertEquals("example.org:25565", ServerKey.of(" EXAMPLE.org. "));
        assertEquals(ServerKey.of("example.org"), ServerKey.of("example.org:25565"));
        assertNotEquals(ServerKey.of("example.org"), ServerKey.of("example.org:25566"));
    }

    @Test void ipv6ServerAddressesAreCanonical() {
        assertEquals(ServerKey.of("[2606:4700:4700::1111]:25565"), ServerKey.of("2606:4700:4700:0:0:0:0:1111"));
    }

    @Test void invalidServerAddressesAreRejected() {
        for (String value : new String[]{"", "https://example.org", "example.org:", "example.org:0", "example.org:65536", "[broken]", "bad host"}) {
            assertThrows(IllegalArgumentException.class, () -> ServerKey.of(value), value);
        }
    }

    @Test void ipParsingDoesNotAcceptHostnamesOrScopedAddresses() {
        for (String value : new String[]{"example.org", "", "127.0.0.999", "fe80::1%3", "<html>error</html>"}) {
            assertThrows(IllegalArgumentException.class, () -> IpAddresses.normalize(value));
        }
    }

    @Test void mappedIpv4AndIpv4ShareHistoryKey() {
        assertEquals(IpAddresses.normalize("8.8.8.8"), IpAddresses.normalize("::ffff:8.8.8.8"));
    }

    @Test void privateAndReservedIpsAreNotAcceptedFromPublicLookup() {
        for (String value : new String[]{"0.0.0.0", "127.0.0.1", "10.0.0.1", "192.168.0.1", "169.254.1.1", "100.64.0.1",
                "224.0.0.1", "255.255.255.255", "::", "::1", "fe80::1", "fd00::1", "2001:db8::1"}) {
            assertFalse(IpAddresses.isPublic(InetAddress.ofLiteral(value)), value);
        }
        assertTrue(IpAddresses.isPublic(InetAddress.ofLiteral("8.8.8.8")));
        assertTrue(IpAddresses.isPublic(InetAddress.ofLiteral("2606:4700:4700::1111")));
    }
}
