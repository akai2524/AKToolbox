import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.NotFoundException;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.DecodeHintType;
import java.util.EnumMap;
import java.util.List;
import dev.ak.toolbox.payment.CodeLocator;
import dev.ak.toolbox.payment.PaymentLayout;
import dev.ak.toolbox.payment.PaymentPalette;
import java.util.Arrays;

/** All test codes contain synthetic data and are never used for a transaction. */
public final class PaymentCodeChecks {
    private static void draw(int[] image, int width, BitMatrix matrix, int x0, int y0) {
        for (int y = 0; y < matrix.getHeight(); y++) for (int x = 0; x < matrix.getWidth(); x++)
            if (matrix.get(x, y)) image[(y + y0) * width + x + x0] = 0xff000000;
    }
    private static String scan(int[] image, int width, CodeLocator.Box box, int targetWidth, int targetHeight) throws Exception {
        int[] result = new int[targetWidth * targetHeight];
        for (int y = 0; y < targetHeight; y++) for (int x = 0; x < targetWidth; x++) {
            int sourceX = box.left() + x * (box.right() - box.left()) / targetWidth;
            int sourceY = box.top() + y * (box.bottom() - box.top()) / targetHeight;
            result[y * targetWidth + x] = image[sourceY * width + sourceX];
        }
        BinaryBitmap binary = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(targetWidth, targetHeight, result)));
        return new MultiFormatReader().decode(binary).getText();
    }
    private static void fill(int[] target, int width, PaymentLayout.Box box, int color) {
        for (int y = box.top(); y < box.bottom(); y++)
            Arrays.fill(target, y * width + box.left(), y * width + box.right(), color);
    }
    private static void copyScaled(int[] source, int sourceWidth, CodeLocator.Box src,
            int[] target, int targetWidth, PaymentLayout.Box dst, int tint) {
        for (int y = 0; y < dst.height(); y++) for (int x = 0; x < dst.width(); x++) {
            int sx = src.left() + x * (src.right() - src.left()) / dst.width();
            int sy = src.top() + y * (src.bottom() - src.top()) / dst.height();
            target[(dst.top() + y) * targetWidth + dst.left() + x] = PaymentPalette.tintPixel(source[sy * sourceWidth + sx], tint);
        }
    }
    private static String decodeFormat(int[] image, int width, int height, BarcodeFormat format) throws Exception {
        EnumMap<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, List.of(format));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        return new MultiFormatReader().decode(new BinaryBitmap(
            new HybridBinarizer(new RGBLuminanceSource(width, height, image))), hints).getText();
    }
    public static void main(String[] args) throws Exception {
        int w = 720, h = 1400;
        int[] pixels = new int[w * h]; Arrays.fill(pixels, 0xffffffff);
        MultiFormatWriter writer = new MultiFormatWriter();
        draw(pixels, w, writer.encode("synthetic-qr-example", BarcodeFormat.QR_CODE, 360, 360), 180, 300);
        draw(pixels, w, writer.encode("123456789012345678", BarcodeFormat.CODE_128, 480, 130), 120, 800);
        for (int y = 800; y < 930; y++) { pixels[y * w + 5] = 0xff000000; pixels[y * w + w - 6] = 0xff000000; }
        CodeLocator.Pair pair = CodeLocator.locate(w, h, pixels);
        if (pair == null || pair.qr().left() > 200 || pair.qr().right() < 520
                || pair.barcode().top() > 805 || pair.barcode().bottom() < 925
                || pair.barcode().left() < 60 || pair.barcode().right() > 660)
            throw new AssertionError("合成双码区域未正确识别：" + pair);
        int safeWidth = 608, safeHeight = 572;
        int barWidth = pair.barcode().right() - pair.barcode().left(), barHeight = pair.barcode().bottom() - pair.barcode().top();
        PaymentLayout.Layout layout = PaymentLayout.calculate(safeWidth, safeHeight, barWidth, barHeight);
        if (layout == null) throw new AssertionError("背屏布局为空");
        if (!layout.sideHeader()) throw new AssertionError("背屏未采用侧边标识布局");
        if (safeWidth - layout.card().right() < 50 || layout.header().right() > layout.card().left())
            throw new AssertionError("背屏右侧圆角安全距离不足或标题重叠");
        int displayedBarWidth = layout.barcode().width(), displayedBarHeight = layout.barcode().height();
        int qrSize = layout.qr().width();
        if (layout.barcode().left() - layout.card().left() < 6
                || layout.card().right() - layout.barcode().right() < 6
                || layout.qr().left() - layout.card().left() < 6
                || layout.card().right() - layout.qr().right() < 6
                || layout.barcode().bottom() + 6 > layout.qr().top()
                || layout.header().right() > safeWidth - 24
                || layout.header().top() < 12)
            throw new AssertionError("码区留白或间距不足");
        if (!"synthetic-qr-example".equals(scan(pixels, w, pair.qr(), qrSize, qrSize)))
            throw new AssertionError("背屏尺寸下合成二维码不可读取");
        if (!"123456789012345678".equals(scan(pixels, w, pair.barcode(), displayedBarWidth, displayedBarHeight)))
            throw new AssertionError("背屏尺寸下合成条形码不可读取");
        int[] themes = {0xff078941, 0xff1670ea};
        int[] tints = {PaymentPalette.WECHAT, PaymentPalette.ALIPAY};
        for (int i = 0; i < themes.length; i++) {
            int[] composed = new int[safeWidth * safeHeight];
            Arrays.fill(composed, themes[i]);
            fill(composed, safeWidth, layout.card(), tints[i]);
            copyScaled(pixels, w, pair.qr(), composed, safeWidth, layout.qr(), tints[i]);
            copyScaled(pixels, w, pair.barcode(), composed, safeWidth, layout.barcode(), tints[i]);
            if (!"synthetic-qr-example".equals(decodeFormat(composed, safeWidth, safeHeight, BarcodeFormat.QR_CODE)))
                throw new AssertionError("主题背景下二维码不可读取");
            if (!"123456789012345678".equals(decodeFormat(composed, safeWidth, safeHeight, BarcodeFormat.CODE_128)))
                throw new AssertionError("主题背景下条形码不可读取");
        }
        if (PaymentPalette.tintPixel(0xff000000, PaymentPalette.WECHAT) != 0xff000000
                || PaymentPalette.tintPixel(0xffffffff, PaymentPalette.WECHAT) != PaymentPalette.WECHAT
                || PaymentPalette.tintPixel(0xffffffff, PaymentPalette.ALIPAY) != PaymentPalette.ALIPAY)
            throw new AssertionError("主题色转换改变了码线或白底");
        for (int[] size : new int[][] {{572, 904}, {904, 572}, {320, 480}, {120, 160}}) {
            PaymentLayout.Layout other = PaymentLayout.calculate(size[0], size[1], barWidth, barHeight);
            if (other == null || other.card().right() > size[0] || other.card().bottom() > size[1]
                    || other.barcode().bottom() > other.qr().top() || other.qr().bottom() > other.card().bottom())
                throw new AssertionError("尺寸 " + size[0] + "x" + size[1] + " 布局越界");
        }
        Arrays.fill(pixels, 0xffffffff);
        draw(pixels, w, writer.encode("synthetic-qr-only", BarcodeFormat.QR_CODE, 360, 360), 180, 300);
        if (CodeLocator.locate(w, h, pixels) != null) throw new AssertionError("缺少条形码时不应通过");
        Arrays.fill(pixels, 0xffffffff);
        try { CodeLocator.locate(w, h, pixels); throw new AssertionError("空白页面不应通过"); }
        catch (NotFoundException expected) { }
        System.out.println("合成双码定位与背屏尺寸可读性通过，缺码和空白页面拒绝。");
    }
}
