package dev.ak.toolbox.payment;

/** Light, high-contrast card colors and the matching multiplicative bitmap tint. */
public final class PaymentPalette {
    public static final int WECHAT = 0xffe5f6eb;
    public static final int ALIPAY = 0xffe8f3ff;
    public static final int NEUTRAL = 0xffffffff;
    private PaymentPalette() { }

    public static int forLabel(String label) {
        return "微信付款码".equals(label) ? WECHAT : "支付宝付款码".equals(label) ? ALIPAY : NEUTRAL;
    }
    public static int tintPixel(int pixel, int tint) {
        int alpha = pixel & 0xff000000;
        int r = ((pixel >>> 16 & 255) * (tint >>> 16 & 255) + 127) / 255;
        int g = ((pixel >>> 8 & 255) * (tint >>> 8 & 255) + 127) / 255;
        int b = ((pixel & 255) * (tint & 255) + 127) / 255;
        return alpha | r << 16 | g << 8 | b;
    }
}
