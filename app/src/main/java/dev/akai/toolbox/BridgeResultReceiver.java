package dev.akai.toolbox;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BridgeResultReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) { BridgeClient.receive(intent); }
}

