package local.vlcbridge;

import android.os.SystemClock;
import android.util.Log;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * «Слушает» кусок серии и снимает с него акустический отпечаток (AudioPrint).
 *
 * Звук берём у TorrServer тем же адресом, что играет плеер. Разбирает его
 * встроенный ffmpeg (native/libffmpeg.so): стандартный MatroskaExtractor на
 * этом телевизоре не умеет A_AC3, а почти все раздачи — MKV с AC3. На выходе
 * ffmpeg — моно 11025 Гц s16le прямо в AudioPrint.
 *
 * Вперёд позиции плеера не убегаем больше чем на LOOKAHEAD: просто перестаём
 * читать вывод ffmpeg, он упирается в трубу и ждёт. Эти куски TorrServer и так
 * качает для плеера, так что лишнего трафика почти нет, а заставку мы «слышим»
 * чуть раньше зрителя.
 */
class EpisodeListener implements Runnable {
    private static final String TAG = "VlcBridgeSkip";
    static final long LOOKAHEAD_US = 75_000_000L;

    interface Clock {
        /** Где сейчас плеер, мкс; < 0 — неизвестно. */
        long playerUs();
    }

    interface Progress {
        /** Отпечаток подрос: print — с начала отрезка, headSec — до какой секунды серии услышали. */
        void onPrint(int[] print, double headSec);

        void onDone(int[] print, boolean complete);
    }

    private final String ffmpeg;
    private final String url;
    private final long fromUs, toUs;
    private final Clock clock;
    private final Progress progress;
    private volatile boolean stop;
    private volatile Process proc;

    EpisodeListener(String ffmpeg, String url, long fromUs, long toUs, Clock clock, Progress progress) {
        this.ffmpeg = ffmpeg;
        this.url = url;
        this.fromUs = fromUs;
        this.toUs = toUs;
        this.clock = clock;
        this.progress = progress;
    }

    void cancel() {
        stop = true;
        Process p = proc;
        if (p != null) p.destroy();
    }

    @Override
    public void run() {
        // TorrServer в «отзывчивом режиме» отдаёт нули вместо ещё не скачанных
        // кусков — ffmpeg тогда не может открыть файл. Ждём и пробуем снова.
        for (int attempt = 0; attempt < 6 && !stop; attempt++) {
            if (attempt > 0) SystemClock.sleep(5000L * attempt);
            if (listenOnce()) return;
        }
        progress.onDone(new int[0], false);
    }

    /** true — дослушали или прервали (результат отдан), false — не открылось, можно повторить. */
    private boolean listenOnce() {
        AudioPrint ap = new AudioPrint();
        boolean complete = false;
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg);
        cmd.add("-v");
        cmd.add("error");
        cmd.add("-nostdin");
        if (fromUs > 0) {
            cmd.add("-ss");
            cmd.add(String.valueOf(fromUs / 1e6));
        }
        cmd.add("-i");
        cmd.add(url);
        cmd.add("-t");
        cmd.add(String.valueOf((toUs - fromUs) / 1e6));
        for (String a : new String[]{"-vn", "-sn", "-map", "0:a:0", "-ac", "1", "-ar",
                String.valueOf(AudioPrint.RATE), "-f", "s16le", "-"}) cmd.add(a);

        long samples = 0;
        try {
            Log.i(TAG, "Listening " + fromUs / 1e6 + "–" + toUs / 1e6 + " s");
            proc = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            InputStream in = proc.getInputStream();
            byte[] buf = new byte[16384];
            float[] pcm = new float[buf.length / 2];
            int carry = -1; // нечётный байт с прошлого чтения
            long lastReport = 0;
            int n;
            while (!stop && (n = in.read(buf)) > 0) {
                int count = 0, i = 0;
                if (carry >= 0) {
                    pcm[count++] = (short) ((buf[0] << 8) | carry) / 32768f;
                    i = 1;
                    carry = -1;
                }
                for (; i + 1 < n; i += 2) pcm[count++] = (short) ((buf[i + 1] << 8) | (buf[i] & 0xff)) / 32768f;
                if (i < n) carry = buf[i] & 0xff;
                ap.feed(pcm, 0, count);
                samples += count;

                double head = fromUs / 1e6 + (double) samples / AudioPrint.RATE;
                long now = SystemClock.elapsedRealtime();
                if (now - lastReport > 3000) {
                    lastReport = now;
                    progress.onPrint(ap.result(), head);
                }
                // Не убегаем вперёд плеера больше чем на LOOKAHEAD
                long p = clock.playerUs();
                while (!stop && p >= 0 && head * 1e6 > p + LOOKAHEAD_US) {
                    SystemClock.sleep(500);
                    p = clock.playerUs();
                }
            }
            if (!stop) {
                int code = proc.waitFor();
                complete = code == 0;
                if (code != 0) {
                    Log.w(TAG, "ffmpeg exit " + code + ": " + readAll(proc.getErrorStream()));
                    if (samples == 0) return false; // не открылось — повторим
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Listening failed: " + e);
            if (ap.size() == 0 && !stop) return false;
        } finally {
            Process p = proc;
            if (p != null) p.destroy();
        }
        progress.onDone(ap.result(), complete);
        return true;
    }

    private static String readAll(InputStream in) {
        try {
            byte[] b = new byte[2048];
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n, "UTF-8").trim() : "";
        } catch (Exception e) {
            return "";
        }
    }
}
