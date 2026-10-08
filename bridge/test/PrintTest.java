import local.vlcbridge.AudioPrint;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.BufferedInputStream;
import java.io.EOFException;

/** Проверка AudioPrint на маке: java PrintTest a.s16 b.s16 (моно, 11025 Гц, s16le). */
public class PrintTest {
    static int[] print(String path) throws Exception {
        AudioPrint ap = new AudioPrint();
        float[] buf = new float[8192];
        int n = 0;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(path), 1 << 16))) {
            while (true) {
                int lo = in.readUnsignedByte(), hi = in.readByte();
                buf[n++] = (short) ((hi << 8) | lo) / 32768f;
                if (n == buf.length) { ap.feed(buf, 0, n); n = 0; }
            }
        } catch (EOFException e) {
            ap.feed(buf, 0, n);
        }
        return ap.result();
    }

    public static void main(String[] a) throws Exception {
        long t0 = System.currentTimeMillis();
        int[] x = print(a[0]), y = print(a[1]);
        long t1 = System.currentTimeMillis();
        for (int bits : new int[]{6, 8, 10}) {
            AudioPrint.Match m = AudioPrint.bestCommon(x, y, Math.max(x.length, y.length) / 2, bits, 8);
            System.out.println("bits<=" + bits + ": " + m);
        }
        System.out.println("frames " + x.length + "/" + y.length + ", print " + (t1 - t0) + " ms, match "
                + (System.currentTimeMillis() - t1) + " ms");
    }
}
