package dev.akai.toolbox.hook;

import android.app.ActivityManager;
import android.app.Application;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import dev.akai.toolbox.BridgeClient;
import dev.akai.toolbox.Options;
import dev.akai.toolbox.ImeListReceiver;
import dev.akai.toolbox.core.GappsController;
import dev.akai.toolbox.core.LegacyGappsRecovery;
import dev.akai.toolbox.core.PackageCatalog;
import de.robv.android.xposed.XposedHelpers;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SystemBridge {
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> new Thread(r, "AkaiToolbox-actions"));
    private static boolean registered;
    private SystemBridge() {}
    public static synchronized void install(Application context) {
        if (registered) return;
        try {
            if (context.getPackageManager().getPackageInfo("com.android.systemui", 0).getLongVersionCode() != 202602260L)
                throw new IllegalStateException("系统界面版本与适配版本不匹配");
        } catch (PackageManager.NameNotFoundException error) { throw new IllegalStateException(error); }
        HookSupport.systemContext = context;
        PackageManager pm = context.getPackageManager();
        SharedPreferences recovery = context.createDeviceProtectedStorageContext()
            .getSharedPreferences("akai_toolbox_gapps_recovery", Context.MODE_PRIVATE);
        GappsController.PackageAccess packages = new GappsController.PackageAccess() {
            public boolean installed(String name) {
                try { pm.getApplicationInfo(name, PackageManager.MATCH_DISABLED_COMPONENTS); return true; }
                catch (PackageManager.NameNotFoundException error) { return false; }
            }
            public boolean enabled(String name) throws Exception {
                return pm.getApplicationInfo(name, PackageManager.MATCH_DISABLED_COMPONENTS).enabled;
            }
            public int state(String name) { return pm.getApplicationEnabledSetting(name); }
            public void setState(String name, int value) { pm.setApplicationEnabledSetting(name, value, 0); }
            public void stop(String name) {
                try { XposedHelpers.callMethod(context.getSystemService(ActivityManager.class), "forceStopPackage", name); }
                catch (Throwable error) { throw new IllegalStateException("停止 " + name + " 失败", error); }
            }
        };
        GappsController.SnapshotStore snapshots = new GappsController.SnapshotStore() {
            public Map<String, Integer> load() throws Exception {
                String raw = recovery.getString("snapshot", null);
                if (raw == null) return null;
                JSONObject json = new JSONObject(raw);
                LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
                java.util.Iterator<String> names = json.keys();
                while (names.hasNext()) {
                    String name = names.next();
                    if (!PackageCatalog.allowed(name) && !LegacyGappsRecovery.isLegacy(name)) continue;
                    int state = json.getInt(name);
                    if (state < 0 || state > 4) throw new IllegalStateException("GApps 恢复记录包含无效状态");
                    result.put(name, state);
                }
                return result;
            }
            public void save(Map<String, Integer> value) {
                if (!recovery.edit().putString("snapshot", new JSONObject(value).toString()).commit())
                    throw new IllegalStateException("无法保存恢复记录，未继续切换");
            }
            public void clear() {
                if (!recovery.edit().remove("snapshot").commit()) throw new IllegalStateException("无法更新恢复记录");
            }
        };
        GappsController controller = new GappsController(packages, snapshots);
        WORK.execute(() -> {
            try {
                int restored = LegacyGappsRecovery.restoreRecordedApps(packages, snapshots);
                if (restored > 0) HookSupport.log("旧版跟随应用已恢复原状态：" + restored + " 个");
            } catch (Throwable error) { HookSupport.error("旧版应用恢复未完成，已保留恢复记录", error); }
        });
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                PendingIntent reply = intent.getParcelableExtra("reply", PendingIntent.class);
                if (reply == null || !BridgeClient.APP.equals(reply.getCreatorPackage())) return;
                PendingResult pending = goAsync();
                WORK.execute(() -> {
                    Intent result = new Intent().putExtra("connected", true);
                    boolean restartRequested = false;
                    try {
                        String command = intent.getStringExtra("command");
                        LegacyGappsRecovery.restoreRecordedApps(packages, snapshots);
                        if (BridgeClient.TOGGLE.equals(command)) {
                            controller.toggle();
                        } else if (BridgeClient.ADD_GOOGLE_TILE.equals(command)) {
                            StatusBarManager statusBar = context.getSystemService(StatusBarManager.class);
                            if (statusBar == null) throw new IllegalStateException("系统状态栏不可用");
                            Object statusBarService = XposedHelpers.callMethod(statusBar, "getService");
                            if (statusBarService == null) throw new IllegalStateException("系统状态栏服务不可用");
                            // Restrict this privileged operation to our own Google tile.
                            XposedHelpers.callMethod(statusBarService, "addTile",
                                new ComponentName(BridgeClient.APP, BridgeClient.APP + ".GappsTileService"));
                        } else if (BridgeClient.RESTART_SYSTEM_UI.equals(command)) {
                            restartRequested = true;
                            result.putExtra("restarting", true);
                        } else if (BridgeClient.SET_OPTION.equals(command)) {
                            String key = intent.getStringExtra("key");
                            if (!Options.LABELS.containsKey(key)) throw new IllegalArgumentException("未知设置");
                            if (!Settings.Secure.putInt(context.getContentResolver(), key,
                                intent.getBooleanExtra("value", true) ? 1 : 0)) throw new IllegalStateException("设置保存失败");
                        } else if (!BridgeClient.QUERY.equals(command)) throw new IllegalArgumentException("未知操作");
                    } catch (Throwable error) {
                        result.putExtra("error", error.getMessage() == null ? "操作未完成" : error.getMessage());
                        HookSupport.error("操作未完成", error);
                    }
                    try { result.putExtra("state", controller.state().name()); }
                    catch (Throwable error) { result.putExtra("state", "MISSING"); }
                    for (String key : Options.LABELS.keySet()) result.putExtra(key, Options.enabled(context, key));
                    boolean delivered = false;
                    try { reply.send(context, 0, result); delivered = true; }
                    catch (PendingIntent.CanceledException ignoredError) { /* Caller left or timed out. */ }
                    finally { pending.finish(); }
                    if (restartRequested && delivered) {
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            HookSupport.log("用户请求重启系统界面");
                            Process.killProcess(Process.myPid());
                        }, 400);
                    }
                });
            }
        };
        context.registerReceiver(receiver, new IntentFilter(BridgeClient.ACTION), BridgeClient.PERMISSION,
            null, Context.RECEIVER_EXPORTED);
        IntentFilter imeActions = new IntentFilter(ImeListReceiver.ACTION);
        imeActions.addAction(ImeListReceiver.SWITCH);
        context.registerReceiver(new ImeListReceiver(), imeActions, null, null, Context.RECEIVER_EXPORTED);
        registered = true;
        try { HeadsetAudioSwitch.install(context, WORK); }
        catch (Throwable error) { HookSupport.error("耳机音效未启用", error); }
        HookSupport.log("系统界面桥接已注册");
    }
}

