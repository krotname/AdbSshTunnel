package name.krot.adbsshtunnel;
import java.io.*;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import com.hardbacknutter.sshd.SshdService;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Installs the APK's controller; fixed root operations never start SSH as root. */
public final class RootHelper {
    private static final String CONTROLLER = "/data/adb/adb-ssh-tunnel/root-controller";
    private enum Operation { INSTALL, APPLY, MAINTAIN, CHECK, ENABLE, DISABLE, STATUS }
    private RootHelper() { }
    public static synchronized String configure(Context context, RootPolicy policy) throws IOException, InterruptedException {
        File binary = extract(context);
        try {
            run(binary.getAbsolutePath(), Operation.INSTALL, policy.encode());
            String result = run(CONTROLLER, policy.enabled ? Operation.ENABLE : Operation.DISABLE, null);
            run(CONTROLLER, Operation.CHECK, null);
            RootSettings.save(context, policy);
            RootMaintenanceJob.schedule(context);
            String state = result + "\n" + status();
            notifySsh(context);
            return state;
        } finally {
            if (!binary.delete() && binary.exists()) binary.deleteOnExit();
        }
    }
    public static synchronized String apply(Context context, RootPolicy policy) throws IOException, InterruptedException {
        run(CONTROLLER, Operation.APPLY, policy.encode());
        RootSettings.save(context, policy);
        String state = status();
        notifySsh(context);
        return state;
    }
    public static String status() throws IOException, InterruptedException { return run(CONTROLLER, Operation.STATUS, null); }
    public static synchronized void maintain(Context context) throws IOException, InterruptedException {
        run(CONTROLLER, Operation.MAINTAIN, RootSettings.read(context).encode());
        notifySsh(context);
    }
    private static void notifySsh(Context context) {
        if (Settings.enabled(context) && Settings.prefs(context).getBoolean("root_mode", false))
            context.startForegroundService(new Intent(context, SshdService.class).putExtra("root_guard_ready", true));
    }
    public static void verifyGuard() throws IOException, InterruptedException { run(CONTROLLER, Operation.CHECK, null); }
    private static File extract(Context context) throws IOException {
        String abi = null;
        for (String candidate : Build.SUPPORTED_ABIS)
            if (candidate.equals("arm64-v8a") || candidate.equals("x86_64")) { abi = candidate; break; }
        if (abi == null) throw new IOException("Unsupported controller ABI");
        File binary = File.createTempFile("root-controller-", ".bin", context.getCacheDir());
        try (InputStream source = context.getAssets().open(abi + "/root-controller");
             FileOutputStream output = new FileOutputStream(binary)) {
            byte[] buffer = new byte[8192]; int count; long size = 0;
            while ((count = source.read(buffer)) != -1) { output.write(buffer, 0, count); size += count; }
            output.getFD().sync();
            if (size == 0 || !binary.setExecutable(true, true)) throw new IOException("Cannot prepare root controller");
            return binary;
        } catch (IOException e) { binary.delete(); throw e; }
    }
    private static String run(String binary, Operation operation, String policy) throws IOException, InterruptedException {
        // Both binary paths originate in this class, not in user input.
        String command = "'" + binary.replace("'", "'\\''") + "' " + operation.name().toLowerCase(java.util.Locale.ROOT);
        Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        try (OutputStream input = process.getOutputStream()) {
            if (policy != null) input.write(policy.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) { process.destroyForcibly(); throw e; }
        return awaitResult(process);
    }
    static String awaitResult(Process process) throws IOException, InterruptedException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] chunk = new byte[1024]; int count;
                while ((count = input.read(chunk)) != -1) {
                    synchronized (buffer) {
                        buffer.write(chunk, 0, Math.min(count, Math.max(0, 8192 - buffer.size())));
                    }
                }
            } catch (IOException ignored) { /* The owning request closes the stream on cancellation. */ }
        }, "root-result");
        reader.setDaemon(true); reader.start();
        try {
            if (!process.waitFor(25, TimeUnit.SECONDS)) throw new IOException("Root request timed out");
            reader.join(1000);
            if (reader.isAlive()) throw new IOException("Root controller left its output pipe open");
            String output;
            synchronized (buffer) { output = new String(buffer.toByteArray(), StandardCharsets.UTF_8).trim(); }
            int exitCode = process.exitValue();
            if (exitCode != 0) throw new IOException("Root controller request failed (exit " + exitCode + ")" + (output.isEmpty() ? "" : ": " + output));
            return output;
        } finally {
            process.destroyForcibly();
            try { process.getInputStream().close(); } catch (IOException ignored) { }
            reader.interrupt();
        }
    }
}
