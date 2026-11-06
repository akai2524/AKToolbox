package dev.ak.toolbox.hook;

import android.content.ComponentName;
import java.util.List;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** Adds our tile after existing controls while leaving the edit tile at the end. */
final class GoogleTilePlacement {
    private static final ComponentName TILE = new ComponentName("dev.ak.toolbox", "dev.ak.toolbox.GappsTileService");
    private static volatile boolean installed;
    private GoogleTilePlacement() { }

    static boolean installed() { return installed; }

    static void install(ClassLoader loader) {
        Class<?> adapter = XposedHelpers.findClass("com.android.systemui.qs.pipeline.domain.adapter.MiuiQSHostAdapter", loader);
        Class<?> tileType = XposedHelpers.findClass("com.android.systemui.qs.pipeline.shared.TileSpec$CustomTileSpec", loader);
        Class<?> customTile = XposedHelpers.findClass("com.android.systemui.qs.external.CustomTile", loader);
        XposedHelpers.findAndHookMethod(adapter, "addTile", ComponentName.class, boolean.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!TILE.equals(p.args[0])) return;
                try {
                    String spec = (String) XposedHelpers.callStaticMethod(customTile, "toSpec", TILE);
                    Object current = XposedHelpers.callMethod(p.thisObject, "getSpecs");
                    if (!(current instanceof List<?> specs)) throw new IllegalStateException();
                    if (specs.contains(spec)) { p.setResult(null); return; }
                    int editIndex = specs.indexOf("edit");
                    if (editIndex < 0) { p.args[1] = true; return; }
                    Object tile = XposedHelpers.newInstance(tileType, TILE, spec);
                    Object interactor = XposedHelpers.getObjectField(p.thisObject, "interactor");
                    XposedHelpers.callMethod(interactor, "addTile", tile, editIndex);
                    p.setResult(null);
                } catch (Throwable error) {
                    // The supported API's end position is safer than its default front position.
                    p.args[1] = true;
                    HookSupport.error("磁贴末尾位置", error);
                }
            }
        });
        installed = true;
    }
}
