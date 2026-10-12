package name.krot.adbsshtunnel;

import static org.junit.Assert.*;
import java.net.InetAddress;
import org.junit.Test;

public final class NetworkDiagnosticsTest {
    @Test public void rejectsAddressesThatCannotBePublicUnicastIpv4() throws Exception {
        for (String address : new String[] {
            "0.0.0.0", "0.1.2.3", "127.0.0.1", "10.0.0.1", "172.16.0.1",
            "192.168.0.1", "100.64.0.1", "100.127.255.254", "169.254.1.2",
            "224.0.0.1", "239.255.255.250", "240.0.0.1", "255.255.255.255", "::1",
            "192.0.0.1", "192.0.0.8", "192.0.0.11", "192.0.0.170", "192.0.0.171",
            "192.0.2.1", "192.88.99.2", "198.18.0.1", "198.19.255.255",
            "198.51.100.1", "203.0.113.1"
        }) assertFalse(address, NetworkDiagnostics.isPublicIpv4(InetAddress.getByName(address)));
    }

    @Test public void acceptsPublicUnicastAndDoesNotExpandCarrierNatRange() throws Exception {
        for (String address : new String[] {
            "1.1.1.1", "8.8.8.8", "100.63.255.254", "100.128.0.1", "192.0.0.9", "192.0.0.10",
            "192.0.1.1", "192.0.3.1", "192.31.196.1", "192.52.193.1", "192.175.48.1",
            "198.17.255.255", "198.20.0.1", "198.51.99.1", "198.51.101.1", "203.0.112.1", "203.0.114.1"
        })
            assertTrue(address, NetworkDiagnostics.isPublicIpv4(InetAddress.getByName(address)));
    }
}
