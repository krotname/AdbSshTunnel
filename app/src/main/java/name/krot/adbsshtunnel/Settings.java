package name.krot.adbsshtunnel;

import android.content.*;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public final class Settings {
    private Settings() { }
    public static android.content.SharedPreferences prefs(Context c) { return c.getSharedPreferences("tunnel", Context.MODE_PRIVATE); }
    public static boolean enabled(Context c) { return prefs(c).getBoolean("enabled", false); }
    public static String keys(Context c) { return prefs(c).getString("keys", ""); }
    public static int port(Context c) { return prefs(c).getInt("adb_port", 0); }
    public static void setEnabled(Context c, boolean enabled) {
        if (!prefs(c).edit().putBoolean("enabled", enabled).commit()) throw new IllegalStateException("Cannot save SSH state");
    }
    public static void saveKeys(Context c, String text) throws IOException {
        List<String> parsed = KeyPolicy.parse(text);
        if (!prefs(c).edit().putString("keys", String.join("\n", parsed)).commit()) throw new IOException("Cannot save keys");
    }
    public static File config(Context c) throws IOException {
        File dir = new File(c.getFilesDir(), ".dropbear");
        if (!dir.isDirectory() && !dir.mkdir()) throw new IOException("Cannot create SSH configuration");
        return dir;
    }
    public static void writePolicy(Context c, int port) throws IOException {
        AtomicFile file = new AtomicFile(new File(config(c), "authorized_keys"));
        FileOutputStream output = file.startWrite();
        try {
            output.write(KeyPolicy.restricted(KeyPolicy.parse(keys(c)), port).getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (Exception e) { file.failWrite(output); throw new IOException("Cannot save SSH policy", e); }
    }
}
