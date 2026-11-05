package dev.ak.toolbox.probe;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;

/** Reports only keyboard window geometry and resource identifiers, never entered text. */
public final class ImeProbeInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            UiAutomation automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityServiceInfo info = automation.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            automation.setServiceInfo(info);
            SystemClock.sleep(500);
            List<AccessibilityWindowInfo> windows = automation.getWindows();
            StringBuilder report = new StringBuilder();
            int matches = 0;
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    matches++;
                    Rect bounds = new Rect();
                    window.getBoundsInScreen(bounds);
                    report.append("ime_window=").append(bounds.flattenToString()).append('\n');
                    collect(root, report, 0);
                }
                collectKnownLabels(root, report, 0);
            }
            result.putString("ime_report", "ime_windows=" + matches + "\n" + report);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("ime_error", error.getClass().getSimpleName());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void collectKnownLabels(AccessibilityNodeInfo node, StringBuilder report, int depth) {
        if (depth > 30 || report.length() > 20000) return;
        CharSequence value = node.getText();
        String text = value == null ? "" : value.toString();
        if (text.equals("微信输入法") || text.equals("讯飞输入法") || text.equals("搜狗输入法小米版")
            || text.equals("搜狗输入法（小米版）") || text.equals("搜狗输入法")
            || text.equals("小米输入法") || text.equals("键盘设置")) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            report.append("known_label=").append(text).append(" bounds=").append(bounds.flattenToString()).append('\n');
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectKnownLabels(child, report, depth + 1);
        }
    }

    private void collect(AccessibilityNodeInfo node, StringBuilder report, int depth) {
        if (depth > 30 || report.length() > 16000) return;
        String id = node.getViewIdResourceName();
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (id != null && (bounds.top >= 2000
            || id.matches("(?i).*(bottom|phrase|navigation|switch_input|input_frame).*"))) {
            report.append("id=").append(id).append(" bounds=").append(bounds.flattenToString()).append('\n');
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collect(child, report, depth + 1);
        }
    }
}
