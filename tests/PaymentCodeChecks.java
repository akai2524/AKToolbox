import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.NotFoundException;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import dev.akai.toolbox.payment.CodeLocator;
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
        int safeWidth = 608, safeHeight = 572, margin = Math.max(8, Math.min(safeWidth, safeHeight) / 60);
        int barWidth = pair.barcode().right() - pair.barcode().left(), barHeight = pair.barcode().bottom() - pair.barcode().top();
        float barScale = Math.min((float) (safeWidth - margin * 2) / barWidth, safeHeight * 0.25f / barHeight);
        int displayedBarWidth = Math.round(barWidth * barScale), displayedBarHeight = Math.round(barHeight * barScale);
        int qrSize = Math.min(safeWidth - margin * 2, safeHeight - displayedBarHeight - Math.max(20, safeHeight / 24) - margin * 4);
        if (!"synthetic-qr-example".equals(scan(pixels, w, pair.qr(), qrSize, qrSize)))
            throw new AssertionError("背屏尺寸下合成二维码不可读取");
        if (!"123456789012345678".equals(scan(pixels, w, pair.barcode(), displayedBarWidth, displayedBarHeight)))
            throw new AssertionError("背屏尺寸下合成条形码不可读取");
        Arrays.fill(pixels, 0xffffffff);
        draw(pixels, w, writer.encode("synthetic-qr-only", BarcodeFormat.QR_CODE, 360, 360), 180, 300);
        if (CodeLocator.locate(w, h, pixels) != null) throw new AssertionError("缺少条形码时不应通过");
        Arrays.fill(pixels, 0xffffffff);
        try { CodeLocator.locate(w, h, pixels); throw new AssertionError("空白页面不应通过"); }
        catch (NotFoundException expected) { }
        System.out.println("合成双码定位与背屏尺寸可读性通过，缺码和空白页面拒绝。");
    }
}
