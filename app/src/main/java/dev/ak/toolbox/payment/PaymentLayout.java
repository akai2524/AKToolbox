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
        int margin = Math.max(6, Math.min(width, height) / 55);
        int gap = Math.max(5, margin * 3 / 4);
        boolean sideHeader = width >= 260 && height >= 280;
        Box header, card;
        if (sideHeader) {
            int railWidth = Math.max(68, Math.min(140, width * 23 / 100));
            int originalLeft = margin + railWidth;
            // The physical rear screen has large right-side rounded corners. Move the
            // entire card inwards while retaining enough room for the source label.
            int cornerInset = Math.max(28, Math.min(50, Math.min(width, height) / 11));
            int minLeft = margin + 10 + 64 + gap;
            int cardLeft = Math.max(minLeft, originalLeft - (cornerInset - 4));
            int cardTop = margin + 4;
            card = new Box(cardLeft, cardTop, width - margin - cornerInset, height - margin - 4);
            header = new Box(margin + 10, margin + Math.max(28, Math.min(52, height / 12)),
                cardLeft - gap, card.bottom());
        } else {
            int headerHeight = Math.max(27, Math.min(48, height / 12));
            int rightInset = Math.max(20, Math.min(34, width / 18));
            header = new Box(margin + 8, margin + 6, width - margin - rightInset, margin + headerHeight);
            int cardTop = margin + headerHeight + gap;
            int cardHeight = height - margin - cardTop;
            int availableWidth = width - 2 * margin;
            int cardWidth = Math.min(availableWidth, Math.max(availableWidth * 84 / 100, cardHeight * 95 / 100));
            int cardLeft = (width - cardWidth) / 2;
            card = new Box(cardLeft, cardTop, cardLeft + cardWidth, height - margin);
        }
        int cardHeight = card.height(), cardWidth = card.width();
        if (cardHeight < 70 || cardWidth < 60 || header.width() < 20) return null;
        int pad = Math.max(8, Math.min(16, cardWidth / 28));
        int barSlotHeight = Math.max(30, Math.min(sideHeader ? 150 : 136,
            sideHeader ? cardHeight * 30 / 100 : cardHeight / 4));
        int barPad = Math.max(6, Math.min(10, barSlotHeight / 10));
        int contentWidth = card.width() - 2 * pad, contentHeight = barSlotHeight - 2 * barPad;
        if (contentWidth <= 0 || contentHeight <= 0) return null;
        float scale = Math.min((float) contentWidth / barcodeWidth, (float) contentHeight / barcodeHeight);
        int bw = Math.max(1, (int) Math.floor(barcodeWidth * scale));
        int bh = Math.max(1, (int) Math.floor(barcodeHeight * scale));
        int bx = card.left() + (card.width() - bw) / 2;
        int by = card.top() + pad + (barSlotHeight - bh) / 2;
        Box bar = new Box(bx, by, bx + bw, by + bh);
        int qrTop = card.top() + pad + barSlotHeight + gap;
        int qrSize = Math.min(contentWidth, card.bottom() - pad - qrTop);
        if (qrSize < 24) return null;
        int qrLeft = card.left() + (card.width() - qrSize) / 2;
        Box qr = new Box(qrLeft, qrTop, qrLeft + qrSize, qrTop + qrSize);
        return new Layout(header, card, bar, qr, sideHeader);
    }
}
