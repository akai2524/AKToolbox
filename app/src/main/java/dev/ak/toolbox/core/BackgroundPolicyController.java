package dev.ak.toolbox.core;

import java.util.ArrayList;
import java.util.List;

/** Records ownership before mutation; never overwrites a later manual change. */
public final class BackgroundPolicyController {
    public static final String AUTOSTART = "autostart", BACKGROUND = "background",
        BACKGROUND_ANY = "background_any", BATTERY = "battery";
    private static final String[] KEYS = { AUTOSTART, BACKGROUND, BACKGROUND_ANY, BATTERY };

    public interface Access {
        String read(String key) throws Exception;
        boolean writeIfEquals(String key, String expected, String value) throws Exception;
        boolean effective(String key) throws Exception;
    }
    public interface Store {
        String load(String key);
        void save(String key, String original) throws Exception;
        void remove(String key) throws Exception;
    }
    public record Result(boolean complete, String message) { }
    private final Access access;
    private final Store store;

    public BackgroundPolicyController(Access access, Store store) {
        this.access = access;
        this.store = store;
    }
    private static String desired(String key) { return BATTERY.equals(key) ? "noRestrict" : "allow"; }
    private static String label(String key) {
        return switch (key) {
            case AUTOSTART -> "后台自启动";
            case BACKGROUND -> "后台运行";
            case BACKGROUND_ANY -> "后台活动";
            default -> "小米省电策略";
        };
    }

    public Result enable() {
        List<String> errors = new ArrayList<>();
        for (String key : KEYS) {
            try {
                String current = access.read(key);
                String original = store.load(key);
                if (original != null && !current.equals(desired(key))) {
                    errors.add(label(key) + "已被更改，请手动检查");
                    continue;
                }
                if (!current.equals(desired(key))) {
                    store.save(key, current); // durable before the system setting changes
                    if (!access.writeIfEquals(key, current, desired(key))) {
                        errors.add(label(key) + "设置失败，请手动检查");
                        continue;
                    }
                }
                if (!access.effective(key)) errors.add(label(key) + "尚未确认生效，请手动检查");
            } catch (Exception e) { errors.add(label(key) + "无法读取或设置，请手动检查"); }
        }
        return errors.isEmpty() ? new Result(true, "后台自启动、运行权限和小米省电策略已确认")
            : new Result(false, String.join("；", errors));
    }

    public Result restore() {
        List<String> errors = new ArrayList<>();
        for (String key : KEYS) {
            try {
                String original = store.load(key);
                if (original == null) continue;
                String current = access.read(key);
                if (current.equals(desired(key)) && !access.writeIfEquals(key, current, original)) {
                    errors.add(label(key) + "恢复失败，可稍后重试");
                    continue;
                }
                // A different value was selected after us: leave it untouched.
                store.remove(key);
            } catch (Exception e) { errors.add(label(key) + "无法确认恢复状态，可稍后重试"); }
        }
        return errors.isEmpty() ? new Result(true, "已处理本工具箱修改的后台策略")
            : new Result(false, String.join("；", errors));
    }
}
