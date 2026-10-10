package name.krot.adbsshtunnel;
import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;
public class KeyPolicyTest {
    private static final String RFC_KEY = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
    private String key() throws Exception { return key(RFC_KEY); }
    private String key(String hex) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        byte[] publicKey = new byte[hex.length() / 2];
        for (int i = 0; i < publicKey.length; i++) publicKey[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        byte[] type = "ssh-ed25519".getBytes(java.nio.charset.StandardCharsets.US_ASCII); out.writeInt(type.length); out.write(type); out.writeInt(publicKey.length); out.write(publicKey);
        return "ssh-ed25519 " + Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
    @Test public void validKeyAndDeduplication() throws Exception { assertEquals(1, KeyPolicy.parse(key() + " client\n" + key()).size()); }
    @Test public void acceptsRfc8032PublicKeys() throws Exception {
        for (String hex : new String[] {RFC_KEY, "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025"})
            assertEquals(1, KeyPolicy.parse(key(hex)).size());
    }
    @Test public void rejectsNoncanonicalOffCurveAndSmallOrderPoints() throws Exception {
        for (String hex : new String[] {
                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
                "0000000000000000000000000000000000000000000000000000000000000000",
                "0100000000000000000000000000000000000000000000000000000000000000",
                "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
                "0100000000000000000000000000000000000000000000000000000000000080",
                "0200000000000000000000000000000000000000000000000000000000000000"}) {
            try { KeyPolicy.parse(key(hex)); fail(hex); } catch (IOException expected) { }
        }
    }
    @Test public void rejectsOptionsAndMalformedKeys() throws Exception {
        for (String input : new String[] {"command=\"sh\" " + key(), "ssh-ed25519 !!!!", "ssh-rsa AAAA", "", key() + "AA"}) {
            try { KeyPolicy.parse(input); fail(input); } catch (IOException expected) { }
        }
    }
    @Test public void rejectsCommentOnlyAndMixedInvalidKeySets() throws Exception {
        String invalidPoint = key("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
        for (String input : new String[] {" \n# no trusted keys\n", key() + "\nssh-ed25519 !!!!",
                invalidPoint + "\n" + key(), key() + "\n" + invalidPoint, key() + "\ncommand=\"sh\" " + key()}) {
            try { KeyPolicy.parse(input); fail("Accepted an invalid key set"); }
            catch (IOException expected) { }
        }
    }
    @Test public void restrictedToExactLocalPort() throws Exception {
        String policy = KeyPolicy.restricted(KeyPolicy.parse(key()), 37121);
        assertTrue(policy.contains("permitopen=\"127.0.0.1:37121\""));
        assertTrue(policy.contains("permitlisten=\"0\""));
        assertTrue(policy.contains("no-pty,no-agent-forwarding,no-X11-forwarding"));
    }
    private String rsa(byte[] exponent, byte[] modulus) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        byte[] type = "ssh-rsa".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (byte[] field : new byte[][] {type, exponent, modulus}) { out.writeInt(field.length); out.write(field); }
        return "ssh-rsa " + Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
    @Test public void requiresPositiveCanonicalRsaFields() throws Exception {
        byte[] modulus = new byte[257]; modulus[1] = (byte) 0x80; modulus[256] = 1;
        byte[] exponent = {1, 0, 1};
        assertEquals(1, KeyPolicy.parse(rsa(exponent, modulus)).size());
        byte[] negative = modulus.clone(); negative[0] = (byte) 0x80;
        byte[] redundant = new byte[258]; System.arraycopy(modulus, 0, redundant, 1, modulus.length);
        for (String invalid : new String[] {rsa(exponent, negative), rsa(exponent, redundant), rsa(new byte[] {0, 1, 0, 1}, modulus), rsa(new byte[] {(byte) 0x81}, modulus)}) {
            try { KeyPolicy.parse(invalid); fail("Accepted a noncanonical or negative RSA mpint"); }
            catch (IOException expected) { }
        }
    }
    @Test public void rejectsUnsignedLookingNegativeAndZeroRsaFields() throws Exception {
        byte[] modulus = new byte[257]; modulus[1] = (byte) 0x80; modulus[256] = 1;
        byte[] missingSignByte = Arrays.copyOfRange(modulus, 1, modulus.length);
        byte[] exponent = {1, 0, 1};
        for (String invalid : new String[] {rsa(exponent, missingSignByte), rsa(exponent, new byte[] {0}),
                rsa(new byte[] {0}, modulus), rsa(new byte[0], modulus), rsa(exponent, new byte[0])}) {
            try { KeyPolicy.parse(invalid); fail("Accepted a negative, zero or empty RSA field"); }
            catch (IOException expected) { }
        }
    }
}
