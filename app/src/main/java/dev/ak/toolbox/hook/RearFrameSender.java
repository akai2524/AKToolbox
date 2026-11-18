package dev.ak.toolbox.hook;

import android.app.Activity;
import android.app.BroadcastOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Transfers visible code views directly from the payment app to the rear-screen process. */
public final class RearFrameSender {
    static final String ACTION = "dev.ak.toolbox.payment.REAR_FRAME";
    private static final long PERIOD = 400;
    private static final int MAX_BYTES = 650_000;
    private static final ExecutorService QUEUE = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AK-rear-wechat"); t.setDaemon(true); return t;
    });
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final AtomicLong GENERATION = new AtomicLong();
    private static long lastCapture;
    private RearFrameSender() { }

    public static void frame(Activity activity, View qr, View bar) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastCapture < PERIOD || !BUSY.compareAndSet(false, true)) return;
        lastCapture = now;
        Bitmap first = null, second = null;
        try {
            first = snapshot(qr); second = snapshot(bar);
            if (first == null || second == null) { recycle(first); recycle(second); BUSY.set(false); stop(activity); return; }
            Bitmap q = first, b = second;
            Context app = activity.getApplicationContext();
            long token = GENERATION.get(), event = SystemClock.elapsedRealtimeNanos();
            String owner = activity.getPackageName();
            if (!"com.tencent.mm".equals(owner) && !"com.eg.android.AlipayGphone".equals(owner)) {
                recycle(q); recycle(b); BUSY.set(false); return;
            }
            QUEUE.execute(() -> transmit(app, owner, token, event, q, b));
        } catch (Throwable ignored) { recycle(first); recycle(second); BUSY.set(false); stop(activity); }
    }

    public static void frameTransit(Activity activity, View qr) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastCapture < PERIOD || !BUSY.compareAndSet(false, true)) return;
        lastCapture = now;
        Bitmap first = null;
        try {
            first = snapshot(qr);
            if (first == null) { BUSY.set(false); stop(activity); return; }
            Bitmap q = first;
            Context app = activity.getApplicationContext();
            long token = GENERATION.get(), event = SystemClock.elapsedRealtimeNanos();
            String owner = activity.getPackageName();
            if (!"com.tencent.mm".equals(owner) && !"com.eg.android.AlipayGphone".equals(owner)) {
                recycle(q); BUSY.set(false); return;
            }
            QUEUE.execute(() -> transmitTransit(app, owner, token, event, q));
        } catch (Throwable ignored) { recycle(first); BUSY.set(false); stop(activity); }
    }

    public static void frameTransitBitmap(Activity activity, Bitmap qr) {
        if (qr == null) { stop(activity); return; }
        long now = SystemClock.elapsedRealtime();
        if (now - lastCapture < PERIOD || !BUSY.compareAndSet(false, true)) {
            recycle(qr);
            return;
        }
        lastCapture = now;
        try {
            Context app = activity.getApplicationContext();
            long token = GENERATION.get(), event = SystemClock.elapsedRealtimeNanos();
            String owner = activity.getPackageName();
            if (!"com.tencent.mm".equals(owner) && !"com.eg.android.AlipayGphone".equals(owner)) {
                recycle(qr); BUSY.set(false); return;
            }
            QUEUE.execute(() -> transmitTransit(app, owner, token, event, qr));
        } catch (Throwable ignored) { recycle(qr); BUSY.set(false); stop(activity); }
    }

    public static void stop(Activity activity) {
        GENERATION.incrementAndGet();
        Context app = activity.getApplicationContext();
        String owner = activity.getPackageName();
        long event = SystemClock.elapsedRealtimeNanos();
        QUEUE.execute(() -> {
            Intent intent = intent(owner, "stop", event);
            try { send(app, intent); } catch (Throwable ignored) { }
        });
    }

    private static Bitmap snapshot(View view) {
        if (view == null || !view.isAttachedToWindow() || !view.isShown()) return null;
        if (view instanceof ImageView image && image.getDrawable() == null) return null;
        int width = view.getWidth(), height = view.getHeight();
        if (width <= 0 || height <= 0 || width > 3000 || height > 3000) return null;
        float scale = Math.min(1f, 1000f / Math.max(width, height));
        int w = Math.max(1, Math.round(width * scale)), h = Math.max(1, Math.round(height * scale));
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap); canvas.drawColor(Color.WHITE); canvas.scale(scale, scale); view.draw(canvas);
            return bitmap;
        } catch (Throwable error) { bitmap.recycle(); return null; }
    }

    private static void transmit(Context app, String owner, long generation, long event, Bitmap qr, Bitmap bar) {
        byte[] first = null, second = null;
        try {
            if (generation != GENERATION.get()) return;
            first = encode(qr); second = encode(bar);
            if (first.length + second.length > MAX_BYTES || generation != GENERATION.get()) return;
            Intent intent = intent(owner, "frame", event).putExtra("codeType", "payment").putExtra("qr", first).putExtra("bar", second);
            send(app, intent);
        } catch (Throwable ignored) { }
        finally {
            if (first != null) Arrays.fill(first, (byte) 0);
            if (second != null) Arrays.fill(second, (byte) 0);
            recycle(qr); recycle(bar); BUSY.set(false);
        }
    }

    private static void transmitTransit(Context app, String owner, long generation, long event, Bitmap qr) {
        byte[] first = null;
        try {
            if (generation != GENERATION.get()) return;
            first = encode(qr);
            if (first.length > MAX_BYTES || generation != GENERATION.get()) return;
            Intent intent = intent(owner, "frame", event).putExtra("codeType", "transit").putExtra("qr", first);
            send(app, intent);
        } catch (Throwable ignored) { }
        finally {
            if (first != null) Arrays.fill(first, (byte) 0);
            recycle(qr); BUSY.set(false);
        }
    }

    private static byte[] encode(Bitmap bitmap) {
        WipingBuffer bytes = new WipingBuffer();
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) throw new IllegalStateException();
            return bytes.toByteArray();
        } finally { bytes.wipe(); }
    }
    private static Intent intent(String owner, String command, long event) {
        return new Intent(ACTION).setPackage("com.xiaomi.subscreencenter")
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND).putExtra("owner", owner)
            .putExtra("command", command).putExtra("eventAt", event);
    }
    private static void send(Context context, Intent intent) {
        context.sendBroadcast(intent, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }
    private static void recycle(Bitmap bitmap) { if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle(); }
    private static final class WipingBuffer extends ByteArrayOutputStream {
        void wipe() { Arrays.fill(buf, (byte) 0); reset(); }
    }
}
