package vn.karaoke.youtube;

/**
 * Hand-rolled QR Code encoder — pure Java, no library, no Android dependency.
 * Byte mode, error-correction level M, versions 1-6 (covers URLs up to ~100
 * bytes; a Pinggy link is ~35 chars, a LAN URL ~25). Structure follows the
 * ISO/IEC 18004 algorithm (finder/timing/alignment patterns, Reed-Solomon
 * error correction over GF(256), zig-zag data placement, mask selection by
 * penalty score, BCH format information).
 *
 * This replaces the hand-rolled QrCode encoder of the original module and
 * avoids bundling a QR library (ZXing core is ~600 KB) just to draw one code.
 */
public class QrMatrix {

    // version: {size, totalCodewords, ecCodewordsPerBlock, numBlocks, dataCodewords, remainderBits}
    // + alignment pattern centres (without the leading 6, which is implicit)
    private static final int[][] VERSIONS = {
        {21, 26, 10, 1, 16, 0},
        {25, 44, 16, 1, 28, 7},
        {29, 70, 26, 1, 44, 7},
        {33, 100, 18, 2, 64, 7},
        {37, 134, 24, 2, 86, 7},
        {41, 172, 16, 4, 108, 7},
    };
    private static final int[][] ALIGN_CENTERS = {
        {},
        {18},
        {22},
        {26},
        {30},
        {34},
    };

    /** Encode text to a boolean matrix (true = dark module). Quiet zone NOT included. */
    public static boolean[][] encode(String text) {
        boolean[][] best = null;
        int bestPenalty = Integer.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            boolean[][] m = encodeWithMask(text, mask);
            int pen = penalty(m);
            if (pen < bestPenalty) { bestPenalty = pen; best = m; }
        }
        return best;
    }

    /** Test/debug accessor: interleaved data+ECC codewords for the text. */
    static byte[] codewordsForTest(String text) {
        byte[] data;
        try { data = text.getBytes("UTF-8"); } catch (Exception e) { data = text.getBytes(); }
        int version = -1;
        for (int v = 0; v < VERSIONS.length; v++) {
            int capacityBits = VERSIONS[v][4] * 8;
            int needBits = 4 + 8 + data.length * 8 + 4;
            if (needBits <= capacityBits) { version = v + 1; break; }
        }
        int dataCodewords = VERSIONS[version - 1][4];
        BitBuffer bb = new BitBuffer();
        bb.appendBits(0x4, 4);
        bb.appendBits(data.length, 8);
        for (byte b : data) bb.appendBits(b & 0xFF, 8);
        int capacityBits = dataCodewords * 8;
        bb.appendBits(0, Math.min(4, capacityBits - bb.size()));
        while (bb.size() % 8 != 0) bb.appendBits(0, 1);
        int pad = 0xEC;
        while (bb.size() < capacityBits) { bb.appendBits(pad, 8); pad = (pad == 0xEC) ? 0x11 : 0xEC; }
        byte[] dataBytes = bb.toBytes();
        int ecLen = VERSIONS[version - 1][2];
        int numBlocks = VERSIONS[version - 1][3];
        int totalCodewords = VERSIONS[version - 1][1];
        int shortBlockLen = dataCodewords / numBlocks;
        int numShortBlocks = numBlocks - (dataCodewords % numBlocks);
        byte[][] blocks = new byte[numBlocks][];
        int off = 0;
        for (int i = 0; i < numBlocks; i++) {
            int len = shortBlockLen + (i < numShortBlocks ? 0 : 1);
            byte[] block = new byte[len];
            System.arraycopy(dataBytes, off, block, 0, len);
            off += len;
            blocks[i] = block;
        }
        byte[][] ecc = new byte[numBlocks][];
        for (int i = 0; i < numBlocks; i++) ecc[i] = reedSolomonComputeRemainder(blocks[i], ecLen);
        byte[] result = new byte[totalCodewords];
        int p = 0;
        int maxDataLen = shortBlockLen + 1;
        for (int i = 0; i < maxDataLen; i++)
            for (int j = 0; j < numBlocks; j++) {
                int len = shortBlockLen + (j < numShortBlocks ? 0 : 1);
                if (i < len) result[p++] = blocks[j][i];
            }
        for (int i = 0; i < ecLen; i++)
            for (int j = 0; j < numBlocks; j++) result[p++] = ecc[j][i];
        return result;
    }

    /** Encode with a fixed mask (used for testing / debugging). */
    public static boolean[][] encodeWithMask(String text, int mask) {
        byte[] data;
        try { data = text.getBytes("UTF-8"); } catch (Exception e) { data = text.getBytes(); }
        int version = -1;
        for (int v = 0; v < VERSIONS.length; v++) {
            int capacityBits = VERSIONS[v][4] * 8;
            int needBits = 4 + 8 + data.length * 8 + 4;
            if (needBits <= capacityBits) { version = v + 1; break; }
        }
        if (version < 0) throw new IllegalArgumentException("text too long for QR v6-M");
        int size = VERSIONS[version - 1][0];
        int dataCodewords = VERSIONS[version - 1][4];
        BitBuffer bb = new BitBuffer();
        bb.appendBits(0x4, 4);
        bb.appendBits(data.length, 8);
        for (byte b : data) bb.appendBits(b & 0xFF, 8);
        int capacityBits = dataCodewords * 8;
        int terminator = Math.min(4, capacityBits - bb.size());
        bb.appendBits(0, terminator);
        while (bb.size() % 8 != 0) bb.appendBits(0, 1);
        int pad = 0xEC;
        while (bb.size() < capacityBits) { bb.appendBits(pad, 8); pad = (pad == 0xEC) ? 0x11 : 0xEC; }
        byte[] dataBytes = bb.toBytes();
        int ecLen = VERSIONS[version - 1][2];
        int numBlocks = VERSIONS[version - 1][3];
        int totalCodewords = VERSIONS[version - 1][1];
        int shortBlockLen = dataCodewords / numBlocks;
        int numShortBlocks = numBlocks - (dataCodewords % numBlocks);
        byte[][] blocks = new byte[numBlocks][];
        int off = 0;
        for (int i = 0; i < numBlocks; i++) {
            int len = shortBlockLen + (i < numShortBlocks ? 0 : 1);
            byte[] block = new byte[len];
            System.arraycopy(dataBytes, off, block, 0, len);
            off += len;
            blocks[i] = block;
        }
        byte[][] ecc = new byte[numBlocks][];
        for (int i = 0; i < numBlocks; i++) ecc[i] = reedSolomonComputeRemainder(blocks[i], ecLen);
        byte[] result = new byte[totalCodewords];
        int p = 0;
        int maxDataLen = shortBlockLen + 1;
        for (int i = 0; i < maxDataLen; i++)
            for (int j = 0; j < numBlocks; j++) {
                int len = shortBlockLen + (j < numShortBlocks ? 0 : 1);
                if (i < len) result[p++] = blocks[j][i];
            }
        for (int i = 0; i < ecLen; i++)
            for (int j = 0; j < numBlocks; j++) result[p++] = ecc[j][i];
        BitBuffer finalBits = new BitBuffer();
        for (byte b : result) finalBits.appendBits(b & 0xFF, 8);
        finalBits.appendBits(0, VERSIONS[version - 1][5]);
        boolean[] dataBits = finalBits.toBooleans();
        return drawMatrix(version, size, dataBits, mask);
    }

    private static boolean[][] drawMatrix(int version, int size, boolean[] dataBits, int mask) {
        boolean[][] modules = new boolean[size][size];
        boolean[][] isFunction = new boolean[size][size];
        // timing patterns FIRST — finders drawn after overwrite the borders
        for (int i = 0; i < size; i++) {
            boolean v = (i % 2 == 0);
            setFunction(modules, isFunction, size, 6, i, v);
            setFunction(modules, isFunction, size, i, 6, v);
        }
        // finder patterns (7x7) + one-module separators on the inner sides only.
        // Top-left at (0,0), top-right at (size-7,0), bottom-left at (0,size-7).
        drawFinder(modules, isFunction, size, 0, 0);
        drawFinder(modules, isFunction, size, size - 7, 0);
        drawFinder(modules, isFunction, size, 0, size - 7);
        // separators (light, but reserved as function modules)
        for (int i = 0; i <= 7; i++) {
            setFunction(modules, isFunction, size, 7, i, false);            // TL right
            setFunction(modules, isFunction, size, i, 7, false);            // TL bottom
            setFunction(modules, isFunction, size, size - 8, i, false);     // TR left
            setFunction(modules, isFunction, size, size - 1 - i, 7, false); // TR bottom
            setFunction(modules, isFunction, size, 7, size - 1 - i, false); // BL right
            setFunction(modules, isFunction, size, i, size - 8, false);     // BL top
        }
        // alignment patterns
        int[] centers = ALIGN_CENTERS[version - 1];
        int[] all = new int[centers.length + 1];
        all[0] = 6;
        System.arraycopy(centers, 0, all, 1, centers.length);
        for (int a : all) for (int b : all) {
            // skip the three finder corners
            if ((a == 6 && b == 6) || (a == 6 && b == size - 7) || (a == size - 7 && b == 6)) continue;
            for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) {
                boolean dark = Math.max(Math.abs(dx), Math.abs(dy)) != 1;
                setFunction(modules, isFunction, size, b + dx, a + dy, dark);
            }
        }
        // reserve format info areas
        for (int i = 0; i <= 5; i++) { markFunction(isFunction, size, 8, i); markFunction(isFunction, size, i, 8); }
        markFunction(isFunction, size, 8, 7); markFunction(isFunction, size, 8, 8); markFunction(isFunction, size, 7, 8);
        for (int i = 9; i < 15; i++) markFunction(isFunction, size, 14 - i, 8);
        for (int i = 0; i < 8; i++) markFunction(isFunction, size, size - 1 - i, 8);
        for (int i = 8; i < 15; i++) markFunction(isFunction, size, 8, size - 15 + i);
        // dark module
        setFunction(modules, isFunction, size, size - 8, 8, true);

        // data bits, zig-zag from bottom-right, skipping column 6
        int bitIndex = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y][x] && bitIndex < dataBits.length) {
                        boolean bit = dataBits[bitIndex++];
                        if (maskBit(mask, x, y)) bit = !bit;
                        modules[y][x] = bit;
                    }
                }
            }
        }
        // format information (EC level M = format bits 00).
        // NOTE: modules[row][col]; the two copies are NOT symmetric —
        // bits 0-5 go down column 8, bits 9-14 go along row 8 (reversed).
        int format = formatBits(0, mask);
        for (int i = 0; i <= 5; i++) modules[i][8] = getBit(format, i);
        modules[7][8] = getBit(format, 6);
        modules[8][8] = getBit(format, 7);
        modules[8][7] = getBit(format, 8);
        for (int i = 9; i < 15; i++) modules[8][14 - i] = getBit(format, i);
        for (int i = 0; i < 8; i++) modules[8][size - 1 - i] = getBit(format, i);
        for (int i = 8; i < 15; i++) modules[size - 15 + i][8] = getBit(format, i);
        modules[8][size - 8] = true; // dark module
        return modules;
    }

    private static void drawFinder(boolean[][] m, boolean[][] f, int size, int x0, int y0) {
        for (int dy = 0; dy < 7; dy++)
            for (int dx = 0; dx < 7; dx++) {
                boolean dark = (dy == 0 || dy == 6 || dx == 0 || dx == 6)
                        || (dy >= 2 && dy <= 4 && dx >= 2 && dx <= 4);
                setFunction(m, f, size, x0 + dx, y0 + dy, dark);
            }
    }

    private static void setFunction(boolean[][] m, boolean[][] f, int size, int x, int y, boolean v) {
        if (x < 0 || x >= size || y < 0 || y >= size) return;
        m[y][x] = v;
        f[y][x] = true;
    }
    private static void markFunction(boolean[][] f, int size, int x, int y) {
        if (x < 0 || x >= size || y < 0 || y >= size) return;
        f[y][x] = true;
    }

    private static boolean maskBit(int mask, int x, int y) {
        switch (mask) {
            case 0: return (x + y) % 2 == 0;
            case 1: return y % 2 == 0;
            case 2: return x % 3 == 0;
            case 3: return (x + y) % 3 == 0;
            case 4: return (x / 3 + y / 2) % 2 == 0;
            case 5: return (x * y) % 2 + (x * y) % 3 == 0;
            case 6: return ((x * y) % 2 + (x * y) % 3) % 2 == 0;
            case 7: return ((x + y) % 2 + (x * y) % 3) % 2 == 0;
            default: return false;
        }
    }

    /** BCH format information: EC level M has format bits 00. */
    private static int formatBits(int eclFormatBits, int mask) {
        int data = (eclFormatBits << 3) | mask;
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >> 9) * 0x537);
        return ((data << 10) | rem) ^ 0x5412;
    }
    private static boolean getBit(int x, int i) { return ((x >>> i) & 1) != 0; }

    // ---- Reed-Solomon over GF(256), generator poly x^8+x^4+x^3+x^2+1 (0x11D) ----
    private static final int[] EXP = new int[512];
    private static final int[] LOG = new int[256];
    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = x;
            LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= 0x11D;
        }
        for (int i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
    }
    private static int gfMul(int a, int b) {
        if (a == 0 || b == 0) return 0;
        return EXP[LOG[a] + LOG[b]];
    }
    private static byte[] reedSolomonComputeRemainder(byte[] data, int degree) {
        // Generator polynomial (x - r^0)(x - r^1)...(x - r^{degree-1}), leading
        // term (always 1) dropped — coefficients stored lowest-first in `divisor`.
        byte[] divisor = new byte[degree];
        divisor[divisor.length - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < divisor.length; j++) {
                divisor[j] = (byte) gfMul(divisor[j] & 0xFF, root);
                if (j + 1 < divisor.length) divisor[j] ^= divisor[j + 1];
            }
            root = gfMul(root, 2);
        }
        byte[] result = new byte[degree];
        for (byte b : data) {
            int factor = (b & 0xFF) ^ (result[0] & 0xFF);
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++)
                result[i] ^= (byte) gfMul(divisor[i] & 0xFF, factor);
        }
        return result;
    }

    // ---- penalty score (ISO 18004 rules 1-4), lower is better ----
    private static int penalty(boolean[][] m) {
        int size = m.length;
        int result = 0;
        // Rule 1: runs of 5+ same colour in rows and columns
        for (int y = 0; y < size; y++) {
            int runColor = -1, runLen = 0;
            for (int x = 0; x < size; x++) {
                int c = m[y][x] ? 1 : 0;
                if (c == runColor) runLen++;
                else { if (runLen >= 5) result += 3 + (runLen - 5); runColor = c; runLen = 1; }
            }
            if (runLen >= 5) result += 3 + (runLen - 5);
        }
        for (int x = 0; x < size; x++) {
            int runColor = -1, runLen = 0;
            for (int y = 0; y < size; y++) {
                int c = m[y][x] ? 1 : 0;
                if (c == runColor) runLen++;
                else { if (runLen >= 5) result += 3 + (runLen - 5); runColor = c; runLen = 1; }
            }
            if (runLen >= 5) result += 3 + (runLen - 5);
        }
        // Rule 2: 2x2 blocks of the same colour
        for (int y = 0; y < size - 1; y++)
            for (int x = 0; x < size - 1; x++) {
                boolean c = m[y][x];
                if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) result += 3;
            }
        // Rule 3: finder-like patterns 10111010000 / 00001011101
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                if (x + 10 < size && finderLike(m, y, x, 0, 1)) result += 40;
                if (y + 10 < size && finderLike(m, y, x, 1, 0)) result += 40;
            }
        // Rule 4: dark module proportion
        int dark = 0;
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) if (m[y][x]) dark++;
        int total = size * size;
        int k = (int) (Math.abs(dark * 20L - total * 10L) + total - 1) / total - 1;
        result += k * 10;
        return result;
    }
    private static boolean finderLike(boolean[][] m, int y, int x, int dy, int dx) {
        // pattern bits: 1 0 1 1 1 0 1 0 0 0 0  (or the reverse)
        int[] pat = {1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0};
        boolean fwd = true, rev = true;
        for (int i = 0; i < 11; i++) {
            int bit = m[y + i * dy][x + i * dx] ? 1 : 0;
            if (bit != pat[i]) fwd = false;
            if (bit != pat[10 - i]) rev = false;
        }
        return fwd || rev;
    }

    private static class BitBuffer {
        private final java.util.ArrayList<Boolean> bits = new java.util.ArrayList<>();
        void appendBits(int val, int len) {
            for (int i = len - 1; i >= 0; i--) bits.add(((val >>> i) & 1) != 0);
        }
        int size() { return bits.size(); }
        byte[] toBytes() {
            byte[] out = new byte[(bits.size() + 7) / 8];
            for (int i = 0; i < bits.size(); i++)
                if (bits.get(i)) out[i / 8] |= (byte) (1 << (7 - (i % 8)));
            return out;
        }
        boolean[] toBooleans() {
            boolean[] out = new boolean[bits.size()];
            for (int i = 0; i < bits.size(); i++) out[i] = bits.get(i);
            return out;
        }
    }
}
