package dev.ak.toolbox;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import dev.ak.toolbox.core.BackgroundPolicyController;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One-time, per-app changes. Unsupported vendor states are left for the user. */
public final class BackgroundPolicy {
    private static final String URI = "content://com.miui.powerkeeper.configure/userTable";
    private static final String PKG = "dev.ak.toolbox";
    private static final String BATTERY_WHERE = "userId=0 AND pkgName='" + PKG + "'";
    private static final int POLICY_REVISION = 2;
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AK-background-policy"); t.setDaemon(true); return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean INITIALIZING = new AtomicBoolean();
    public interface Callback { void done(BackgroundPolicyController.Result result); }
    private BackgroundPolicy() { }

    public static boolean hasPendingRestore(Context context) {
        for (String key : context.getSharedPreferences("payment_background_policy", Context.MODE_PRIVATE).getAll().keySet())
            if (key.startsWith("original_")) return true;
        return false;
    }

    /** Handles an enabled setting that survived installation without a policy record. */
    public static boolean ensureInitialized(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("payment_background_policy", Context.MODE_PRIVATE);
        if (prefs.getInt("policy_revision", 0) >= POLICY_REVISION || !INITIALIZING.compareAndSet(false, true)) return false;
        change(app, true, result -> {
            INITIALIZING.set(false);
            if (callback != null) callback.done(result);
        });
        return true;
    }

    public static void change(Context context, boolean enable, Callback callback) {
        Context app = context.getApplicationContext();
        WORK.execute(() -> {
            BackgroundPolicyController.Result result;
            SharedPreferences prefs = app.getSharedPreferences("payment_background_policy", Context.MODE_PRIVATE);
            if (enable && !Options.enabled(app, Options.REAR_PAYMENT)) {
                result = new BackgroundPolicyController.Result(false, "付款码已关闭，未配置后台策略");
            } else if (!PKG.equals(app.getPackageName()) || Process.myUid() / 100000 != 0) {
                result = new BackgroundPolicyController.Result(false, "仅支持主用户配置，请手动检查后台策略");
            } else {
                BackgroundPolicyController.Store store = new BackgroundPolicyController.Store() {
                    public String load(String key) { return prefs.getString("original_" + key, null); }
                    public void save(String key, String value) throws Exception {
                        if (!prefs.edit().putString("original_" + key, value).commit()) throw new Exception("save");
                    }
                    public void remove(String key) throws Exception {
                        if (!prefs.edit().remove("original_" + key).commit()) throw new Exception("remove");
                    }
                };
                BackgroundPolicyController controller = new BackgroundPolicyController(new RootAccess(), store);
                result = enable ? controller.enable() : controller.restore();
            }
            SharedPreferences.Editor editor = prefs.edit().putString("status", result.message());
            if (enable && Options.enabled(app, Options.REAR_PAYMENT)) editor.putInt("policy_revision", POLICY_REVISION);
            editor.apply();
            BackgroundPolicyController.Result delivered = result;
            MAIN.post(() -> callback.done(delivered));
        });
    }

    public static String lastStatus(Context context) {
        return context.getSharedPreferences("payment_background_policy", Context.MODE_PRIVATE)
            .getString("status", "尚未检查；启用付款码或首次使用时自动检查");
    }

    private static final class RootAccess implements BackgroundPolicyController.Access {
        @Override public String read(String key) throws Exception {
            String op = operation(key);
            if (op != null) {
                String text = root("cmd appops get --user 0 " + PKG + " " + op);
                if (text.contains("No operations")) return "default";
                String name = BackgroundPolicyController.AUTOSTART.equals(key)
                    ? "(?:MIUIOP\\(10008\\)|10008)" : Pattern.quote(op);
                Matcher m = Pattern.compile("(?m)^\\s*" + name + "\\s*:\\s*(allow|ignore|deny|errored|default|foreground)\\b").matcher(text);
                if (!m.find()) throw new Exception("unknown appop");
                return "errored".equals(m.group(1)) ? "deny" : m.group(1);
            }
            if (!BackgroundPolicyController.BATTERY.equals(key)) throw new Exception("unknown key");
            String text = root("content query --uri " + URI + " --projection bgControl --where \"" + BATTERY_WHERE + "\"");
            Matcher m = Pattern.compile("(?m)^Row: \\d+\\s+bgControl=([A-Za-z][A-Za-z0-9_]*)\\s*$").matcher(text);
            if (!m.find()) throw new Exception("missing or unsupported row");
            String value = m.group(1);
            if ("null".equalsIgnoreCase(value)) throw new Exception("unknown battery state");
            if (m.find()) throw new Exception("ambiguous rows");
            return value;
        }
        @Override public boolean writeIfEquals(String key, String expected, String value) throws Exception {
            if (!read(key).equals(expected)) return false;
            String op = operation(key);
            if (op != null) {
                if (!Arrays.asList("allow", "ignore", "deny", "default", "foreground").contains(value)) throw new Exception("mode");
                root("cmd appops set --user 0 " + PKG + " " + op + " " + value);
            } else if (BackgroundPolicyController.BATTERY.equals(key)) {
                if (!expected.matches("[A-Za-z][A-Za-z0-9_]*") || !value.matches("[A-Za-z][A-Za-z0-9_]*")) throw new Exception("mode");
                // Compare in the provider too, so another settings change is not overwritten.
                root("content update --uri " + URI + " --bind bgControl:s:" + value
                    + " --where \"" + BATTERY_WHERE + " AND bgControl='" + expected + "'\"");
            } else throw new Exception("unknown key");
            return read(key).equals(value);
        }
        @Override public boolean effective(String key) throws Exception {
            if (operation(key) != null) return "allow".equals(read(key));
            if (!"noRestrict".equals(read(key))) return false;
            for (int i = 0; i < 8; i++) {
                String list = root("settings get --user 0 system MILLET_NO_RESTRICT_APP");
                for (String item : list.split("[,\\s]+")) if (PKG.equals(item)) return true;
                Thread.sleep(250);
            }
            return false;
        }
        private static String operation(String key) {
            return switch (key) {
                case BackgroundPolicyController.AUTOSTART -> "10008";
                case BackgroundPolicyController.BACKGROUND -> "RUN_IN_BACKGROUND";
                case BackgroundPolicyController.BACKGROUND_ANY -> "RUN_ANY_IN_BACKGROUND";
                default -> null;
            };
        }
    }

    private static String root(String command) throws Exception {
        java.lang.Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream stream = process.getInputStream()) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = stream.read(buffer)) != -1) if (output.size() < 32768) output.write(buffer, 0, count);
            } catch (Exception ignored) { }
        }, "AK-policy-output");
        reader.setDaemon(true); reader.start();
        if (!process.waitFor(6, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new Exception("root timeout"); }
        reader.join(500);
        if (process.exitValue() != 0) throw new Exception("root command failed");
        String text = output.toString(StandardCharsets.UTF_8);
        if (text.contains("Exception") || text.contains("Permission Denial") || text.startsWith("Error:")) throw new Exception("command failed");
        return text;
    }
}
