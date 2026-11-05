package dev.akai.toolbox.probe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Checks only whether an ordinary app can read the module's feature flag. */
public final class SettingsProbeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String result;
        try {
            if ("ime_list".equals(intent.getStringExtra("check"))) {
                StringBuilder values = new StringBuilder();
                for (InputMethodInfo info : context.getSystemService(InputMethodManager.class).getEnabledInputMethodList()) {
                    if (values.length() > 0) values.append(':');
                    values.append(info.getId());
                }
                result = values.toString();
            } else {
                int value = Settings.Secure.getInt(context.getContentResolver(),
                    "akai_toolbox_third_party_ime", 1);
                result = "value=" + value;
            }
        } catch (RuntimeException error) {
            result = "readable=false error=" + error.getClass().getSimpleName();
        }
        try (FileOutputStream output = context.openFileOutput("ime-settings-check.txt", Context.MODE_PRIVATE)) {
            output.write(result.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }
}
