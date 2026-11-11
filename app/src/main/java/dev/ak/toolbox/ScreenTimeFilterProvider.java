package dev.ak.toolbox;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Read-only configuration endpoint for the Xiaomi process into which the module is loaded. */
public final class ScreenTimeFilterProvider extends ContentProvider {
    public static final Uri URI = Uri.parse("content://dev.ak.toolbox.screentimefilter");
    public static final String PREFS = "screen_time_filter";
    public static final String ENABLED = "enabled";
    public static final String PACKAGES = "packages";

    public static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Set<String> selected(Context context) {
        return new HashSet<>(preferences(context).getStringSet(PACKAGES, Collections.emptySet()));
    }

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        Context context = getContext();
        if (context == null || !"read".equals(method)) throw new SecurityException("Unsupported operation");
        int caller = Binder.getCallingUid();
        int xiaomiUid;
        try { xiaomiUid = context.getPackageManager().getApplicationInfo("com.xiaomi.misettings", 0).uid; }
        catch (Exception failure) { throw new SecurityException("Xiaomi Settings unavailable"); }
        if (caller != Process.myUid() && caller != xiaomiUid) throw new SecurityException("Caller not allowed");
        Bundle result = new Bundle();
        result.putBoolean(ENABLED, preferences(context).getBoolean(ENABLED, false));
        result.putStringArrayList(PACKAGES, new ArrayList<>(selected(context)));
        return result;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        throw new UnsupportedOperationException();
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
