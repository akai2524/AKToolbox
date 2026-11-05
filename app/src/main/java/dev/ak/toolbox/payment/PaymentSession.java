package dev.ak.toolbox.payment;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import dev.ak.toolbox.BackgroundPolicy;
import dev.ak.toolbox.Options;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Owns the private capture pipe and fails closed when its page or frames disappear. */
public final class PaymentSession {
    private static final String TAG = "AKRearPayment";
    private static final int MAX_IMAGE = 4 * 1024 * 1024;
    private static final long MAX_FRAME_AGE = 1800;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService STAGE = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Akai-rear-stage"); t.setDaemon(true); return t;
    });
    private static final Object LOCK = new Object();
    private static long generation;
    private static int owner = -1;
    private static Process capture;
    private static boolean registered, stageLaunching, suspended;
    private static Context appContext;
    private static Bitmap qr, barcode;
    private static volatile long frameAt;
    private static String label = "";
    private static WeakReference<RearPaymentActivity> stage = new WeakReference<>(null);
    private PaymentSession() { }

    public static void start(Context context, int uid, String pkg) {
        Context app = context.getApplicationContext();
        if (!Options.enabled(app, Options.REAR_PAYMENT)) { Log.i(TAG, "feature disabled"); return; }
        BackgroundPolicy.ensureInitialized(app, null);
        startInternal(app, uid, pkg);
    }

    static void startInternal(Context app, int uid, String pkg) {
        registerScreenOff(app);
        long token;
        Process old;
        synchronized (LOCK) {
            appContext = app;
            if (owner == uid) { suspended = false; return; }
            old = detachLocked();
            owner = uid;
            suspended = false;
            token = ++generation;
        }
        terminate(old);
        MAIN.post(() -> { if (current(token)) clearFrame(); });
        Thread worker = new Thread(() -> readFrames(app, uid, pkg, token), "Akai-payment-capture");
        worker.setDaemon(true);
        worker.start();
    }

    public static void stop(Context context, int uid) {
        long stopped;
        Process old;
        synchronized (LOCK) {
            if (uid != owner) return;
            old = detachLocked();
            stopped = ++generation;
        }
        terminate(old);
        MAIN.post(() -> { if (isStopped(stopped)) { closeDisplay(); releaseForeground(); } });
    }

    public static void suspend(int uid) {
        synchronized (LOCK) {
            if (uid != owner) return;
            suspended = true;
        }
        MAIN.post(PaymentSession::clearFrame);
    }

    public static void stopAll() {
        long stopped;
        Process old;
        synchronized (LOCK) { old = detachLocked(); stopped = ++generation; }
        terminate(old);
        MAIN.post(() -> { if (isStopped(stopped)) { closeDisplay(); releaseForeground(); } });
    }

    private static Process detachLocked() {
        owner = -1;
        Process old = capture;
        capture = null;
        return old;
    }

    private static void terminate(Process process) {
        if (process == null) return;
        Thread cleanup = new Thread(() -> {
            try { process.getOutputStream().close(); } catch (Exception ignored) { }
            process.destroy();
            try { process.getInputStream().close(); } catch (Exception ignored) { }
        }, "Akai-payment-stop");
        cleanup.setDaemon(true);
        cleanup.start();
    }

    private static boolean current(long token) {
        synchronized (LOCK) { return owner >= 0 && generation == token; }
    }
    private static boolean mayDisplay(long token) {
        synchronized (LOCK) { return owner >= 0 && generation == token && !suspended; }
    }
    private static boolean isStopped(long token) {
        synchronized (LOCK) { return owner < 0 && generation == token; }
    }

    private static void registerScreenOff(Context app) {
        synchronized (LOCK) {
            if (registered) return;
            app.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) { Log.i(TAG, "screen off; stopping session"); stopAll(); }
            }, new IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED);
            registered = true;
        }
    }

    private static void readFrames(Context context, int uid, String pkg, long token) {
        Process process = null;
        try {
            root("am start-foreground-service -n dev.ak.toolbox/.payment.PaymentCaptureService");
            for (int i = 0; i < 30 && current(token) && !PaymentCaptureService.isReady(); i++) Thread.sleep(100);
            if (!current(token) || !PaymentCaptureService.isReady()) return;
            // Prepare the transparent rear window while the first local frame is located.
            MAIN.post(() -> { if (mayDisplay(token)) ensureStage(context); });
            String source = context.getApplicationInfo().sourceDir;
            String quoted = "'" + source.replace("'", "'\\''") + "'";
            String command = "CLASSPATH=" + quoted + " app_process /system/bin " + PaymentCaptureHelper.class.getName() + " " + uid;
            process = new ProcessBuilder("su", "-c", command).start();
            Log.i(TAG, "capture process started");
            synchronized (LOCK) {
                if (!current(token)) { process.destroy(); return; }
                capture = process;
            }
            Process running = process;
            Thread errors = new Thread(() -> {
                try { byte[] ignored = new byte[1024]; while (running.getErrorStream().read(ignored) >= 0) { } }
                catch (Exception ignored) { }
            }, "Akai-payment-errors");
            errors.setDaemon(true); errors.start();
            DataInputStream input = new DataInputStream(process.getInputStream());
            int missing = 0;
            while (current(token)) {
                int magic = input.readInt();
                if (magic == 0) {
                    MAIN.post(() -> { if (current(token)) clearFrame(); });
                    if (++missing >= 8) break;
                    continue;
                }
                if (magic != PaymentCaptureHelper.FRAME_MAGIC) { Log.w(TAG, "invalid capture stream header"); break; }
                missing = 0;
                byte[] one = readImage(input), two = readImage(input);
                Bitmap first = null, second = null;
                try {
                    BitmapFactory.Options options = new BitmapFactory.Options(); options.inMutable = true;
                    first = BitmapFactory.decodeByteArray(one, 0, one.length, options);
                    second = BitmapFactory.decodeByteArray(two, 0, two.length, options);
                } finally { Arrays.fill(one, (byte) 0); Arrays.fill(two, (byte) 0); }
                if (first == null || second == null) { recycle(first); recycle(second); break; }
                Bitmap q = first, b = second;
                MAIN.post(() -> {
                    if (!mayDisplay(token)) { recycle(q); recycle(b); return; }
                    setFrame(context, token, pkg, q, b);
                });
            }
        } catch (Throwable error) {
            Log.w(TAG, "capture session stopped: " + error.getClass().getSimpleName());
            // Root denial, unsupported system APIs, invalid frames and a closed pipe hide the display.
        } finally {
            if (process != null) {
                try { process.getOutputStream().close(); } catch (Exception ignored) { }
                process.destroy();
            }
            long stopped = -1;
            synchronized (LOCK) {
                if (generation == token) { owner = -1; capture = null; stopped = ++generation; }
            }
            if (stopped >= 0) {
                long expected = stopped;
                MAIN.post(() -> { if (isStopped(expected)) { closeDisplay(); releaseForeground(); } });
            } else {
                MAIN.post(() -> { synchronized (LOCK) { if (owner >= 0) return; } releaseForeground(); });
            }
        }
    }

    private static void releaseForeground() {
        Context context = appContext;
        if (context != null) context.stopService(new Intent(context, PaymentCaptureService.class));
    }

    static void onForegroundStopped() {
        Log.i(TAG, "foreground service stopped");
        boolean active;
        synchronized (LOCK) { active = owner >= 0; }
        if (active) stopAll();
    }

    private static byte[] readImage(DataInputStream input) throws Exception {
        int count = input.readInt();
        if (count <= 0 || count > MAX_IMAGE) throw new IllegalStateException("invalid frame size");
        byte[] data = new byte[count]; input.readFully(data); return data;
    }

    private static void setFrame(Context context, long token, String pkg, Bitmap first, Bitmap second) {
        if (qr == null) Log.i(TAG, "first frame ready");
        recycleLater(qr); recycleLater(barcode);
        qr = first; barcode = second;
        label = "com.tencent.mm".equals(pkg) ? "微信付款码"
            : "com.eg.android.AlipayGphone".equals(pkg) ? "支付宝付款码" : "测试图形";
        frameAt = SystemClock.elapsedRealtime();
        RearPaymentActivity activity = stage.get();
        if (activity != null && !activity.isFinishing()) activity.frameChanged();
        else ensureStage(context);
        MAIN.postDelayed(() -> {
            if (current(token) && SystemClock.elapsedRealtime() - frameAt >= MAX_FRAME_AGE) clearFrame();
        }, MAX_FRAME_AGE + 50);
    }

    private static void clearFrame() {
        if (qr == null && barcode == null) return;
        if (qr != null) Log.i(TAG, "frame cleared");
        Bitmap first = qr, second = barcode;
        qr = null; barcode = null; frameAt = 0;
        RearPaymentActivity activity = stage.get();
        if (activity != null && !activity.isFinishing()) activity.frameChanged();
        recycleLater(first); recycleLater(second);
    }

    private static void closeDisplay() {
        clearFrame();
        RearPaymentActivity activity = stage.get();
        if (!stageLaunching && activity != null && !activity.isFinishing()) activity.finish();
    }

    static Bitmap qr() { return qr; }
    static Bitmap barcode() { return barcode; }
    static String label() { return label; }
    static boolean fresh() { return qr != null && barcode != null && SystemClock.elapsedRealtime() - frameAt < MAX_FRAME_AGE; }
    static void attach(RearPaymentActivity activity) { stage = new WeakReference<>(activity); activity.frameChanged(); }
    static void detach(RearPaymentActivity activity) { if (stage.get() == activity) stage.clear(); }
    private static void recycle(Bitmap bitmap) { if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle(); }
    private static void recycleLater(Bitmap bitmap) { if (bitmap != null) MAIN.postDelayed(() -> recycle(bitmap), 1500); }

    private static void ensureStage(Context context) {
        if (stageLaunching) return;
        stageLaunching = true;
        STAGE.execute(() -> {
            try {
                root("am start -W --display 1 -n dev.ak.toolbox/.payment.RearPaymentActivity");
                // Do not wake the rear screen until a fresh image is ready.
                for (int i = 0; i < 20 && frameAt == 0; i++) {
                    synchronized (LOCK) { if (owner < 0) return; }
                    Thread.sleep(100);
                }
                if (frameAt != 0) root("input -d 1 keyevent KEYCODE_WAKEUP");
            } catch (Throwable error) { Log.w(TAG, "rear stage failed: " + error.getClass().getSimpleName()); }
            finally { MAIN.post(() -> {
                stageLaunching = false;
                boolean stopped;
                synchronized (LOCK) { stopped = owner < 0; }
                if (stopped) closeDisplay();
            }); }
        });
    }

    private static String root(String command) throws Exception {
        Process p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        StringBuilder text = new StringBuilder();
        Thread drain = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) if (text.length() < 100000) text.append(line).append('\n');
            } catch (Exception ignored) { }
        }, "Akai-rear-output");
        drain.setDaemon(true); drain.start();
        if (!p.waitFor(3, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IllegalStateException(); }
        drain.join(500);
        if (p.exitValue() != 0) throw new IllegalStateException();
        return text.toString();
    }
}
