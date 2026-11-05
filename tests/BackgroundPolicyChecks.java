import dev.ak.toolbox.core.BackgroundPolicyController;
import java.util.HashMap;
import java.util.Map;

public final class BackgroundPolicyChecks {
    private static final String A = BackgroundPolicyController.AUTOSTART, B = BackgroundPolicyController.BATTERY,
        C = BackgroundPolicyController.BACKGROUND, D = BackgroundPolicyController.BACKGROUND_ANY;
    static final class Fake implements BackgroundPolicyController.Access, BackgroundPolicyController.Store {
        final Map<String, String> values = new HashMap<>(), saved = new HashMap<>();
        String failWrite, failRead, failSave, ineffective, race;
        int writes;
        Fake() { values.put(A, "ignore"); values.put(B, "miuiAuto"); values.put(C, "default"); values.put(D, "ignore"); }
        public String read(String key) throws Exception {
            if (key.equals(failRead)) throw new Exception("read");
            return values.get(key);
        }
        public boolean writeIfEquals(String key, String expected, String next) throws Exception {
            if (key.equals(failWrite)) throw new Exception("write");
            if (key.equals(race)) values.put(key, "manual");
            if (!values.get(key).equals(expected)) return false;
            values.put(key, next); writes++; return true;
        }
        public boolean effective(String key) { return !key.equals(ineffective); }
        public String load(String key) { return saved.get(key); }
        public void save(String key, String value) throws Exception {
            if (key.equals(failSave)) throw new Exception("save");
            saved.put(key, value);
        }
        public void remove(String key) { saved.remove(key); }
        BackgroundPolicyController controller() { return new BackgroundPolicyController(this, this); }
    }
    static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) {
        Fake f = new Fake();
        require(f.controller().enable().complete(), "initial enable");
        require(f.values.get(A).equals("allow") && f.values.get(B).equals("noRestrict"), "desired values");
        require(f.saved.get(A).equals("ignore") && f.saved.get(B).equals("miuiAuto"), "originals saved");
        require(f.values.get(C).equals("allow") && f.values.get(D).equals("allow"), "background modes enabled");
        require(f.controller().enable().complete() && f.writes == 4, "repeat does not write");
        require(f.controller().restore().complete() && f.values.get(A).equals("ignore") && f.values.get(B).equals("miuiAuto"), "restore originals");
        require(f.saved.isEmpty(), "cleared after restoration");
        require(f.values.get(C).equals("default") && f.values.get(D).equals("ignore"), "background modes restored");

        f = new Fake(); f.values.put(A, "allow"); f.values.put(B, "noRestrict"); f.values.put(C, "allow"); f.values.put(D, "allow");
        require(f.controller().enable().complete() && f.saved.isEmpty(), "preexisting settings not owned");
        require(f.controller().restore().complete() && f.writes == 0, "preexisting settings retained");

        f = new Fake(); f.controller().enable(); f.values.put(B, "restrict");
        require(f.controller().restore().complete() && f.values.get(B).equals("restrict"), "manual change preserved");

        f = new Fake(); f.controller().enable(); f.failWrite = A;
        require(!f.controller().restore().complete() && f.saved.containsKey(A), "failed restore remains retryable");
        f.failWrite = null;
        require(f.controller().restore().complete() && f.values.get(A).equals("ignore"), "restore retry");

        f = new Fake(); f.failSave = A;
        require(!f.controller().enable().complete() && f.values.get(A).equals("ignore"), "no write without durable snapshot");
        require(f.values.get(B).equals("noRestrict"), "independent setting can succeed");

        f = new Fake(); f.race = B;
        require(!f.controller().enable().complete() && f.values.get(B).equals("manual"), "concurrent setting not overwritten");
        f.race = null;
        require(f.controller().restore().complete() && f.values.get(B).equals("manual"), "concurrent change retained");

        f = new Fake(); f.failRead = B;
        require(!f.controller().enable().complete() && f.values.get(B).equals("miuiAuto"), "unknown vendor state untouched");
        f = new Fake(); f.ineffective = B;
        require(!f.controller().enable().complete(), "unconfirmed effective state is not success");
        System.out.println("Background policy state, rollback and failure checks passed.");
    }
}
