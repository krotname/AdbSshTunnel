package name.krot.adbsshtunnel;

import org.junit.Test;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.Assert.*;

public class AdbTlsProbeTest {
    private byte[] reply(int command, int version, int arg1, int length, int checksum, int magic) {
        return ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command).putInt(version).putInt(arg1).putInt(length).putInt(checksum).putInt(magic).array();
    }
    @Test public void acceptsAdbdStartTlsWithoutOpeningAService() throws Exception {
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        AdbTlsProbe.exchange(new ByteArrayInputStream(reply(0x534c5453, 0x01000000, 0, 0, 0, ~0x534c5453)), request);
        ByteBuffer header = ByteBuffer.wrap(request.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x4e584e43, header.getInt());
        assertEquals(0x01000001, header.getInt());
        assertArrayEquals("host::\0".getBytes(StandardCharsets.US_ASCII), Arrays.copyOfRange(request.toByteArray(), 24, request.size()));
    }
    @Test public void rejectsOtherServicesPlainAdbAndMalformedStartTls() throws Exception {
        for (byte[] response : new byte[][] {
                "HTTP/1.1 200 OK\r\nServer: test\r\n\r\n".getBytes(StandardCharsets.US_ASCII),
                "SSH-2.0-dropbear_test\r\n".getBytes(StandardCharsets.US_ASCII),
                reply(0x48545541, 1, 0, 20, 0, ~0x48545541),
                reply(0x4e584e43, 0x01000001, 4096, 0, 0, ~0x4e584e43),
                reply(0x534c5453, 0, 0, 0, 0, ~0x534c5453),
                reply(0x534c5453, 0x01000000, 1, 0, 0, ~0x534c5453),
                reply(0x534c5453, 0x01000000, 0, 1, 0, ~0x534c5453),
                reply(0x534c5453, 0x01000000, 0, 0, 1, ~0x534c5453),
                reply(0x534c5453, 0x01000000, 0, 0, 0, 0), new byte[23]}) {
            try {
                AdbTlsProbe.exchange(new ByteArrayInputStream(response), new ByteArrayOutputStream());
                fail("Accepted a non-ADB-TLS response");
            } catch (IOException expected) { }
        }
    }
}
