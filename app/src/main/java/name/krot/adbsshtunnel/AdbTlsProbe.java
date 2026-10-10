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
    private static final int AUTH = 0x48545541;
    private AdbTlsProbe() { }
    public static void verify(int port) throws IOException {
        if (port < 1024 || port > 65535) throw new IOException("Invalid Wireless Debugging port");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 750);
            socket.setSoTimeout(1000);
            exchange(socket.getInputStream(), socket.getOutputStream());
        }
    }
    public static void verifyPlain(int port) throws IOException {
        if (port < 1024 || port > 65535) throw new IOException("Invalid root ADB port");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 750);
            socket.setSoTimeout(1000);
            exchangePlain(socket.getInputStream(), socket.getOutputStream());
        }
    }
    private static void sendConnect(OutputStream output) throws IOException {
        byte[] banner = "host::\0".getBytes(StandardCharsets.US_ASCII);
        int checksum = 0; for (byte b : banner) checksum += b & 0xff;
        byte[] request = ByteBuffer.allocate(24 + banner.length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(CNXN).putInt(0x01000001).putInt(4096).putInt(banner.length)
                .putInt(checksum).putInt(~CNXN).put(banner).array();
        output.write(request); output.flush();
    }
    static void exchange(InputStream input, OutputStream output) throws IOException {
        sendConnect(output);
        byte[] header = new byte[24]; new DataInputStream(input).readFully(header);
        ByteBuffer reply = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        if (reply.getInt() != STLS || reply.getInt() != 0x01000000 || reply.getInt() != 0 ||
                reply.getInt() != 0 || reply.getInt() != 0 || reply.getInt() != ~STLS)
            throw new IOException("Endpoint is not ADB Wireless Debugging");
    }
    static void exchangePlain(InputStream input, OutputStream output) throws IOException {
        sendConnect(output);
        DataInputStream stream = new DataInputStream(input);
        byte[] header = new byte[24]; stream.readFully(header);
        ByteBuffer reply = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int command = reply.getInt(), arg0 = reply.getInt(), arg1 = reply.getInt();
        int length = reply.getInt(), checksum = reply.getInt(), magic = reply.getInt();
        boolean auth = command == AUTH && arg0 == 1 && arg1 == 0 && length == 20;
        boolean connected = command == CNXN && (arg0 == 0x01000000 || arg0 == 0x01000001) &&
                arg1 >= 4096 && arg1 <= 1048576 && length > 0 && length <= 4096;
        if (magic != ~command || !(auth || connected)) throw new IOException("Endpoint is not root ADB");
        byte[] payload = new byte[length]; stream.readFully(payload);
        int sum = 0; for (byte b : payload) sum += b & 0xff;
        if (checksum != 0 && checksum != sum) throw new IOException("Invalid ADB checksum");
        if (connected && (!new String(payload, StandardCharsets.US_ASCII).startsWith("device::") || payload[length - 1] != 0))
            throw new IOException("Invalid ADB device banner");
    }
}
