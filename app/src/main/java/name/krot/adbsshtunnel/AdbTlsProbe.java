package name.krot.adbsshtunnel;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Checks ADB's CNXN/STLS exchange without pairing, credentials or opening an ADB service. */
public final class AdbTlsProbe {
    private static final int CNXN = 0x4e584e43;
    private static final int STLS = 0x534c5453;
    private AdbTlsProbe() { }
    public static void verify(int port) throws IOException {
        if (port < 1024 || port > 65535) throw new IOException("Invalid Wireless Debugging port");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 750);
            socket.setSoTimeout(1000);
            exchange(socket.getInputStream(), socket.getOutputStream());
        }
    }
    static void exchange(InputStream input, OutputStream output) throws IOException {
        byte[] banner = "host::\0".getBytes(StandardCharsets.US_ASCII);
        int checksum = 0; for (byte b : banner) checksum += b & 0xff;
        byte[] request = ByteBuffer.allocate(24 + banner.length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(CNXN).putInt(0x01000001).putInt(4096).putInt(banner.length)
                .putInt(checksum).putInt(~CNXN).put(banner).array();
        output.write(request); output.flush();
        byte[] header = new byte[24]; new DataInputStream(input).readFully(header);
        ByteBuffer reply = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        if (reply.getInt() != STLS || reply.getInt() != 0x01000000 || reply.getInt() != 0 ||
                reply.getInt() != 0 || reply.getInt() != 0 || reply.getInt() != ~STLS)
            throw new IOException("Endpoint is not ADB Wireless Debugging");
    }
}
