package dev.akai.toolbox;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class BridgeClient {
    public static final String APP = "dev.akai.toolbox";
    public static final String ACTION = APP + ".CONTROL";
    public static final String RESULT = APP + ".RESULT";
    public static final String PERMISSION = APP + ".permission.CONTROL";
    public static final String QUERY = "query", TOGGLE = "toggle", SET_OPTION = "option";
    public static final String RESTART_SYSTEM_UI = "restart_system_ui";
    public static final String ADD_GOOGLE_TILE = "add_google_tile";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Request> REQUESTS = new HashMap<>();
    public interface Callback { void done(Intent result); }
    private record Request(PendingIntent reply, Runnable timeout, Callback callback) {}
    private BridgeClient() {}

    public static void send(Context context, String command, Intent arguments, Callback callback) {
        String id = UUID.randomUUID().toString();
        Intent result = new Intent(RESULT).setComponent(new ComponentName(context, BridgeResultReceiver.class))
            .setData(Uri.parse("akai-toolbox://result/" + id)).addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        PendingIntent reply = PendingIntent.getBroadcast(context, 0, result,
            PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_MUTABLE);
        Runnable timeout = () -> {
            Request request = REQUESTS.remove(id);
            if (request == null) return;
            request.reply().cancel();
            callback.done(new Intent().putExtra("connected", false)
                .putExtra("error", "模块未连接：请在 LSPosed 中启用模块及作用域，并重启相关应用"));
        };
        REQUESTS.put(id, new Request(reply, timeout, callback));
        MAIN.postDelayed(timeout, 6000);
        Intent request = new Intent(ACTION).setPackage("com.android.systemui")
            .putExtra("command", command).putExtra("reply", reply).addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        if (arguments != null && arguments.getExtras() != null) request.putExtras(arguments.getExtras());
        try { context.sendBroadcast(request); }
        catch (RuntimeException error) { MAIN.removeCallbacks(timeout); timeout.run(); }
    }
    static void receive(Intent result) {
        if (!RESULT.equals(result.getAction()) || result.getData() == null) return;
        Request request = REQUESTS.remove(result.getData().getLastPathSegment());
        if (request == null) return;
        MAIN.removeCallbacks(request.timeout());
        request.callback().done(result);
    }
}

