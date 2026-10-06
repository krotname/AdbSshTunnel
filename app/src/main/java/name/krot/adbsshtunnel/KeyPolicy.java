package name.krot.adbsshtunnel;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Strict OpenSSH public-key input; user supplied options are never accepted. */
public final class KeyPolicy {
    private KeyPolicy() { }
    public static List<String> parse(String input) throws IOException {
        if (input.length() > 65536) throw new IOException("Key input too large");
        List<String> keys = new ArrayList<>();
        for (String line : input.split("\\R")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] fields = line.split("\\s+");
            if (fields.length < 2 || !(fields[0].equals("ssh-ed25519") || fields[0].equals("ssh-rsa")))
                throw new IOException("Use ssh-ed25519 or ssh-rsa public keys without options");
            byte[] blob;
            try { blob = Base64.getDecoder().decode(fields[1]); } catch (IllegalArgumentException e) { throw new IOException("Invalid Base64 public key", e); }
            try (DataInputStream stream = new DataInputStream(new ByteArrayInputStream(blob))) {
                String type = new String(readField(stream, 32), StandardCharsets.US_ASCII);
                if (!type.equals(fields[0])) throw new IOException("Key type mismatch");
                if (type.equals("ssh-ed25519")) {
                    if (readField(stream, 32).length != 32) throw new IOException("Invalid Ed25519 key");
                } else {
                    byte[] exponent = readField(stream, 16);
                    byte[] modulus = readField(stream, 1025);
                    java.math.BigInteger e = new java.math.BigInteger(exponent);
                    java.math.BigInteger n = new java.math.BigInteger(modulus);
                    if (e.signum() <= 0 || !e.testBit(0) || e.compareTo(java.math.BigInteger.valueOf(3)) < 0 || n.bitLength() < 2048 || !n.testBit(0))
                        throw new IOException("RSA key must be at least 2048 bits");
                }
                if (stream.available() != 0) throw new IOException("Trailing key data");
            }
            String key = fields[0] + " " + Base64.getEncoder().encodeToString(blob);
            if (!keys.contains(key)) keys.add(key);
        }
        if (keys.isEmpty() || keys.size() > 32) throw new IOException("Import between 1 and 32 public keys");
        return keys;
    }
    private static byte[] readField(DataInputStream input, int max) throws IOException {
        int length = input.readInt();
        if (length <= 0 || length > max || length > input.available()) throw new IOException("Invalid key field");
        byte[] bytes = new byte[length]; input.readFully(bytes); return bytes;
    }
    public static String restricted(List<String> keys, int port) {
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("Invalid ADB port");
        StringBuilder result = new StringBuilder();
        for (String key : keys) result.append("no-pty,no-agent-forwarding,no-X11-forwarding,permitopen=\"127.0.0.1:").append(port)
            .append("\",permitlisten=\"0\",command=\"/system/bin/false\" ").append(key).append('\n');
        return result.toString();
    }
    public static String fingerprint(String key) throws Exception {
        byte[] blob = Base64.getDecoder().decode(key.split("\\s+")[1]);
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob));
    }
}
