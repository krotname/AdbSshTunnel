import name.krot.adbsshtunnel.HostKeyPublic;
import java.io.*;
import java.util.*;

public final class TestHostKeyPublic {
    private static final String PUBLIC_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBzfszI4DvzhNr5akGwFZ5fQxaq+cRj+b1mEXeGVRpoG";

    private static byte[] fixture(String type, int length) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        byte[] keyType = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.writeInt(keyType.length); out.write(keyType); out.writeInt(length);
        byte[] syntheticSeed = new byte[32]; Arrays.fill(syntheticSeed, (byte) 0x5a);
        out.write(syntheticSeed);
        byte[] publicBlob = Base64.getDecoder().decode(PUBLIC_KEY.split(" ")[1]);
        out.write(publicBlob, publicBlob.length - 32, 32);
        return bytes.toByteArray();
    }

    private static void reject(byte[] bytes) throws IOException {
        try { HostKeyPublic.read(new ByteArrayInputStream(bytes)); }
        catch (IOException expected) { return; }
        throw new AssertionError("Malformed Dropbear host key accepted");
    }

    public static void main(String[] args) throws IOException {
        byte[] valid = fixture("ssh-ed25519", 64);
        String actual = HostKeyPublic.read(new ByteArrayInputStream(valid));
        if (!PUBLIC_KEY.equals(actual)) throw new AssertionError("Must show the public half, never the seed");
        reject(fixture("ssh-ed25519", 32));
        reject(fixture("ssh-rsa", 64));
        reject(Arrays.copyOf(valid, 19 + 31));
        reject(Arrays.copyOf(valid, valid.length - 1));
        reject(Arrays.copyOf(valid, valid.length + 1));
        System.out.println("Dropbear private-key public export and malformed-input checks passed");
    }
}
