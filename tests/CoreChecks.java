import dev.akai.toolbox.core.GappsController;
import dev.akai.toolbox.core.LegacyGappsRecovery;
import dev.akai.toolbox.core.PackageCatalog;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class CoreChecks {
    private static final String GOOGLE = "com.google.android.googlequicksearchbox";
    private static final String GEMINI = "com.google.android.apps.bard";
    private static final String WEBVIEW = "com.google.android.webview";
    private static int passed;
    static final class Packages implements GappsController.PackageAccess {
        final LinkedHashMap<String, Integer> states = new LinkedHashMap<>();
        final Set<String> stopped = new LinkedHashSet<>();
        String failOnce, stopFailOnce;
        Packages() {
            for (String name : PackageCatalog.CORE) states.put(name, 0);
            for (String name : PackageCatalog.PRIMARY) states.put(name, 1);
            states.put(GOOGLE, 0); states.put(GEMINI, 3); states.put(WEBVIEW, 0);
        }
        public boolean installed(String name) { return states.containsKey(name); }
        public boolean enabled(String name) { return states.get(name) < 2; }
        public int state(String name) { return states.get(name); }
        public void setState(String name, int value) throws Exception {
            if (name.equals(failOnce)) { failOnce = null; throw new Exception("injected package failure"); }
            states.put(name, value);
        }
        public void stop(String name) throws Exception {
            if (name.equals(stopFailOnce)) { stopFailOnce = null; throw new Exception("injected stop failure"); }
            stopped.add(name);
        }
    }
    static final class Store implements GappsController.SnapshotStore {
        Map<String, Integer> saved;
        boolean failSave;
        int writes;
        public Map<String, Integer> load() { return saved == null ? null : new LinkedHashMap<>(saved); }
        public void save(Map<String, Integer> value) throws Exception {
            if (failSave) throw new Exception("injected storage failure");
            saved = new LinkedHashMap<>(value); writes++;
        }
        public void clear() { saved = null; writes++; }
    }
    interface Check { void run() throws Exception; }
    private static void check(String name, Check run) throws Exception {
        run.run(); passed++; System.out.println("PASS " + name);
    }
    private static void require(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
    private static void expectFailure(Check run) throws Exception {
        try { run.run(); throw new AssertionError("failure swallowed"); }
        catch (Exception expected) {}
    }
    private static Store oldSnapshot(Packages p) {
        Store s = new Store();
        s.saved = new LinkedHashMap<>();
        for (String name : PackageCatalog.CORE) {
            s.saved.put(name, p.states.get(name)); p.states.put(name, 2);
        }
        s.saved.put(GOOGLE, p.states.get(GOOGLE));
        s.saved.put(GEMINI, p.states.get(GEMINI));
        p.states.put(GOOGLE, 2); p.states.put(GEMINI, 2);
        return s;
    }
    public static void main(String[] args) throws Exception {
        check("disable only core packages and stop their processes", () -> {
            Packages p = new Packages(); Store s = new Store();
            require(new GappsController(p, s).setEnabled(false) == GappsController.State.OFF, "not off");
            require(p.state(GOOGLE) == 0 && p.state(GEMINI) == 3 && p.state(WEBVIEW) == 0, "non-core app changed");
            require(p.stopped.equals(new LinkedHashSet<>(PackageCatalog.CORE)), "stop target list is not core-only");
            require(s.saved.keySet().equals(new LinkedHashSet<>(PackageCatalog.CORE)), "non-core recovery entry");
        });
        check("restore exact core defaults and prior disabled states", () -> {
            Packages p = new Packages(); Store s = new Store();
            p.states.put("com.google.android.configupdater", 3);
            Map<String,Integer> original = new LinkedHashMap<>(p.states);
            GappsController c = new GappsController(p, s);
            c.setEnabled(false); c.setEnabled(true);
            require(p.states.equals(original) && s.saved == null, "prior states lost");
        });
        check("double off preserves original recovery record", () -> {
            Packages p = new Packages(); Store s = new Store();
            Map<String,Integer> original = new LinkedHashMap<>(p.states);
            GappsController c = new GappsController(p, s);
            c.setEnabled(false); c.setEnabled(false); c.setEnabled(true);
            require(p.states.equals(original), "snapshot overwritten");
        });
        check("partial mutation failure rolls back and clears new snapshot", () -> {
            Packages p = new Packages(); Store s = new Store();
            Map<String,Integer> original = new LinkedHashMap<>(p.states);
            p.failOnce = PackageCatalog.PRIMARY.get(1);
            expectFailure(() -> new GappsController(p, s).setEnabled(false));
            require(p.states.equals(original) && s.saved == null, "incomplete rollback");
        });
        check("storage failure prevents all package changes", () -> {
            Packages p = new Packages(); Store s = new Store();
            Map<String,Integer> original = new LinkedHashMap<>(p.states); s.failSave = true;
            expectFailure(() -> new GappsController(p, s).setEnabled(false));
            require(p.states.equals(original) && p.stopped.isEmpty(), "changed before durable snapshot");
        });
        check("native off can be enabled without touching other apps", () -> {
            Packages p = new Packages(); Store s = new Store();
            for (String name : PackageCatalog.CORE) p.states.put(name, 2);
            require(new GappsController(p, s).toggle() == GappsController.State.ON, "native off not enabled");
            require(p.state(GOOGLE) == 0 && p.state(GEMINI) == 3, "non-core app enabled");
        });
        check("process restart retains core recovery state", () -> {
            Packages p = new Packages(); Store s = new Store();
            Map<String,Integer> original = new LinkedHashMap<>(p.states);
            new GappsController(p, s).setEnabled(false);
            new GappsController(p, s).setEnabled(true);
            require(p.states.equals(original), "restart recovery lost");
        });
        check("mixed core state completes disable on toggle", () -> {
            Packages p = new Packages(); Store s = new Store(); p.states.put(PackageCatalog.PRIMARY.get(0), 2);
            GappsController c = new GappsController(p, s);
            require(c.state() == GappsController.State.PARTIAL, "mixed state hidden");
            require(c.toggle() == GappsController.State.OFF, "mixed state unexpectedly enabled");
        });
        check("missing core aborts before changes", () -> {
            Packages p = new Packages(); Store s = new Store(); p.states.remove(PackageCatalog.PRIMARY.get(0));
            expectFailure(() -> new GappsController(p, s).toggle());
            require(s.saved == null && p.stopped.isEmpty(), "mutation despite missing core");
        });
        check("core restore never applies foreign snapshot entries", () -> {
            Packages p = new Packages(); Store s = new Store(); s.saved = new LinkedHashMap<>();
            for (String name : PackageCatalog.CORE) { s.saved.put(name, 1); p.states.put(name, 2); }
            s.saved.put(GOOGLE, 1); s.saved.put(GEMINI, 1); s.saved.put(WEBVIEW, 1);
            new GappsController(p, s).setEnabled(true);
            require(p.state(GOOGLE) == 0 && p.state(GEMINI) == 3 && p.state(WEBVIEW) == 0, "foreign entry applied");
        });
        check("failed enable retains snapshot for successful retry", () -> {
            Packages p = new Packages(); Store s = new Store();
            Map<String,Integer> original = new LinkedHashMap<>(p.states);
            GappsController c = new GappsController(p, s); c.setEnabled(false);
            p.failOnce = PackageCatalog.PRIMARY.get(1);
            expectFailure(() -> c.setEnabled(true));
            require(c.state() == GappsController.State.OFF && s.saved != null, "failed enable lost recovery");
            c.setEnabled(true);
            require(p.states.equals(original), "retry did not restore prior states");
        });
        check("process-stop failure rolls back enabled states", () -> {
            Packages p = new Packages(); Store s = new Store();
            Map<String,Integer> original = new LinkedHashMap<>(p.states);
            p.stopFailOnce = PackageCatalog.PRIMARY.get(0);
            expectFailure(() -> new GappsController(p, s).setEnabled(false));
            require(p.states.equals(original) && s.saved == null, "stop failure left disabled packages");
        });
        check("upgrade restores removed apps and keeps core off", () -> {
            Packages p = new Packages(); Store s = oldSnapshot(p);
            p.states.put(WEBVIEW, 2); s.saved.put(WEBVIEW, 0);
            require(LegacyGappsRecovery.restoreRecordedApps(p, s) == 2, "restore count incorrect");
            require(p.state(GOOGLE) == 0 && p.state(GEMINI) == 3, "original app state lost");
            require(p.state(WEBVIEW) == 2 && p.stopped.isEmpty(), "unrelated package or process touched");
            require(new GappsController(p, s).state() == GappsController.State.OFF, "core state changed");
            require(s.saved.keySet().equals(new LinkedHashSet<>(PackageCatalog.CORE)), "old app records retained");
        });
        check("upgrade recovery is idempotent and later toggles are core-only", () -> {
            Packages p = new Packages(); Store s = oldSnapshot(p);
            LegacyGappsRecovery.restoreRecordedApps(p, s);
            int writes = s.writes;
            require(LegacyGappsRecovery.restoreRecordedApps(p, s) == 0 && s.writes == writes, "repeated migration write");
            GappsController c = new GappsController(p, s); c.toggle(); c.toggle();
            require(p.state(GOOGLE) == 0 && p.state(GEMINI) == 3, "removed feature returned");
        });
        check("upgrade preserves manual changes and missing apps", () -> {
            Packages p = new Packages(); Store s = oldSnapshot(p);
            p.states.put(GOOGLE, 1); p.states.remove(GEMINI);
            LegacyGappsRecovery.restoreRecordedApps(p, s);
            require(p.state(GOOGLE) == 1 && !p.installed(GEMINI), "manual state changed or app recreated");
            require(!s.saved.containsKey(GOOGLE) && !s.saved.containsKey(GEMINI), "old records not removed");
        });
        check("failed upgrade restoration keeps original record for retry", () -> {
            Packages p = new Packages(); Store s = oldSnapshot(p);
            Map<String,Integer> previous = new LinkedHashMap<>(s.saved); p.failOnce = GEMINI;
            expectFailure(() -> LegacyGappsRecovery.restoreRecordedApps(p, s));
            require(s.saved.equals(previous), "failed restoration lost record");
            LegacyGappsRecovery.restoreRecordedApps(p, s);
            require(p.state(GOOGLE) == 0 && p.state(GEMINI) == 3, "retry failed");
        });
        check("upgrade persistence failure can be retried safely", () -> {
            Packages p = new Packages(); Store s = oldSnapshot(p);
            Map<String,Integer> previous = new LinkedHashMap<>(s.saved); s.failSave = true;
            expectFailure(() -> LegacyGappsRecovery.restoreRecordedApps(p, s));
            require(s.saved.equals(previous) && p.state(GOOGLE) == 0 && p.state(GEMINI) == 3, "recovery data lost");
            s.failSave = false; LegacyGappsRecovery.restoreRecordedApps(p, s);
            require(!s.saved.containsKey(GOOGLE) && !s.saved.containsKey(GEMINI), "retry did not prune records");
        });
        check("invalid upgrade record fails before any mutation", () -> {
            Packages p = new Packages(); Store s = oldSnapshot(p); s.saved.put(GEMINI, 99);
            Map<String,Integer> original = new LinkedHashMap<>(p.states);
            expectFailure(() -> LegacyGappsRecovery.restoreRecordedApps(p, s));
            require(p.states.equals(original), "invalid record caused mutation");
        });
        check("no upgrade record has no effects", () -> {
            Packages p = new Packages(); Store s = new Store();
            Map<String,Integer> original = new LinkedHashMap<>(p.states);
            require(LegacyGappsRecovery.restoreRecordedApps(p, s) == 0, "unexpected recovery");
            require(p.states.equals(original) && s.writes == 0, "new install changed apps");
        });
        check("previously disabled removed app remains disabled", () -> {
            Packages p = new Packages(); p.states.put(GOOGLE, 2); Store s = oldSnapshot(p);
            LegacyGappsRecovery.restoreRecordedApps(p, s);
            require(p.state(GOOGLE) == 2 && p.state(GEMINI) == 3, "disabled app unexpectedly enabled");
        });
        System.out.println("Passed " + passed + " state-machine checks.");
    }
}

