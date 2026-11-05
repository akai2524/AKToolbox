package dev.ak.toolbox.payment;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

/** Debug-only visual comparison. The symbols contain fixed synthetic values. */
public final class RearStaticPatternActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setShowWhenLocked(false); setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        if (getDisplay() == null || getDisplay().getDisplayId() != 1) {
            WindowManager.LayoutParams p = getWindow().getAttributes(); p.alpha = 0; getWindow().setAttributes(p);
        }
        setContentView(new View(this) {
            final PaymentRenderer renderer = new PaymentRenderer();
            Bitmap qr, bar;
            @Override protected void onDraw(Canvas canvas) {
                WindowInsets insets = getRootWindowInsets();
                DisplayCutout cutout = insets == null ? null : insets.getDisplayCutout();
                Rect display = getWindowManager().getMaximumWindowMetrics().getBounds();
                int[] origin = new int[2]; getLocationOnScreen(origin);
                int left = Math.max(0, display.left + (cutout == null ? 0 : cutout.getSafeInsetLeft()) - origin[0]);
                int right = Math.min(getWidth(), display.right - (cutout == null ? 0 : cutout.getSafeInsetRight()) - origin[0]);
                int top = Math.max(0, display.top + (cutout == null ? 0 : cutout.getSafeInsetTop()) - origin[1]);
                int bottom = Math.min(getHeight(), display.bottom - (cutout == null ? 0 : cutout.getSafeInsetBottom()) - origin[1]);
                int width = right - left, height = bottom - top;
                if (width < 120 || height < 160) return;
                try {
                    if (qr == null) qr = code(BarcodeFormat.QR_CODE, "synthetic-not-payment", 400, 400);
                    if (bar == null) bar = code(BarcodeFormat.CODE_128, "123456789012345678", 560, 90);
                    String label = getIntent().getStringExtra("theme");
                    renderer.draw(canvas, left, top, right, bottom, qr, bar,
                        "wechat".equals(label) ? "微信付款码" : "alipay".equals(label) ? "支付宝付款码" : "测试图形");
                    postInvalidateDelayed(500);
                } catch (Exception error) { throw new IllegalStateException(error); }
            }
            Bitmap code(BarcodeFormat format, String value, int width, int height) throws Exception {
                BitMatrix bits = new MultiFormatWriter().encode(value, format, width, height);
                int[] pixels = new int[width * height];
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) pixels[y * width + x] = bits.get(x, y) ? Color.BLACK : Color.WHITE;
                return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
            }
        });
    }
}
