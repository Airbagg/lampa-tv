package local.vlcbridge;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Сцены после титров в фильме — по времени реплик субтитров.
 *
 * mkvmerge кладёт в оглавление MKV (Cues) отметку на каждый блок субтитров.
 * Оглавление лежит в конце файла, и Лампа подгружает конец ещё при запуске,
 * так что сам фильм не читаем — только заголовок и Cues.
 *
 * Как выглядит конец фильма в репликах: последняя реплика фильма → долгая
 * пауза (титры) → короткая пачка реплик (сцена) → снова пауза или конец.
 * «Нет пути домой»: 2:14:02 конец → 2:17:11–2:19:49 бар с Веномом → 6 минут
 * титров → 2:25:57–2:27:59 тизер.
 *
 * Без Android-зависимостей — гоняется и на маке.
 */
public final class StingerFinder {
    /** Пауза в репликах, после которой идёт сцена. Секунды. */
    public static final class Stinger {
        public final double gapStart, sceneStart, sceneEnd;

        Stinger(double gapStart, double sceneStart, double sceneEnd) {
            this.gapStart = gapStart;
            this.sceneStart = sceneStart;
            this.sceneEnd = sceneEnd;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.US, "gap %.0f → scene %.0f–%.0f", gapStart, sceneStart, sceneEnd);
        }
    }

    static final double TAIL_SEC = 30 * 60;     // смотрим последние 30 минут
    static final double MIN_GAP = 120;          // титры — минимум 2 минуты без реплик
    static final double MAX_SCENE = 240;        // сцена после титров — до 4 минут реплик
    static final double FIRST_GAP_WITHIN = 25 * 60; // первые титры — в последних 25 минутах
    static final double BIN = 5;                // голосуем по 5-секундным корзинам

    private StingerFinder() {
    }

    // ------------------------------------------------------------------ поиск по временам

    /**
     * cues[track] — времена реплик (с) каждой полной дорожки субтитров.
     * Реплика «есть», если в корзине отметились хотя бы половина дорожек:
     * одна дорожка может обрезать сцену (у HDRezka её нет) или подписывать
     * песни в титрах.
     */
    public static List<Stinger> find(List<double[]> cues, double duration) {
        return find(cues, duration, new double[0]);
    }

    /**
     * evidence — моменты, где точно что-то происходит в кадре, хотя полные
     * субтитры молчат: строки SDH со звуками («[рычание]»), не про музыку.
     * Засчитываются как реплики без голосования — так видна немая сцена.
     */
    public static List<Stinger> find(List<double[]> cues, double duration, double[] evidence) {
        List<Stinger> res = new ArrayList<>();
        if (cues.isEmpty() || duration < 70 * 60) return res;
        int bins = (int) Math.ceil(duration / BIN) + 1;
        int[] votes = new int[bins];
        for (double[] track : cues) {
            boolean[] hit = new boolean[bins];
            for (double t : track) {
                int b = (int) (t / BIN);
                if (b >= 0 && b < bins) hit[b] = true;
            }
            for (int b = 0; b < bins; b++) if (hit[b]) votes[b]++;
        }
        int need = Math.max(1, cues.size() / 2);
        boolean[] forced = new boolean[bins];
        for (double t : evidence) {
            int b = (int) (t / BIN);
            if (b >= 0 && b < bins) forced[b] = true;
        }
        List<Double> speech = new ArrayList<>();
        for (int b = 0; b < bins; b++) {
            double t = b * BIN;
            if ((votes[b] >= need || forced[b]) && t >= duration - TAIL_SEC) speech.add(t);
        }
        if (speech.size() < 2) return res;

        // Пачки реплик, разделённые паузами ≥ MIN_GAP
        List<double[]> groups = new ArrayList<>();
        double gs = speech.get(0), ge = gs;
        for (int i = 1; i < speech.size(); i++) {
            double t = speech.get(i);
            if (t - ge >= MIN_GAP) {
                groups.add(new double[]{gs, ge + BIN});
                gs = t;
            }
            ge = t;
        }
        groups.add(new double[]{gs, ge + BIN});
        if (groups.size() < 2) return res;

        // Первая пауза должна быть в конце фильма, а всё после неё — короткие сцены:
        // длинная пачка реплик после «паузы» значит, что это была тихая сцена фильма
        if (groups.get(0)[1] < duration - FIRST_GAP_WITHIN) return res;
        for (int i = 1; i < groups.size(); i++) {
            double[] g = groups.get(i);
            if (g[1] - g[0] > MAX_SCENE) return new ArrayList<>();
            res.add(new Stinger(groups.get(i - 1)[1], g[0], g[1]));
        }
        return res;
    }

    // ------------------------------------------------------------------ чтение MKV

    private static final int ID_SEGMENT = 0x18538067, ID_SEEKHEAD = 0x114D9B74, ID_SEEK = 0x4DBB,
            ID_SEEKID = 0x53AB, ID_SEEKPOS = 0x53AC, ID_INFO = 0x1549A966, ID_TIMESCALE = 0x2AD7B1,
            ID_DURATION = 0x4489, ID_TRACKS = 0x1654AE6B, ID_TRACKENTRY = 0xAE, ID_TRACKNUM = 0xD7,
            ID_TRACKTYPE = 0x83, ID_FORCED = 0x55AA, ID_NAME = 0x536E, ID_CLUSTER = 0x1F43B675,
            ID_CUES = 0x1C53BB6B, ID_CUEPOINT = 0xBB, ID_CUETIME = 0xB3, ID_CUETRACKPOS = 0xB7, ID_CUETRACK = 0xF7,
            ID_CODEC = 0x86, ID_HEARING = 0x55AB, ID_CUECLUSTER = 0xF1, ID_CUERELPOS = 0xF0,
            ID_SIMPLEBLOCK = 0xA3, ID_BLOCKGROUP = 0xA0, ID_BLOCK = 0xA1;

    /** Строка SDH: где лежит её блок, чтобы прочитать текст. */
    private static final class SdhCue {
        final double time;
        final long cluster; // абсолютная позиция кластера
        final long rel;     // смещение блока от начала ДАННЫХ кластера
        final boolean ass;

        SdhCue(double time, long cluster, long rel, boolean ass) {
            this.time = time;
            this.cluster = cluster;
            this.rel = rel;
            this.ass = ass;
        }
    }

    /** Сцены после титров для MKV по адресу (Range-запросы к TorrServer). Пусто — не нашли. */
    public static List<Stinger> findInMkv(String url) throws Exception {
        byte[] head = fetch(url, 0, 1 << 20);
        int[] e = new int[3];
        int i = next(head, 0, head.length, e);          // EBML header
        i = next(head, e[1] + e[2], head.length, e);     // Segment
        if (e[0] != ID_SEGMENT) throw new IllegalStateException("not mkv");
        long segData = e[1];

        long timescale = 1_000_000;
        double duration = 0;
        long cuesPos = -1;
        List<Integer> subs = new ArrayList<>();
        java.util.Map<Integer, Boolean> sdh = new java.util.HashMap<>(); // дорожка → ASS?
        int p = (int) segData;
        while (p < head.length) {
            int end = next(head, p, head.length, e);
            if (end < 0) break;
            int id = e[0], d = e[1], s = e[2];
            if (id == ID_CLUSTER) break;
            if (id == ID_SEEKHEAD) {
                for (int q = d; q < d + s; ) {
                    int qe = next(head, q, d + s, e);
                    if (e[0] == ID_SEEK) {
                        int sd = e[1], ss = e[2];
                        long sid = 0, pos = -1;
                        for (int r = sd; r < sd + ss; ) {
                            int re = next(head, r, sd + ss, e);
                            if (e[0] == ID_SEEKID) sid = uint(head, e[1], e[2]);
                            else if (e[0] == ID_SEEKPOS) pos = uint(head, e[1], e[2]);
                            r = re;
                        }
                        if (sid == ID_CUES && pos >= 0) cuesPos = segData + pos;
                    }
                    q = qe;
                }
            } else if (id == ID_INFO) {
                for (int q = d; q < d + s; ) {
                    int qe = next(head, q, d + s, e);
                    if (e[0] == ID_TIMESCALE) timescale = uint(head, e[1], e[2]);
                    else if (e[0] == ID_DURATION) duration = e[2] == 4
                            ? Float.intBitsToFloat((int) uint(head, e[1], 4))
                            : Double.longBitsToDouble(uint(head, e[1], 8));
                    q = qe;
                }
            } else if (id == ID_TRACKS) {
                for (int q = d; q < d + s; ) {
                    int qe = next(head, q, d + s, e);
                    if (e[0] == ID_TRACKENTRY) {
                        int td = e[1], ts = e[2];
                        long num = -1, type = 0, forced = 0, hearing = 0;
                        String name = "", codec = "";
                        for (int r = td; r < td + ts; ) {
                            int re = next(head, r, td + ts, e);
                            if (e[0] == ID_TRACKNUM) num = uint(head, e[1], e[2]);
                            else if (e[0] == ID_TRACKTYPE) type = uint(head, e[1], e[2]);
                            else if (e[0] == ID_FORCED) forced = uint(head, e[1], e[2]);
                            else if (e[0] == ID_NAME) name = new String(head, e[1], e[2], "UTF-8").toLowerCase();
                            else if (e[0] == ID_CODEC) codec = new String(head, e[1], e[2], "US-ASCII");
                            else if (e[0] == ID_HEARING) hearing = uint(head, e[1], e[2]);
                            r = re;
                        }
                        // Только полные субтитры: «forced» подписывают лишь надписи
                        // Для глухих — отдельно: там подписана и музыка в титрах, в голосование
                        // их не берём, а текст в конце фильма читаем (PGS не прочитать — пропускаем)
                        boolean isSdh = hearing != 0 || SDH_NAME.matcher(name).find();
                        if (type == 0x11 && isSdh && codec.startsWith("S_TEXT/"))
                            sdh.put((int) num, codec.contains("ASS") || codec.contains("SSA"));
                        else if (type == 0x11 && !isSdh && forced == 0 && !name.contains("forced") && !name.contains("форс"))
                            subs.add((int) num);
                    }
                    q = qe;
                }
            }
            p = end;
        }
        double durSec = duration * timescale / 1e9;
        if (cuesPos < 0 || subs.isEmpty() || durSec <= 0) return new ArrayList<>();

        byte[] ch = fetch(url, cuesPos, 16);
        int end = next(ch, 0, ch.length, e);
        if (e[0] != ID_CUES) throw new IllegalStateException("no cues at " + cuesPos);
        int hdr = e[1];
        byte[] cues = fetch(url, cuesPos, hdr + e[2]);

        List<SdhCue> sdhCues = new ArrayList<>();
        List<List<Double>> perTrack = new ArrayList<>();
        for (int k = 0; k < subs.size(); k++) perTrack.add(new ArrayList<Double>());
        for (int q = hdr; q < cues.length; ) {
            int qe = next(cues, q, cues.length, e);
            if (qe < 0) break;
            if (e[0] == ID_CUEPOINT) {
                int cd = e[1], cs = e[2];
                double t = -1;
                List<long[]> tracks = new ArrayList<>(); // {дорожка, кластер, смещение в кластере}
                for (int r = cd; r < cd + cs; ) {
                    int re = next(cues, r, cd + cs, e);
                    if (e[0] == ID_CUETIME) t = uint(cues, e[1], e[2]) * timescale / 1e9;
                    else if (e[0] == ID_CUETRACKPOS) {
                        int pd = e[1], ps = e[2];
                        long[] tp = {-1, -1, -1};
                        for (int w = pd; w < pd + ps; ) {
                            int we = next(cues, w, pd + ps, e);
                            if (e[0] == ID_CUETRACK) tp[0] = uint(cues, e[1], e[2]);
                            else if (e[0] == ID_CUECLUSTER) tp[1] = uint(cues, e[1], e[2]);
                            else if (e[0] == ID_CUERELPOS) tp[2] = uint(cues, e[1], e[2]);
                            w = we;
                        }
                        tracks.add(tp);
                    }
                    r = re;
                }
                for (long[] tp : tracks) {
                    int k = subs.indexOf((int) tp[0]);
                    if (k >= 0 && t >= 0) perTrack.get(k).add(t);
                    Boolean ass = sdh.get((int) tp[0]);
                    if (ass != null && t >= durSec - TAIL_SEC && tp[1] >= 0 && tp[2] >= 0)
                        sdhCues.add(new SdhCue(t, segData + tp[1], tp[2], ass));
                }
            }
            q = qe;
        }
        List<double[]> tracks = new ArrayList<>();
        for (List<Double> l : perTrack) {
            if (l.size() < 200) continue; // почти пустая дорожка — не полные субтитры
            double[] a = new double[l.size()];
            for (int k = 0; k < a.length; k++) a[k] = l.get(k);
            Arrays.sort(a);
            tracks.add(a);
        }
        List<Stinger> plain = find(tracks, durSec);
        if (sdhCues.isEmpty()) return plain;

        // Титры начинаются там, где кончаются реплики фильма; SDH после этого места —
        // кандидаты в немую сцену. Читаем их текст (по несколько байт) и отбрасываем музыку.
        double creditsFrom = !plain.isEmpty() ? plain.get(0).gapStart : lastSpeech(tracks);
        List<Double> evidence = new ArrayList<>();
        int read = 0;
        for (SdhCue c : sdhCues) {
            if (c.time <= creditsFrom || read >= MAX_SDH_READS) continue;
            read++;
            try {
                String txt = blockText(url, c.cluster, c.rel, c.ass);
                if (txt != null && !txt.isEmpty() && !MUSIC.matcher(txt.toLowerCase()).find()) evidence.add(c.time);
            } catch (Exception ignored) {
            }
        }
        if (evidence.isEmpty()) return plain;
        double[] ev = new double[evidence.size()];
        for (int k = 0; k < ev.length; k++) ev[k] = evidence.get(k);
        return find(tracks, durSec, ev);
    }

    static final int MAX_SDH_READS = 60;
    static final java.util.regex.Pattern SDH_NAME = java.util.regex.Pattern.compile(
            "sdh|\\bcc\\b|hearing|глух|слабослыш|\\bhoh\\b");
    /** Строка SDH про музыку — это титры, а не сцена. */
    static final java.util.regex.Pattern MUSIC = java.util.regex.Pattern.compile(
            "♪|♫|music|song|melod|singing|instrumental|музык|песн|мелоди|поёт|поет|поют|играет|звучит");

    /** Сцен со словами после титров нет — значит, титры идут от последней реплики. */
    private static double lastSpeech(List<double[]> tracks) {
        double last = 0;
        for (double[] t : tracks) if (t.length > 0) last = Math.max(last, t[t.length - 1]);
        return last;
    }

    /**
     * Текст строки субтитров (SimpleBlock или BlockGroup→Block). CueRelativePosition
     * отсчитывается от начала данных кластера, поэтому сначала читаем его заголовок.
     */
    static String blockText(String url, long cluster, long rel, boolean ass) throws Exception {
        byte[] ch = fetch(url, cluster, 16);
        int[] e = new int[3];
        if (next(ch, 0, ch.length, e) < 0 || e[0] != ID_CLUSTER) return null;
        byte[] b = fetch(url, cluster + e[1] + rel, 4096);
        int end = next(b, 0, b.length, e);
        if (end < 0) return null;
        int d = e[1], s = e[2];
        if (e[0] == ID_BLOCKGROUP) {
            boolean found = false;
            for (int q = d; q < Math.min(d + s, b.length); ) {
                int qe = next(b, q, Math.min(d + s, b.length), e);
                if (qe < 0) break;
                if (e[0] == ID_BLOCK) {
                    d = e[1];
                    s = e[2];
                    found = true;
                    break;
                }
                q = qe;
            }
            if (!found) return null;
        } else if (e[0] != ID_SIMPLEBLOCK) {
            return null;
        }
        long[] v = new long[2];
        int n = vint(b, d, true, v);               // номер дорожки
        int payload = d + n + 3;                     // + int16 время + флаги
        int len = Math.min(s - n - 3, b.length - payload);
        if (len <= 0) return "";
        String txt = new String(b, payload, len, "UTF-8");
        if (ass) {
            // ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text
            int c = 0, i = 0;
            while (c < 8 && i < txt.length()) if (txt.charAt(i++) == ',') c++;
            txt = txt.substring(i);
        }
        return txt.replaceAll("\\{[^}]*\\}|<[^>]*>|\\\\N", " ").trim();
    }

    /** Элемент EBML с позиции i: out = {id, начало данных, размер}; возвращает конец элемента. */
    private static int next(byte[] b, int i, int limit, int[] out) {
        if (i >= limit || i >= b.length) return -1;
        long[] v = new long[2];
        int n1 = vint(b, i, false, v);
        long id = v[0];
        int n2 = vint(b, i + n1, true, v);
        long size = v[0];
        int data = i + n1 + n2;
        if (size == (1L << (7 * n2)) - 1) size = limit - data; // неизвестный размер
        out[0] = (int) id;
        out[1] = data;
        out[2] = (int) Math.min(size, Integer.MAX_VALUE);
        long end = data + size;
        return end > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) end;
    }

    private static int vint(byte[] b, int i, boolean strip, long[] out) {
        int first = b[i] & 0xff;
        int n = 1, mask = 0x80;
        while (n <= 8 && (first & mask) == 0) {
            n++;
            mask >>= 1;
        }
        long v = strip ? first & (mask - 1) : first;
        for (int k = 1; k < n; k++) v = (v << 8) | (b[i + k] & 0xff);
        out[0] = v;
        return n;
    }

    private static long uint(byte[] b, int i, int n) {
        long v = 0;
        for (int k = 0; k < n; k++) v = (v << 8) | (b[i + k] & 0xff);
        return v;
    }

    private static byte[] fetch(String url, long start, int length) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(5000);
            c.setReadTimeout(30000);
            c.setRequestProperty("Range", "bytes=" + start + "-" + (start + length - 1));
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(length);
            byte[] buf = new byte[65536];
            int n;
            while (bos.size() < length && (n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            c.disconnect();
        }
    }
}
