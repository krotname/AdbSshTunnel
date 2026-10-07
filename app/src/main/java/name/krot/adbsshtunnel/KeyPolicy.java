package name.krot.adbsshtunnel;

import java.io.*;
import java.math.BigInteger;
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
                    validateEd25519(readField(stream, 32));
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
    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger TWO = BigInteger.valueOf(2);
    private static final BigInteger D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger SQRT_MINUS_ONE = TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);
    /** RFC 8032 point decoding, followed by rejection of points of order dividing eight. */
    private static void validateEd25519(byte[] encoded) throws IOException {
        if (encoded.length != 32) throw new IOException("Invalid Ed25519 key length");
        boolean sign = (encoded[31] & 0x80) != 0;
        byte[] bigEndian = new byte[32];
        for (int i = 0; i < 32; i++) bigEndian[31 - i] = encoded[i];
        bigEndian[0] &= 0x7f;
        BigInteger y = new BigInteger(1, bigEndian);
        if (y.compareTo(P) >= 0) throw new IOException("Noncanonical Ed25519 key");
        BigInteger y2 = y.multiply(y).mod(P);
        BigInteger x2 = y2.subtract(BigInteger.ONE).multiply(D.multiply(y2).add(BigInteger.ONE).mod(P).modInverse(P)).mod(P);
        BigInteger x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P);
        if (!x.multiply(x).mod(P).equals(x2)) x = x.multiply(SQRT_MINUS_ONE).mod(P);
        if (!x.multiply(x).mod(P).equals(x2) || (x.signum() == 0 && sign)) throw new IOException("Invalid Ed25519 point");
        if (x.testBit(0) != sign) x = P.subtract(x);
        for (int i = 0; i < 3; i++) {
            x2 = x.multiply(x).mod(P); y2 = y.multiply(y).mod(P);
            BigInteger product = D.multiply(x2).multiply(y2).mod(P);
            BigInteger nextX = TWO.multiply(x).multiply(y).multiply(BigInteger.ONE.add(product).mod(P).modInverse(P)).mod(P);
            y = y2.add(x2).multiply(BigInteger.ONE.subtract(product).mod(P).modInverse(P)).mod(P);
            x = nextX;
        }
        if (x.signum() == 0 && y.equals(BigInteger.ONE)) throw new IOException("Small-order Ed25519 key");
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
