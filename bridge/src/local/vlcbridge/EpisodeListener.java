package local.vlcbridge;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * «Слушает» кусок серии и снимает с него акустический отпечаток (AudioPrint).
 *
 * Звук берём у TorrServer тем же адресом, что играет плеер, и декодируем
 * декодером телевизора (AC3/EAC3/DTS/AAC). Вперёд позиции плеера не убегаем
 * больше чем на LOOKAHEAD: эти куски TorrServer и так качает для плеера, так
 * что лишнего трафика почти нет, а заставку мы «слышим» чуть раньше зрителя.
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

    private final String url;
    private final long fromUs, toUs;
    private final Clock clock;
    private final Progress progress;
    private volatile boolean stop;

    EpisodeListener(String url, long fromUs, long toUs, Clock clock, Progress progress) {
        this.url = url;
        this.fromUs = fromUs;
        this.toUs = toUs;
        this.clock = clock;
        this.progress = progress;
    }

    void cancel() {
        stop = true;
    }

    @Override
    public void run() {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        AudioPrint ap = new AudioPrint();
        boolean complete = false;
        try {
            ex.setDataSource(url, java.util.Collections.<String, String>emptyMap());
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    fmt = f;
                    break;
                }
            }
            if (track < 0) {
                StringBuilder all = new StringBuilder();
                for (int i = 0; i < ex.getTrackCount(); i++) all.append(ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)).append(' ');
                throw new IllegalStateException("no audio track, tracks: " + all);
            }
            ex.selectTrack(track);
            if (fromUs > 0) ex.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();
            Log.i(TAG, "Listening " + fmt.getString(MediaFormat.KEY_MIME) + " " + fromUs / 1e6 + "–" + toUs / 1e6 + " s");

            Resampler rs = null;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            long lastReport = 0;
            double head = fromUs / 1e6;

            while (!stop) {
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        long t = ex.getSampleTime();
                        if (t < 0 || t > toUs) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            // Не убегаем вперёд плеера больше чем на LOOKAHEAD
                            long p = clock.playerUs();
                            while (!stop && p >= 0 && t > p + LOOKAHEAD_US) {
                                SystemClock.sleep(500);
                                p = clock.playerUs();
                            }
                            ByteBuffer buf = codec.getInputBuffer(in);
                            int size = ex.readSampleData(buf, 0);
                            if (size < 0) {
                                codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                codec.queueInputBuffer(in, 0, size, t, 0);
                                ex.advance();
                            }
                        }
                    }
                }

                int out = codec.dequeueOutputBuffer(info, 10_000);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rs = null;
                } else if (out >= 0) {
                    if (info.size > 0 && info.presentationTimeUs >= fromUs) {
                        if (rs == null) {
                            MediaFormat of = codec.getOutputFormat();
                            rs = new Resampler(of.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                                    of.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                        }
                        ByteBuffer ob = codec.getOutputBuffer(out);
                        ob.position(info.offset).limit(info.offset + info.size);
                        rs.push(ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer(), ap);
                        head = info.presentationTimeUs / 1e6;
                    }
                    codec.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        complete = true;
                        break;
                    }
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastReport > 3000) {
                        lastReport = now;
                        progress.onPrint(ap.result(), head);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Listening failed: " + e);
        } finally {
            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            } catch (Exception ignored) {
            }
            ex.release();
        }
        progress.onDone(ap.result(), complete);
    }

    /** Многоканальный PCM 16 бит → моно 11025 Гц (усреднение + линейная интерполяция). */
    static final class Resampler {
        private final int channels;
        private final double step; // входных отсчётов на один выходной
        private double pos;
        private float prev;
        private final float[] out = new float[4096];
        private int n;

        Resampler(int rate, int channels) {
            this.channels = Math.max(1, channels);
            this.step = (double) rate / AudioPrint.RATE;
        }

        void push(ShortBuffer pcm, AudioPrint ap) {
            int frames = pcm.remaining() / channels;
            for (int f = 0; f < frames; f++) {
                float s = 0;
                for (int c = 0; c < channels; c++) s += pcm.get();
                s /= channels * 32768f;
                // Простейший ФНЧ: среднее с предыдущим отсчётом
                float cur = (s + prev) * 0.5f;
                pos += 1;
                while (pos >= step) {
                    pos -= step;
                    double frac = pos;
                    out[n++] = (float) (cur - (cur - prev) * frac);
                    if (n == out.length) {
                        ap.feed(out, 0, n);
                        n = 0;
                    }
                }
                prev = cur;
            }
            if (n > 0) {
                ap.feed(out, 0, n);
                n = 0;
            }
        }
    }
}
