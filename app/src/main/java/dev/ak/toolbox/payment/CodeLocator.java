package dev.ak.toolbox.payment;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.DetectorResult;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.detector.Detector;

/** Finds the shapes, without decoding or returning either code's contents. */
public final class CodeLocator {
    public record Box(int left, int top, int right, int bottom) { }
    public record Pair(Box qr, Box barcode) { }
    private CodeLocator() { }

    public static Box locateQr(int width, int height, int[] pixels) {
        try {
            BitMatrix bits = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width, height, pixels))).getBlackMatrix();
            DetectorResult detection = new Detector(bits).detect(null);
            ResultPoint[] finder = detection.getPoints();
            if (finder == null || finder.length < 3) return null;
            float minX = width, minY = height, maxX = 0, maxY = 0;
            for (int i = 0; i < 3; i++) {
                ResultPoint p = finder[i];
                minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
                minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
            }
            float module = (maxX - minX + maxY - minY) / (2 * Math.max(1, detection.getBits().getWidth() - 7));
            int pad = Math.max(8, Math.round(module * 7));
            return new Box(Math.max(0, Math.round(minX) - pad), Math.max(0, Math.round(minY) - pad),
                Math.min(width, Math.round(maxX) + pad), Math.min(height, Math.round(maxY) + pad));
        } catch (Throwable ignored) { return null; }
    }

    public static Pair locate(int width, int height, int[] pixels) throws Exception {
        BitMatrix bits = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width, height, pixels))).getBlackMatrix();
        DetectorResult detection = new Detector(bits).detect(null);
        ResultPoint[] finder = detection.getPoints();
        if (finder.length < 3) return null;
        float minX = width, minY = height, maxX = 0, maxY = 0;
        for (int i = 0; i < 3; i++) {
            ResultPoint p = finder[i];
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
        }
        float module = (maxX - minX + maxY - minY) / (2 * Math.max(1, detection.getBits().getWidth() - 7));
        int pad = Math.max(8, Math.round(module * 7));
        Box qr = new Box(Math.max(0, Math.round(minX) - pad), Math.max(0, Math.round(minY) - pad),
            Math.min(width, Math.round(maxX) + pad), Math.min(height, Math.round(maxY) + pad));
        int start = -1, bestStart = -1, bestEnd = -1, left = width, right = 0;
        int bestLeft = 0, bestRight = 0;
        long runHash = 0;
        int[] positions = new int[width];
        for (int y = 0; y <= height; y++) {
            int changes = 0, first = width, last = 0;
            long hash = 0xcbf29ce484222325L;
            boolean previous = false;
            if (y < height && (y < qr.top() || y >= qr.bottom())) {
                for (int x = 0; x < width; x++) {
                    boolean black = bits.get(x, y);
                    if (x > 0 && black != previous) positions[changes++] = x;
                    previous = black;
                }
            }
            int cluster = 0, bestCluster = 0, bestCount = 0;
            for (int i = 1; i <= changes; i++) {
                if (i == changes || positions[i] - positions[i - 1] > Math.max(16, width / 20)) {
                    if (i - cluster > bestCount) { bestCluster = cluster; bestCount = i - cluster; }
                    cluster = i;
                }
            }
            if (bestCount > 0) {
                first = positions[bestCluster]; last = positions[bestCluster + bestCount - 1];
                for (int i = bestCluster; i < bestCluster + bestCount; i++) hash = (hash ^ positions[i]) * 0x100000001b3L;
            }
            boolean valid = bestCount >= 32 && last - first >= width / 4;
            if (start >= 0 && (!valid || hash != runHash)) {
                if (y - start > bestEnd - bestStart) { bestStart = start; bestEnd = y; bestLeft = left; bestRight = right; }
                start = -1;
            }
            if (valid && start < 0) { start = y; left = first; right = last; runHash = hash; }
        }
        if (bestStart < 0 || bestEnd - bestStart < Math.max(20, height / 100)) return null;
        int quiet = Math.max(12, width / 50);
        return new Pair(qr, new Box(Math.max(0, bestLeft - quiet), Math.max(0, bestStart - quiet),
            Math.min(width, bestRight + quiet), Math.min(height, bestEnd + quiet)));
    }
}
