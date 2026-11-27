package vn.karaoke.youtube;

import android.graphics.Bitmap;
import java.io.ByteArrayOutputStream;

/** QR codes via the hand-rolled QrMatrix encoder (no QR library bundled). */
public class QrCode {
    public static Bitmap bitmap(String text, int size) {
        try {
            boolean[][] m = QrMatrix.encode(text);
            int n = m.length;
            int quiet = 2;
            int total = n + quiet * 2;
            int scale = Math.max(1, size / total);
            int px = total * scale;
            Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            bmp.eraseColor(0xFFFFFFFF);
            for (int y = 0; y < n; y++)
                for (int x = 0; x < n; x++) {
                    if (!m[y][x]) continue;
                    for (int dy = 0; dy < scale; dy++)
                        for (int dx = 0; dx < scale; dx++)
                            bmp.setPixel((x + quiet) * scale + dx, (y + quiet) * scale + dy, 0xFF000000);
                }
            return bmp;
        } catch (Exception e) { return null; }
    }

    public static byte[] png(String text, int size) {
        Bitmap bmp = bitmap(text, size);
        if (bmp == null) return null;
        try {
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
            return os.toByteArray();
        } catch (Exception e) { return null; }
    }
}
