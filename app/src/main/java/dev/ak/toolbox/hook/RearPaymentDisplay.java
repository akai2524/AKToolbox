package dev.ak.toolbox.hook;

import android.app.Application;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import dev.ak.toolbox.Options;
import dev.ak.toolbox.payment.PaymentRenderer;
import dev.ak.toolbox.payment.PaymentEventGate;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.util.Arrays;

/** Renders short-lived payment views inside the trusted rear-screen process. */
final class RearPaymentDisplay {
    private static final String TAG = "AKRearPayment";
    private static final String REAR = "com.xiaomi.subscreencenter";
    private static final String WX = "com.tencent.mm";
    private static final String ALI = "com.eg.android.AlipayGphone";
    private static final long MAX_FRAME_MS = 1500;
    private static final int MAX_IMAGE_BYTES = 650_000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final PaymentEventGate EVENTS = new PaymentEventGate();
    private static final Runnable EXPIRE = RearPaymentDisplay::remove;
    private static boolean installed;
    private static long lastEvent;
    private static String owner;
    private static Bitmap qr, barcode;
    private static WindowManager manager;
    private static View overlay;

    private RearPaymentDisplay() { }

    static void install() {
        XposedHelpers.findAndHookMethod(Instrumentation.class, "callApplicationOnCreate", Application.class,
            new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Application app = (Application) param.args[0];
                    if (installed || !REAR.equals(app.getPackageName())) return;
                    installed = true;
                    app.registerReceiver(new BroadcastReceiver() {
                        @Override public void onReceive(Context context, Intent intent) {
                            receive(context, intent, getSentFromUid());
                        }
                    }, new IntentFilter(RearFrameSender.ACTION), Context.RECEIVER_EXPORTED);
                    app.registerReceiver(new BroadcastReceiver() {
                        @Override public void onReceive(Context context, Intent intent) { remove(); }
                    }, new IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED);
                }
            });
    }

    private static void receive(Context context, Intent intent, int senderUid) {
        byte[] firstBytes = null, secondBytes = null;
        try {
            String source = intent.getStringExtra("owner");
            if (!WX.equals(source) && !ALI.equals(source)) return;
            if (context.getPackageManager().getPackageUid(source, 0) != senderUid) return;
            long event = intent.getLongExtra("eventAt", -1), now = SystemClock.elapsedRealtimeNanos();
            if (!EVENTS.accept(senderUid, event, now) || event <= lastEvent
                    || now - event >= MAX_FRAME_MS * 1_000_000L) return;
            lastEvent = event;
            String command = intent.getStringExtra("command");
            if ("stop".equals(command)) { if (source.equals(owner)) remove(); return; }
            if (!"frame".equals(command) || !allowed(context)) { remove(); return; }
            firstBytes = intent.getByteArrayExtra("qr"); secondBytes = intent.getByteArrayExtra("bar");
            if (firstBytes == null || secondBytes == null || firstBytes.length + secondBytes.length > MAX_IMAGE_BYTES) {
                remove(); return;
            }
            Bitmap first = null, second = null;
            try {
                first = decode(firstBytes); second = decode(secondBytes);
                if (first == null || second == null) { remove(); return; }
                long age = (now - event) / 1_000_000L;
                show(context, source, first, second, Math.max(1, MAX_FRAME_MS - age));
                first = null; second = null;
            } finally { recycle(first); recycle(second); }
        } catch (Throwable error) {
            remove();
        } finally {
            if (firstBytes != null) Arrays.fill(firstBytes, (byte) 0);
            if (secondBytes != null) Arrays.fill(secondBytes, (byte) 0);
        }
    }

    private static boolean allowed(Context context) {
        try {
            KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
            return Options.enabled(context, Options.REAR_PAYMENT) && keyguard != null && !keyguard.isKeyguardLocked();
        } catch (Throwable ignored) { return false; }
    }

    private static Bitmap decode(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1 || bounds.outWidth > 1000 || bounds.outHeight > 1000) return null;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }

    private static void show(Context context, String source, Bitmap first, Bitmap second, long lifetime) {
        if (overlay == null) {
            try {
                Display display = context.getSystemService(DisplayManager.class).getDisplay(1);
                if (display == null || display.getCutout() == null) { recycle(first); recycle(second); return; }
                Context rear = context.createDisplayContext(display);
                manager = rear.getSystemService(WindowManager.class);
                View content = new View(rear) {
                    final PaymentRenderer renderer = new PaymentRenderer();
                    @Override protected void onDraw(Canvas canvas) {
                        if (qr == null || barcode == null) return;
                        boolean ali = ALI.equals(owner);
                        canvas.drawColor(ali ? Color.rgb(22, 112, 234) : Color.rgb(7, 137, 65));
                        Display current = getDisplay();
                        DisplayCutout cutout = current == null ? null : current.getCutout();
                        if (cutout == null) return;
                        int left = cutout.getSafeInsetLeft(), top = cutout.getSafeInsetTop();
                        int right = getWidth() - cutout.getSafeInsetRight();
                        int bottom = getHeight() - cutout.getSafeInsetBottom();
                        if (right - left >= 120 && bottom - top >= 160)
                            renderer.draw(canvas, left, top, right, bottom, qr, barcode, ali ? "支付宝付款码" : "微信付款码");
                    }
                };
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_SECURE
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, PixelFormat.OPAQUE);
                params.gravity = Gravity.TOP | Gravity.LEFT;
                params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                params.setFitInsetsTypes(0);
                params.setTitle("AKRearPayment");
                XposedHelpers.callMethod(params, "setTrustedOverlay");
                manager.addView(content, params);
                overlay = content;
                wakeRear();
            } catch (Throwable error) {
                recycle(first); recycle(second); remove();
                Log.w(TAG, "rear window unavailable=" + error.getClass().getSimpleName());
                return;
            }
        }
        Bitmap oldQr = qr, oldBarcode = barcode;
        owner = source; qr = first; barcode = second;
        overlay.invalidate();
        recycleLater(oldQr); recycleLater(oldBarcode);
        MAIN.removeCallbacks(EXPIRE);
        MAIN.postDelayed(EXPIRE, lifetime);
    }

    private static void wakeRear() {
        try {
            Class<?> type = Class.forName("android.hardware.input.InputManagerGlobal");
            Object input = XposedHelpers.callStaticMethod(type, "getInstance");
            long now = SystemClock.uptimeMillis();
            KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_WAKEUP, 0);
            KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_WAKEUP, 0);
            XposedHelpers.callMethod(down, "setDisplayId", 1);
            XposedHelpers.callMethod(up, "setDisplayId", 1);
            XposedHelpers.callMethod(input, "injectInputEvent", down, 0);
            XposedHelpers.callMethod(input, "injectInputEvent", up, 0);
        } catch (Throwable error) { Log.w(TAG, "rear wake unavailable=" + error.getClass().getSimpleName()); }
    }

    private static void remove() {
        MAIN.removeCallbacks(EXPIRE);
        if (overlay != null && manager != null) {
            try { manager.removeViewImmediate(overlay); } catch (RuntimeException ignored) { }
        }
        overlay = null; manager = null; owner = null;
        Bitmap first = qr, second = barcode; qr = null; barcode = null;
        recycleLater(first); recycleLater(second);
    }
    private static void recycle(Bitmap bitmap) { if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle(); }
    private static void recycleLater(Bitmap bitmap) { if (bitmap != null) MAIN.postDelayed(() -> recycle(bitmap), 1500); }
}
