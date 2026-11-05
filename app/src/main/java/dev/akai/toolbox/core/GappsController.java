package dev.akai.toolbox.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Pure Java state machine; privileged Android calls live behind PackageAccess. */
public final class GappsController {
    public enum State { ON, OFF, PARTIAL, MISSING }
    public interface PackageAccess {
        boolean installed(String name) throws Exception;
        boolean enabled(String name) throws Exception;
        int state(String name) throws Exception;
        void setState(String name, int state) throws Exception;
        void stop(String name) throws Exception;
    }
    public interface SnapshotStore {
        Map<String, Integer> load() throws Exception; // null means no snapshot
        void save(Map<String, Integer> states) throws Exception;
        void clear() throws Exception;
    }
    private final PackageAccess packages;
    private final SnapshotStore snapshots;
    public GappsController(PackageAccess packages, SnapshotStore snapshots) {
        this.packages = packages;
        this.snapshots = snapshots;
    }
    public synchronized State state() throws Exception {
        int active = 0;
        for (String name : PackageCatalog.PRIMARY) {
            if (!packages.installed(name)) return State.MISSING;
            if (packages.enabled(name)) active++;
        }
        return active == 3 ? State.ON : active == 0 ? State.OFF : State.PARTIAL;
    }
    public synchronized State toggle() throws Exception {
        return setEnabled(state() == State.OFF);
    }
    public synchronized State setEnabled(boolean enabled) throws Exception {
        if (state() == State.MISSING) throw new IllegalStateException("缺少 Google 核心组件，未执行切换");
        Map<String, Integer> previousSnapshot = snapshots.load();
        LinkedHashMap<String, Integer> before = new LinkedHashMap<>();
        for (String name : PackageCatalog.CORE) if (packages.installed(name)) before.put(name, packages.state(name));
        LinkedHashMap<String, Integer> saved = new LinkedHashMap<>();
        if (previousSnapshot != null) {
            previousSnapshot.forEach((name, value) -> { if (PackageCatalog.allowed(name)) saved.put(name, value); });
        }
        if (!enabled) {
            before.forEach(saved::putIfAbsent);
            snapshots.save(saved); // Persist recovery data before touching any package.
        }
        try {
            for (String name : before.keySet()) {
                // State 2 matches Xiaomi's native switch (which tests specifically for state 2).
                int desired = enabled ? saved.getOrDefault(name, 1) : 2;
                if (packages.state(name) != desired) packages.setState(name, desired);
                if (!enabled) packages.stop(name);
            }
            if (enabled) snapshots.clear();
            return state();
        } catch (Exception failure) {
            boolean rolledBack = true;
            for (Map.Entry<String, Integer> entry : before.entrySet()) {
                try { packages.setState(entry.getKey(), entry.getValue()); }
                catch (Exception rollbackError) { rolledBack = false; failure.addSuppressed(rollbackError); }
            }
            if (rolledBack) {
                if (previousSnapshot == null) snapshots.clear(); else snapshots.save(previousSnapshot);
            } else if (enabled && previousSnapshot != null) {
                snapshots.save(previousSnapshot);
            }
            throw new IllegalStateException(rolledBack
                ? "切换失败，已恢复原启用状态：" + failure.getMessage()
                : "切换未完成，已保留恢复记录，请重新开启 Google服务：" + failure.getMessage(), failure);
        }
    }
}

