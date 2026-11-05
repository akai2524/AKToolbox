package dev.ak.toolbox;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;
import dev.ak.toolbox.core.PackageCatalog;

public final class GappsTileService extends TileService {
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean registered, busy;
    private final Runnable refresh = this::query;
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent.getData() == null || !PackageCatalog.allowed(intent.getData().getSchemeSpecificPart())) return;
            main.removeCallbacks(refresh);
            main.postDelayed(refresh, 250);
        }
    };
    @Override public void onStartListening() {
        super.onStartListening();
        if (!registered) {
            IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_CHANGED);
            filter.addAction(Intent.ACTION_PACKAGE_ADDED);
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addDataScheme("package");
            registerReceiver(packages, filter, Context.RECEIVER_EXPORTED);
            registered = true;
        }
        query();
    }
    @Override public void onStopListening() {
        main.removeCallbacks(refresh);
        if (registered) { unregisterReceiver(packages); registered = false; }
        super.onStopListening();
    }
    @Override public void onClick() {
        if (isLocked()) unlockAndRun(this::toggle); else toggle();
    }
    private void query() {
        if (busy) return;
        BridgeClient.send(this, BridgeClient.QUERY, null, this::display);
    }
    private void toggle() {
        if (busy) return;
        busy = true;
        // Keep the previous state until the result arrives so HyperOS can show its
        // native control-center message for the actual state change.
        BridgeClient.send(this, BridgeClient.TOGGLE, null, result -> {
            busy = false;
            display(result);
            String error = result.getStringExtra("error");
            if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show();
        });
    }
    private void display(Intent result) {
        Tile tile = getQsTile();
        if (tile == null || busy) return;
        String state = result.getStringExtra("state");
        boolean connected = result.getBooleanExtra("connected", false);
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_google_g));
        tile.setLabel("Google服务");
        tile.setState(!connected || "MISSING".equals(state) ? Tile.STATE_UNAVAILABLE
            : "ON".equals(state) ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setSubtitle(!connected ? "未连接模块" : switch (state == null ? "" : state) {
            case "ON" -> "已开启";
            case "OFF" -> "已关闭";
            case "PARTIAL" -> "状态不完整";
            default -> "缺少组件";
        });
        tile.setContentDescription("Google服务，" + tile.getSubtitle());
        tile.updateTile();
    }
}

