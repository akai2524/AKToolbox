package dev.ak.toolbox;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import java.util.ArrayList;

/** Returns the enabled list only to the current input method. */
public final class ImeListReceiver extends BroadcastReceiver {
    public static final String ACTION = "dev.ak.toolbox.action.ENABLED_IME_LIST";
    public static final String SWITCH = "dev.ak.toolbox.action.SWITCH_IME";
    public static final String RESULT = "input_methods";
    public static final String TARGET = "target";
    public static final String EXPIRES = "expires_elapsed_realtime";

    @Override public void onReceive(Context context, Intent intent) {
        try { handle(context, intent); }
        catch (RuntimeException error) {
            android.util.Log.w("AKToolbox", "输入法操作未完成：" + error.getClass().getSimpleName());
        }
    }

    private void handle(Context context, Intent intent) {
        if (!isOrderedBroadcast() || !(ACTION.equals(intent.getAction()) || SWITCH.equals(intent.getAction()))
            || !Options.enabled(context, Options.IME_SWITCH_LIST)) return;
        long expires = intent.getLongExtra(EXPIRES, 0);
        if (expires > 0 && SystemClock.elapsedRealtime() >= expires) return;
        int caller = getSentFromUid();
        if (caller < 0 || !UserHandle.getUserHandleForUid(caller).equals(Process.myUserHandle())) return;
        String selected = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        ComponentName current = selected == null ? null : ComponentName.unflattenFromString(selected);
        String[] callerPackages = context.getPackageManager().getPackagesForUid(caller);
        boolean authorized = false;
        if (current != null && callerPackages != null) {
            for (String pkg : callerPackages) if (current.getPackageName().equals(pkg)) authorized = true;
        }
        if (!authorized) return;
        InputMethodManager manager = context.getSystemService(InputMethodManager.class);
        if (manager == null) return;
        ArrayList<InputMethodInfo> enabled = new ArrayList<>(manager.getEnabledInputMethodList());
        if (enabled.stream().noneMatch(info -> info.getId().equals(selected))) return;
        Bundle result = new Bundle();
        if (ACTION.equals(intent.getAction())) {
            result.putParcelableArrayList(RESULT, enabled);
        } else {
            String target = intent.getStringExtra(TARGET);
            if (target == null || enabled.stream().noneMatch(info -> info.getId().equals(target))) return;
            if (!selected.equals(Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD))) return;
            if (expires > 0 && SystemClock.elapsedRealtime() >= expires) return;
            // Android's documented compatibility path for a privileged caller resets the subtype
            // and changes the selected input method through the secure settings observer.
            manager.setInputMethod(null, target);
            if (!target.equals(Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD))) return;
        }
        setResult(Activity.RESULT_OK, null, result);
    }
}
