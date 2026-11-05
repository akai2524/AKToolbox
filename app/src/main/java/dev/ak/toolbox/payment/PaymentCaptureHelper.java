package dev.ak.toolbox.payment;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.os.IBinder;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Root child process. Code images are transferred through its private pipe only. */
public final class PaymentCaptureHelper {
    static final int FRAME_MAGIC = 0x50415931;
    private static final long MAIN_DISPLAY = 4630946949513469331L;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private PaymentCaptureHelper() { }

    // This entry point runs as a separately launched root app_process on the tested Android 17 build.
    @SuppressLint("BlockedPrivateApi")
    public static void main(String[] args) {
        AtomicBoolean running = new AtomicBoolean(true);
        Thread lease = new Thread(() -> {
            try { while (System.in.read() != -1) { /* only EOF is meaningful */ } }
            catch (Exception ignored) { }
            running.set(false);
            // EOF must also stop a writer currently blocked on a full pipe.
            android.os.Process.killProcess(android.os.Process.myPid());
        }, "payment-capture-lease");
        lease.setDaemon(true);
        lease.start();
        try {
            if (args.length != 1) return;
            long uid = Long.parseLong(args[0]);
            if (uid < 10000) return;
            ClassLoader loader = new dalvik.system.PathClassLoader("/system/framework/services.jar", ClassLoader.getSystemClassLoader());
            Class<?> displayControl = loader.loadClass("com.android.server.display.DisplayControl");
            Method load = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
            load.setAccessible(true);
            load.invoke(Runtime.getRuntime(), displayControl, "android_servers");
            long[] ids = (long[]) displayControl.getMethod("getPhysicalDisplayIds").invoke(null);
            if (Arrays.stream(ids).noneMatch(id -> id == MAIN_DISPLAY)) return;
            IBinder token = (IBinder) displayControl.getMethod("getPhysicalDisplayToken", long.class).invoke(null, MAIN_DISPLAY);
            if (token == null) return;
            Class<?> builderType = Class.forName("android.window.ScreenCaptureInternal$DisplayCaptureArgs$Builder");
            Class<?> argsType = Class.forName("android.window.ScreenCaptureInternal$DisplayCaptureArgs");
            Class<?> captureType = Class.forName("android.window.ScreenCaptureInternal");
            Object builder = builderType.getConstructor(IBinder.class).newInstance(token);
            builderType.getMethod("setSecureContentPolicy", int.class).invoke(builder, 1);
            builderType.getMethod("setUid", long.class).invoke(builder, uid);
            Object captureArgs = builderType.getMethod("build").invoke(builder);
            Method capture = captureType.getMethod("captureDisplay", argsType);
            DataOutputStream output = new DataOutputStream(System.out);
            while (running.get()) {
                Object result = capture.invoke(null, captureArgs);
                if (result == null || !(Boolean) result.getClass().getMethod("containsSecureLayers").invoke(result)) {
                    output.writeInt(0); output.flush(); Thread.sleep(300); continue;
                }
                Bitmap buffer = (Bitmap) result.getClass().getMethod("asBitmap").invoke(result);
                if (buffer == null) { output.writeInt(0); output.flush(); Thread.sleep(300); continue; }
                Bitmap image = buffer.copy(Bitmap.Config.ARGB_8888, false);
                if (image == null) { buffer.recycle(); output.writeInt(0); output.flush(); continue; }
                try {
                    int width = image.getWidth(), height = image.getHeight();
                    int[] pixels = new int[Math.multiplyExact(width, height)];
                    image.getPixels(pixels, 0, width, 0, 0, width, height);
                    CodeLocator.Pair pair = null;
                    try { pair = CodeLocator.locate(width, height, pixels); }
                    catch (Exception ignored) { }
                    finally { Arrays.fill(pixels, 0); }
                    if (!running.get()) break;
                    if (pair == null) { output.writeInt(0); output.flush(); Thread.sleep(300); continue; }
                    byte[] qr = encode(image, pair.qr()), barcode = encode(image, pair.barcode());
                    try {
                        if (!running.get()) break;
                        output.writeInt(FRAME_MAGIC);
                        output.writeInt(qr.length); output.write(qr);
                        output.writeInt(barcode.length); output.write(barcode);
                        output.flush();
                    } finally { Arrays.fill(qr, (byte) 0); Arrays.fill(barcode, (byte) 0); }
                } finally { image.recycle(); buffer.recycle(); }
                Thread.sleep(350);
            }
        } catch (Throwable error) {
            Throwable cause = error.getCause();
            Log.w("AKRearPayment", "capture helper stopped: " + (cause == null ? error.getClass().getSimpleName() : cause.getClass().getSimpleName()));
            // A missing API, root denial, closed pipe or capture error ends the session.
        } finally {
            // app_process has no application lifecycle to shut down RenderThread cleanly.
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    private static byte[] encode(Bitmap image, CodeLocator.Box box) throws Exception {
        if (box.right() <= box.left() || box.bottom() <= box.top()) throw new IllegalStateException();
        Bitmap crop = Bitmap.createBitmap(image, box.left(), box.top(), box.right() - box.left(), box.bottom() - box.top());
        try {
            WipingBuffer output = new WipingBuffer();
            try {
                if (!crop.compress(Bitmap.CompressFormat.PNG, 100, output) || output.size() > MAX_BYTES) throw new IllegalStateException();
                return output.toByteArray();
            } finally { output.wipe(); }
        } finally { crop.recycle(); }
    }
    private static final class WipingBuffer extends ByteArrayOutputStream {
        void wipe() { Arrays.fill(buf, (byte) 0); reset(); }
    }
}
