package dev.ak.toolbox.payment;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;

/** Shared by the secure payment surface and synthetic preview. */
public final class PaymentRenderer {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

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
        paint.setTextSize(Math.max(16, Math.min(50, layout.header().height() * .90f)));
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = (layout.header().top() + layout.header().bottom() - metrics.ascent - metrics.descent) / 2f;
        canvas.drawText(wechat ? "微信支付" : alipay ? "支付宝" : "测试图形", layout.header().left(), baseline, paint);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setTextSize(Math.max(11, Math.min(18, layout.header().height() * .48f)));
        metrics = paint.getFontMetrics();
        baseline = (layout.header().top() + layout.header().bottom() - metrics.ascent - metrics.descent) / 2f;
        canvas.drawText(wechat || alipay ? "付款码" : "合成测试", layout.header().right(), baseline, paint);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setColor(Color.WHITE);
        float radius = Math.max(6, Math.min(18, Math.min(width, height) / 32f));
        rounded(canvas, layout.card(), radius);
        paint.setFilterBitmap(false);
        paint.setDither(false);
        paint.setColorFilter(null);
        canvas.drawBitmap(barcode, null, rect(layout.barcode()), paint);
        Rect qrBounds = rect(layout.qr());
        if (wechat) {
            // Preserve the 0.1.20 size and move it up without overlapping the barcode.
            int size = Math.round(qrBounds.width() * .90f * .85f);
            qrBounds.left += (qrBounds.width() - size) / 2;
            qrBounds.right = qrBounds.left + size;
            qrBounds.top = qrBounds.bottom - size;
            int upward = Math.min(40, Math.max(0, qrBounds.top - layout.barcode().bottom() - 6));
            qrBounds.offset(0, -upward);
        }
        canvas.drawBitmap(qr, null, qrBounds, paint);
        paint.setColorFilter(null);
        canvas.restore();
    }

    public void drawTransit(Canvas canvas, int left, int top, int right, int bottom,
            Bitmap qr, String label) {
        if (qr == null) return;
        int width = right - left, height = bottom - top;
        PaymentLayout.Layout layout = PaymentLayout.calculateTransit(width, height);
        if (layout == null) return;
        boolean wechat = "微信乘车码".equals(label);
        canvas.drawColor(wechat ? Color.rgb(7, 137, 65) : Color.rgb(22, 112, 234));
        canvas.save();
        canvas.translate(left, top);
        paint.setColorFilter(null);
        paint.setColor(Color.WHITE);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTextSize(Math.max(16, Math.min(46, layout.header().height() * .80f)));
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = (layout.header().top() + layout.header().bottom() - metrics.ascent - metrics.descent) / 2f;
        canvas.drawText(wechat ? "微信乘车码" : "支付宝乘车码", layout.header().left(), baseline, paint);
        paint.setColor(Color.WHITE);
        float radius = Math.max(6, Math.min(24, Math.min(width, height) / 28f));
        rounded(canvas, layout.card(), radius);
        paint.setFilterBitmap(false);
        paint.setDither(false);
        paint.setColorFilter(null);
        canvas.drawBitmap(qr, null, rect(layout.qr()), paint);
        canvas.restore();
    }

    private void rounded(Canvas canvas, PaymentLayout.Box box, float radius) {
        canvas.drawRoundRect(box.left(), box.top(), box.right(), box.bottom(), radius, radius, paint);
    }
    private static Rect rect(PaymentLayout.Box box) {
        return new Rect(box.left(), box.top(), box.right(), box.bottom());
    }
}
