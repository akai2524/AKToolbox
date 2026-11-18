package dev.ak.toolbox.payment;

/** Geometry for the rear screen; all coordinates are inside the safe area. */
public final class PaymentLayout {
    public record Box(int left, int top, int right, int bottom) {
        public int width() { return right - left; }
        public int height() { return bottom - top; }
    }
    public record Layout(Box header, Box card, Box barcode, Box qr, boolean sideHeader) { }
    private PaymentLayout() { }

    public static Layout calculate(int width, int height, int barcodeWidth, int barcodeHeight) {
        if (width < 120 || height < 160 || barcodeWidth <= 0 || barcodeHeight <= 0) return null;
        int margin = Math.max(5, Math.min(19, Math.min(width, height) / 30));
        int gap = Math.max(4, Math.min(10, margin / 2));
        int cornerInset = Math.max(8, Math.min(34, width / 18));
        int headerTop = Math.max(6, Math.min(24, height / 24));
        int headerHeight = Math.max(20, Math.min(60, height / 9));
        Box header = new Box(margin + 6, headerTop, width - margin - cornerInset, headerTop + headerHeight);
        Box card = new Box(margin, header.bottom() + gap, width - margin - cornerInset, height - margin);
        int cardHeight = card.height(), cardWidth = card.width();
        if (cardHeight < 70 || cardWidth < 60 || header.width() < 20) return null;
        int pad = Math.max(6, Math.min(24, cardWidth / 24));
        int barSlotHeight = Math.max(24, Math.min(100, cardHeight / 5));
        int barPad = Math.max(6, Math.min(10, barSlotHeight / 10));
        int contentWidth = card.width() - 2 * pad, contentHeight = barSlotHeight - 2 * barPad;
        if (contentWidth <= 0 || contentHeight <= 0) return null;
        // A 1D code carries information horizontally. Use the full available width
        // while fitting its height independently so narrow bars remain scannable.
        int bw = contentWidth;
        int bh = Math.max(1, Math.min(contentHeight,
            (int) Math.floor((double) barcodeHeight * contentWidth / barcodeWidth)));
        int bx = card.left() + (card.width() - bw) / 2;
        int by = card.top() + pad + (barSlotHeight - bh) / 2;
        Box bar = new Box(bx, by, bx + bw, by + bh);
        int qrTop = Math.max(bar.bottom() + 6,
            card.top() + pad + barSlotHeight - Math.min(14, barPad + gap));
        int bottomPad = Math.max(6, Math.min(8, pad));
        int qrSize = Math.min(contentWidth, card.bottom() - bottomPad - qrTop);
        if (qrSize < 24) return null;
        int qrLeft = card.left() + (card.width() - qrSize) / 2;
        Box qr = new Box(qrLeft, qrTop, qrLeft + qrSize, qrTop + qrSize);
        return new Layout(header, card, bar, qr, false);
    }

    public static Layout calculateTransit(int width, int height) {
        if (width < 120 || height < 160) return null;
        int margin = Math.max(5, Math.min(19, Math.min(width, height) / 30));
        int gap = Math.max(4, Math.min(10, margin / 2));
        int headerTop = Math.max(6, Math.min(24, height / 24));
        int headerHeight = Math.max(20, Math.min(60, height / 9));
        int cardTop = headerTop + headerHeight + gap;
        int cardBottom = height - margin;
        int cardHeight = cardBottom - cardTop;
        if (cardHeight < 70) return null;
        int cornerInset = Math.max(8, Math.min(34, width / 18));
        int cardLeft = margin + 6;
        int maxCardWidth = width - margin - cornerInset - cardLeft;
        if (maxCardWidth < 60) return null;
        int cardWidth = Math.min(cardHeight + Math.max(4, cardHeight / 20), maxCardWidth);
        int cardRight = cardLeft + cardWidth;
        Box header = new Box(cardLeft, headerTop, cardRight, headerTop + headerHeight);
        Box card = new Box(cardLeft, cardTop, cardRight, cardBottom);
        int pad = Math.max(8, Math.min(24, cardHeight / 16));
        int qrSize = Math.min(cardWidth - 2 * pad, cardHeight - 2 * pad);
        if (qrSize < 24) return null;
        int qrLeft = cardLeft + (cardWidth - qrSize) / 2;
        int qrTop = cardTop + (cardHeight - qrSize) / 2;
        Box qr = new Box(qrLeft, qrTop, qrLeft + qrSize, qrTop + qrSize);
        return new Layout(header, card, null, qr, false);
    }
}
