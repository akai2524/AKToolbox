package dev.ak.toolbox.payment;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;

/** Shared by the secure payment surface and synthetic preview. */
public final class PaymentRenderer {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final ColorFilter WECHAT_TINT = tint(PaymentPalette.WECHAT);
    private static final ColorFilter ALIPAY_TINT = tint(PaymentPalette.ALIPAY);

    public void draw(Canvas canvas, int left, int top, int right, int bottom,
            Bitmap qr, Bitmap barcode, String label) {
        int width = right - left, height = bottom - top;
        PaymentLayout.Layout layout = PaymentLayout.calculate(width, height, barcode.getWidth(), barcode.getHeight());
        if (layout == null) return;
        boolean wechat = "微信付款码".equals(label), alipay = "支付宝付款码".equals(label);
        canvas.drawColor(wechat ? Color.rgb(7, 137, 65) : alipay ? Color.rgb(22, 112, 234) : Color.rgb(53, 65, 81));
        canvas.save();
        canvas.translate(left, top);
        paint.setColorFilter(null);
        paint.setColor(Color.WHITE);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        paint.setTextAlign(Paint.Align.LEFT);
        if (layout.sideHeader()) {
            float titleSize = Math.max(14, Math.min(32, layout.header().width() / 2.1f));
            paint.setTextSize(titleSize);
            Paint.FontMetrics titleMetrics = paint.getFontMetrics();
            float first = layout.header().top() - titleMetrics.ascent;
            canvas.drawText(wechat ? "微信" : alipay ? "支付" : "测试", layout.header().left(), first, paint);
            canvas.drawText(wechat ? "支付" : alipay ? "宝" : "图形", layout.header().left(), first + titleSize + 5, paint);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            paint.setTextSize(Math.max(11, Math.min(18, titleSize * .53f)));
            canvas.drawText(wechat || alipay ? "付款码" : "合成测试", layout.header().left(), first + titleSize * 2 + 18, paint);
        } else {
            paint.setTextSize(Math.max(16, Math.min(29, layout.header().height() * .68f)));
            Paint.FontMetrics metrics = paint.getFontMetrics();
            float baseline = (layout.header().top() + layout.header().bottom() - metrics.ascent - metrics.descent) / 2f;
            canvas.drawText(wechat ? "微信支付" : alipay ? "支付宝" : "测试图形", layout.header().left(), baseline, paint);
        }
        paint.setColor(PaymentPalette.forLabel(label));
        float radius = Math.max(6, Math.min(18, Math.min(width, height) / 32f));
        rounded(canvas, layout.card(), radius);
        // Multiplication keeps black modules black while mapping the white background to the light card.
        paint.setFilterBitmap(false);
        paint.setDither(false);
        paint.setColorFilter(wechat ? WECHAT_TINT : alipay ? ALIPAY_TINT : null);
        canvas.drawBitmap(barcode, null, rect(layout.barcode()), paint);
        canvas.drawBitmap(qr, null, rect(layout.qr()), paint);
        paint.setColorFilter(null);
        canvas.restore();
    }

    private void rounded(Canvas canvas, PaymentLayout.Box box, float radius) {
        canvas.drawRoundRect(box.left(), box.top(), box.right(), box.bottom(), radius, radius, paint);
    }
    private static Rect rect(PaymentLayout.Box box) {
        return new Rect(box.left(), box.top(), box.right(), box.bottom());
    }
    private static ColorFilter tint(int color) {
        return new ColorMatrixColorFilter(new float[] {
            Color.red(color) / 255f, 0, 0, 0, 0,
            0, Color.green(color) / 255f, 0, 0, 0,
            0, 0, Color.blue(color) / 255f, 0, 0,
            0, 0, 0, 1, 0
        });
    }
}
