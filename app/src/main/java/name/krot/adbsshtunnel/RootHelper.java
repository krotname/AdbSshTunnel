package name.krot.adbsshtunnel;
import java.io.*;
import java.util.concurrent.TimeUnit;

/** Runs only the separately provisioned root-owned helper; never starts SSH as root. */
public final class RootHelper {
    private RootHelper() { }
    public static String configure() throws IOException, InterruptedException {
        return run("enable");
    }
    public static void verifyGuard() throws IOException, InterruptedException { run("check"); }
    private static String run(String operation) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("su", "-c", "/data/adb/adb-ssh-tunnel/helper.sh " + operation).redirectErrorStream(true).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("Root request timed out"); }
        String output;
        try (InputStream input = process.getInputStream(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[1024];
            int count;
            while (buffer.size() < 8192 && (count = input.read(chunk, 0, Math.min(chunk.length, 8192 - buffer.size()))) != -1) buffer.write(chunk, 0, count);
            output = new String(buffer.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).trim();
        }
        if (process.exitValue() != 0) throw new IOException("Root helper denied or not installed: " + output);
        return output;
    }
}
