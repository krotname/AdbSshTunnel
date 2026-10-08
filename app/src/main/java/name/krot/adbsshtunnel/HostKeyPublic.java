package name.krot.adbsshtunnel;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class HostKeyPublic {
    private HostKeyPublic() { }

    public static String read(InputStream source) throws IOException {
        DataInputStream stream = new DataInputStream(source);
        if (stream.readInt() != 11) throw new IOException("Unexpected server key format");
        byte[] type = new byte[11]; stream.readFully(type);
        if (!new String(type, StandardCharsets.US_ASCII).equals("ssh-ed25519"))
            throw new IOException("Unexpected server key type");
        if (stream.readInt() != 64) throw new IOException("Invalid Dropbear Ed25519 key length");
        if (stream.skipBytes(32) != 32) throw new EOFException("Truncated server key");
        byte[] publicBytes = new byte[32]; stream.readFully(publicBytes);
        if (stream.read() != -1) throw new IOException("Unexpected trailing server key data");
        ByteArrayOutputStream blob = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(blob);
        out.writeInt(type.length); out.write(type); out.writeInt(publicBytes.length); out.write(publicBytes);
        return "ssh-ed25519 " + Base64.getEncoder().encodeToString(blob.toByteArray());
    }
}
