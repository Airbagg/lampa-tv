package local.vlcbridge;

/**
 * Акустический отпечаток для поиска общих кусков (заставка, титры) у серий.
 *
 * Вариант Haitsma–Kalker: звук моно 11025 Гц режем на окна 4096 отсчётов с
 * шагом 1365 (~0.124 с), в каждом окне считаем энергию 33 полос от 300 до
 * 2000 Гц (логарифмическая шкала), и 32 бита кадра — знаки разностей энергий
 * соседних полос во времени. Одинаковый звук даёт почти одинаковые биты даже
 * после разного сжатия и громкости; разный — случайные.
 *
 * Без Android-зависимостей: тот же код гоняется на маке для проверки.
 */
public final class AudioPrint {
    public static final int RATE = 11025;
    public static final int FRAME = 4096;
    public static final int HOP = 1365;
    public static final double FRAME_SEC = (double) HOP / RATE;
    private static final int BANDS = 33;
    private static final double F_MIN = 300, F_MAX = 2000;

    private final double[] window = new double[FRAME];
    private final int[] bandLo = new int[BANDS];
    private final int[] bandHi = new int[BANDS];
    private final double[] re = new double[FRAME];
    private final double[] im = new double[FRAME];
    private final double[] prev = new double[BANDS];
    private final double[] cur = new double[BANDS];
    private final float[] ring = new float[FRAME];
    private int filled;      // сколько отсчётов в ring
    private int sinceHop;    // отсчётов с последнего кадра
    private boolean havePrev;

    private int[] out = new int[1024];
    private int count;

    public AudioPrint() {
        for (int i = 0; i < FRAME; i++) window[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FRAME - 1));
        for (int b = 0; b <= BANDS; b++) {
            double f = F_MIN * Math.pow(F_MAX / F_MIN, (double) b / BANDS);
            int bin = (int) Math.round(f * FRAME / RATE);
            if (b < BANDS) bandLo[b] = bin;
            if (b > 0) bandHi[b - 1] = Math.max(bin, bandLo[b - 1] + 1);
        }
    }

    /** Подать моно-отсчёты 11025 Гц (float, -1..1). */
    public void feed(float[] pcm, int off, int len) {
        for (int i = 0; i < len; i++) {
            if (filled < FRAME) {
                ring[filled++] = pcm[off + i];
            } else {
                System.arraycopy(ring, 1, ring, 0, FRAME - 1);
                ring[FRAME - 1] = pcm[off + i];
            }
            if (filled == FRAME && ++sinceHop >= HOP) {
                sinceHop = 0;
                frame();
            }
        }
    }

    /** Отпечаток на сейчас: по int на каждые FRAME_SEC секунд. */
    public int[] result() {
        int[] r = new int[count];
        System.arraycopy(out, 0, r, 0, count);
        return r;
    }

    public int size() {
        return count;
    }

    private void frame() {
        for (int i = 0; i < FRAME; i++) {
            re[i] = ring[i] * window[i];
            im[i] = 0;
        }
        fft(re, im);
        for (int b = 0; b < BANDS; b++) {
            double e = 0;
            for (int k = bandLo[b]; k < bandHi[b]; k++) e += re[k] * re[k] + im[k] * im[k];
            cur[b] = e;
        }
        if (havePrev) {
            int bits = 0;
            for (int m = 0; m < 32; m++) {
                double d = (cur[m] - cur[m + 1]) - (prev[m] - prev[m + 1]);
                if (d > 0) bits |= 1 << m;
            }
            if (count == out.length) {
                int[] n = new int[out.length * 2];
                System.arraycopy(out, 0, n, 0, count);
                out = n;
            }
            out[count++] = bits;
        }
        System.arraycopy(cur, 0, prev, 0, BANDS);
        havePrev = true;
    }

    /** Классическое БПФ по основанию 2, на месте. */
    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    double xr = re[b] * cr - im[b] * ci;
                    double xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    double t = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = t;
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Сравнение

    /** Самый длинный общий кусок: длина в кадрах и начало в a и в b. */
    public static final class Match {
        public final int length, startA, startB;

        Match(int length, int startA, int startB) {
            this.length = length;
            this.startA = startA;
            this.startB = startB;
        }

        public double seconds() { return length * FRAME_SEC; }

        public double startSecA() { return startA * FRAME_SEC; }

        public double startSecB() { return startB * FRAME_SEC; }

        @Override
        public String toString() {
            return String.format(java.util.Locale.US, "%.1fs a@%.1f b@%.1f", seconds(), startSecA(), startSecB());
        }
    }

    /**
     * Ищем при всех сдвигах b относительно a (в пределах maxShift кадров)
     * самую длинную цепочку совпавших кадров (≤ maxBits различий), прощая
     * провалы до maxGap кадров.
     */
    public static Match bestCommon(int[] a, int[] b, int maxShift, int maxBits, int maxGap) {
        int bestLen = 0, bestA = 0, bestB = 0;
        for (int shift = -maxShift; shift <= maxShift; shift++) {
            int i0 = Math.max(0, -shift);
            int i1 = Math.min(a.length, b.length - shift);
            if (i1 - i0 <= bestLen) continue;
            int runStart = -1, last = -1;
            for (int i = i0; i < i1; i++) {
                if (Integer.bitCount(a[i] ^ b[i + shift]) <= maxBits) {
                    if (runStart < 0 || i - last > maxGap) runStart = i;
                    last = i;
                    int len = last - runStart + 1;
                    if (len > bestLen) {
                        bestLen = len;
                        bestA = runStart;
                        bestB = runStart + shift;
                    }
                }
            }
        }
        return new Match(bestLen, bestA, bestB);
    }
}
