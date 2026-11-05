package dev.akai.toolbox.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One-time upgrade recovery only. These apps are never targets of the GApps switch. */
public final class LegacyGappsRecovery {
    private static final List<String> REMOVED_APPS = List.of(
        "com.google.android.googlequicksearchbox", "com.google.android.apps.bard",
        "com.google.android.gm", "com.google.android.youtube", "com.google.android.apps.maps",
        "com.google.android.apps.photos", "com.google.android.apps.docs");
    private LegacyGappsRecovery() {}

    public static boolean isLegacy(String name) { return REMOVED_APPS.contains(name); }

    public static int restoreRecordedApps(GappsController.PackageAccess packages,
        GappsController.SnapshotStore snapshots) throws Exception {
        Map<String, Integer> previous = snapshots.load();
        if (previous == null) return 0;
        LinkedHashMap<String, Integer> core = new LinkedHashMap<>();
        boolean needsCleanup = false;
        // Validate the entire recovery record before modifying any package.
        for (Map.Entry<String, Integer> entry : previous.entrySet()) {
            if (PackageCatalog.allowed(entry.getKey()) || isLegacy(entry.getKey())) {
                if (entry.getValue() == null || entry.getValue() < 0 || entry.getValue() > 4)
                    throw new IllegalStateException("旧版 GApps 恢复记录无效，已停止恢复");
            }
            if (PackageCatalog.allowed(entry.getKey())) core.put(entry.getKey(), entry.getValue());
            else needsCleanup = true;
        }
        if (!needsCleanup) return 0;
        int restored = 0;
        for (String name : REMOVED_APPS) {
            Integer original = previous.get(name);
            if (original == null || !packages.installed(name)) continue;
            // Preserve manual changes made after the old module disabled the app.
            if (packages.state(name) == 2 && original != 2) {
                packages.setState(name, original);
                restored++;
            }
        }
        // Failed restoration leaves the old record available for an idempotent retry.
        if (core.isEmpty()) snapshots.clear(); else snapshots.save(core);
        return restored;
    }
}
