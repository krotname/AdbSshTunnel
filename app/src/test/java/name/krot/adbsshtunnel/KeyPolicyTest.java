package name.krot.adbsshtunnel;
import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;
public class KeyPolicyTest {
    private String key() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        byte[] type = "ssh-ed25519".getBytes(java.nio.charset.StandardCharsets.US_ASCII); out.writeInt(type.length); out.write(type); out.writeInt(32); out.write(new byte[32]);
        return "ssh-ed25519 " + Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
    @Test public void validKeyAndDeduplication() throws Exception { assertEquals(1, KeyPolicy.parse(key() + " client\n" + key()).size()); }
    @Test public void rejectsOptionsAndMalformedKeys() throws Exception {
        for (String input : new String[] {"command=\"sh\" " + key(), "ssh-ed25519 !!!!", "ssh-rsa AAAA", "", key() + "AA"}) {
            try { KeyPolicy.parse(input); fail(input); } catch (IOException expected) { }
        }
    }
    @Test public void restrictedToExactLocalPort() throws Exception {
        String policy = KeyPolicy.restricted(KeyPolicy.parse(key()), 37121);
        assertTrue(policy.contains("permitopen=\"127.0.0.1:37121\""));
        assertTrue(policy.contains("permitlisten=\"0\""));
        assertTrue(policy.contains("no-pty,no-agent-forwarding,no-X11-forwarding"));
    }
}
