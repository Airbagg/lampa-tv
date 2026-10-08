package local.vlcbridge;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Посредник между Лампой и плеерами.
 *
 * 1. Лампа запускает плеер через startActivityForResult, внутри своей задачи.
 *    VLC на Android TV сразу после старта шлёт своему VideoPlayerActivity
 *    (singleTask) интент PLAY_FROM_VIDEOGRID с FLAG_ACTIVITY_NEW_TASK. Если плеер
 *    живёт в задаче Лампы, этот интент создаёт вторую копию плеера, копии рвут
 *    друг другу загрузку, и VLC закрывается («моргнул и ничего»). Поэтому плеер
 *    запускается отдельной задачей (FLAG_ACTIVITY_NEW_TASK).
 *
 * 2. VLC 3.x не умеет AV1 через MediaCodec (в mediacodec.c ветки 3.0.x нет
 *    VLC_CODEC_AV1) и декодирует его программно; слабый процессор ТВ 1080p не
 *    тянет: звук идёт, картинка стоит. Поэтому перед запуском смотрим кодек
 *    видео: AV1 уходит в Just Player (ExoPlayer берёт аппаратный декодер ТВ),
 *    всё остальное — в VLC.
 *
 * Плата за отдельную задачу: плеер не вернёт Лампе позицию, на которой
 * остановились, но позицию от Лампы мы ему передаём.
 */
public class BridgeActivity extends Activity {
    private static final String TAG = "VlcBridge";
    private static final String VLC = "org.videolan.vlc";
    private static final String JUST_PLAYER = "com.brouken.player";
    private static final String MIME_AV1 = "video/av01";
    // Лампа зовёт плеер после прелоада, так что заголовки файла уже скачаны и
    // проба занимает секунду-две. Если торрент тормозит, не держим зрителя.
    private static final long PROBE_TIMEOUT_MS = 8000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean handedOver = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Intent in = getIntent();
        final Uri uri = in != null ? in.getData() : null;
        if (uri == null) {
            finish();
            return;
        }

        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            handOver(in, VLC, "not http", null);
            return;
        }

        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                handOver(in, VLC, "probe timeout", seriesPlaylist(uri, false));
            }
        }, PROBE_TIMEOUT_MS);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final long started = android.os.SystemClock.elapsedRealtime();
                final String mime = probeVideoMime(uri);
                final boolean av1 = MIME_AV1.equals(mime) && isInstalled(JUST_PLAYER);
                final Uri playlist = av1 ? null : seriesPlaylist(uri, true);
                final long took = android.os.SystemClock.elapsedRealtime() - started;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        handOver(in, av1 ? JUST_PLAYER : VLC, "video " + mime + " in " + took + " ms", playlist);
                    }
                });
            }
        }, "codec-probe").start();
    }

    @Override
    protected void onDestroy() {
        // Ушли назад во время пробы: плеер уже не запускаем
        handedOver.set(true);
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /**
     * Ищем, где в файле записан кодек, и читаем только этот кусок.
     * MKV: блок Tracks с CodecID лежит в первых килобайтах.
     * MP4: идём по коробкам верхнего уровня; moov в начале — ищем в нём
     * av1C/avcC/hvcC, moov после mdat — читаем кусок сразу за mdat.
     * Не вышло — разбираем файл через MediaExtractor.
     */
    private static String probeVideoMime(Uri uri) {
        try {
            String sniffed = sniff(uri);
            if (sniffed != null) return sniffed;
        } catch (Exception e) {
            Log.w(TAG, "Sniff failed: " + e);
        }
        return extractVideoMime(uri);
    }

    private static final String[][] MARKERS = {
            // MKV CodecID
            {"V_AV1", MIME_AV1},
            {"V_MPEG4/ISO/AVC", "video/avc"},
            {"V_MPEGH/ISO/HEVC", "video/hevc"},
            {"V_VP9", "video/x-vnd.on2.vp9"},
            {"V_MPEG2", "video/mpeg2"},
            // MP4 конфигурационные коробки
            {"av1C", MIME_AV1},
            {"avcC", "video/avc"},
            {"hvcC", "video/hevc"},
            {"vpcC", "video/x-vnd.on2.vp9"},
    };

    private static final int CHUNK = 256 * 1024;

    private static String sniff(Uri uri) throws Exception {
        byte[] head = readRange(uri, 0, CHUNK);
        if (head.length < 16) return null;

        // EBML magic — Matroska/WebM
        if ((head[0] & 0xff) == 0x1A && (head[1] & 0xff) == 0x45
                && (head[2] & 0xff) == 0xDF && (head[3] & 0xff) == 0xA3) {
            return findMarker(head);
        }

        // MP4: коробки верхнего уровня
        long offset = 0;
        while (offset + 16 <= head.length) {
            int o = (int) offset;
            long size = be32(head, o);
            String type = new String(head, o + 4, 4, "US-ASCII");
            if (size == 1) size = be64(head, o + 8);
            if (size < 8) return null;
            if ("moov".equals(type)) {
                byte[] moov = offset + size <= head.length ? head : readRange(uri, offset, CHUNK * 4);
                return findMarker(moov);
            }
            if ("mdat".equals(type)) {
                // moov лежит после сжатых данных — читаем кусок сразу за mdat
                return findMarker(readRange(uri, offset + size, CHUNK * 4));
            }
            offset += size;
        }
        return null;
    }

    private static String findMarker(byte[] buf) throws Exception {
        String best = null;
        int bestAt = Integer.MAX_VALUE;
        for (String[] m : MARKERS) {
            int at = indexOf(buf, buf.length, m[0].getBytes("US-ASCII"));
            if (at >= 0 && at < bestAt) {
                bestAt = at;
                best = m[1];
            }
        }
        return best;
    }

    private static byte[] readRange(Uri uri, long from, int maxLen) throws Exception {
        java.net.HttpURLConnection conn =
                (java.net.HttpURLConnection) new java.net.URL(uri.toString()).openConnection();
        try {
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Range", "bytes=" + from + "-" + (from + maxLen - 1));
            java.io.InputStream is = conn.getInputStream();
            byte[] buf = new byte[maxLen];
            int len = 0, n;
            while (len < maxLen && (n = is.read(buf, len, maxLen - len)) > 0) len += n;
            return len == maxLen ? buf : java.util.Arrays.copyOf(buf, len);
        } finally {
            conn.disconnect();
        }
    }

    private static long be32(byte[] b, int o) {
        return ((long) (b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16)
                | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }

    private static long be64(byte[] b, int o) {
        return (be32(b, o) << 32) | be32(b, o + 4);
    }

    private static int indexOf(byte[] hay, int len, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= len; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static String extractVideoMime(Uri uri) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(uri.toString(), Collections.<String, String>emptyMap());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) return mime;
            }
        } catch (Exception e) {
            Log.w(TAG, "Codec probe failed: " + e);
        } finally {
            extractor.release();
        }
        return null;
    }

    private static final java.util.regex.Pattern EPISODE = java.util.regex.Pattern.compile(
            "s\\d{1,2}\\s?e\\d{1,3}|\\d{1,2}x\\d{2,3}|сери[яи]|episode|\\bep?\\d{2,3}\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);

    private static final java.util.regex.Pattern VIDEO_EXT = java.util.regex.Pattern.compile(
            "\\.(mkv|mp4|avi|ts|m2ts|mov|wmv|webm|m4v|mpg|mpeg|vob)$",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * http://…/stream/<file>?link=…&index=N&play → тот же адрес с m3u вместо play,
     * если это серия: в имени есть S01E02 / «серия» и т.п., или (askServer) в той
     * же папке раздачи лежат ещё видео — имена вида «01. Название.mkv».
     */
    static Uri seriesPlaylist(Uri uri, boolean askServer) {
        if (uri == null || uri.getQueryParameter("link") == null
                || uri.getQueryParameter("index") == null
                || uri.getPath() == null || !uri.getPath().contains("/stream/")) return null;
        String file = uri.getLastPathSegment();
        if (file == null) return null;
        boolean series = EPISODE.matcher(file).find();
        if (!series && askServer) series = hasSiblingVideos(uri);
        if (!series) return null;

        Uri.Builder b = uri.buildUpon().clearQuery();
        for (String name : uri.getQueryParameterNames()) {
            if ("play".equals(name) || "preload".equals(name)) continue;
            for (String value : uri.getQueryParameters(name)) b.appendQueryParameter(name, value);
        }
        return b.appendQueryParameter("m3u", "").build();
    }

    private static boolean hasSiblingVideos(Uri uri) {
        java.net.HttpURLConnection conn = null;
        try {
            String hash = uri.getQueryParameter("link");
            int index = Integer.parseInt(uri.getQueryParameter("index"));
            java.net.URL url = new java.net.URL(uri.getScheme() + "://" + uri.getEncodedAuthority() + "/torrents");
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(3000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.getOutputStream().write(("{\"action\":\"get\",\"hash\":\"" + hash + "\"}").getBytes("UTF-8"));
            java.io.InputStream is = conn.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = is.read(b)) > 0) bos.write(b, 0, n);
            org.json.JSONArray files = new org.json.JSONObject(bos.toString("UTF-8")).optJSONArray("file_stats");
            if (files == null) return false;

            String dir = null;
            for (int i = 0; i < files.length(); i++) {
                org.json.JSONObject f = files.getJSONObject(i);
                if (f.optInt("id") == index) {
                    String path = f.optString("path");
                    dir = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "";
                }
            }
            if (dir == null) return false;
            int videos = 0;
            for (int i = 0; i < files.length(); i++) {
                String path = files.getJSONObject(i).optString("path");
                String d = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "";
                if (d.equals(dir) && VIDEO_EXT.matcher(path).find()) videos++;
            }
            return videos >= 2;
        } catch (Exception e) {
            Log.w(TAG, "Sibling check failed: " + e);
        } finally {
            if (conn != null) conn.disconnect();
        }
        return false;
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void handOver(Intent in, String pkg, String reason, Uri playlist) {
        if (!handedOver.compareAndSet(false, true)) return;
        main.removeCallbacksAndMessages(null);

        Intent out = new Intent(Intent.ACTION_VIEW);
        String type = in.getType();
        Uri data = in.getData();
        // Серия сериала из TorrServer → отдаём VLC плейлист с неё до конца
        // раздачи (TorrServer сам строит m3u от index), следующая серия
        // включится сама. Just Player m3u от TorrServer не понимает.
        if (!VLC.equals(pkg)) playlist = null;
        if (playlist != null) {
            data = playlist;
            reason += ", playlist";
        }
        out.setDataAndType(data, playlist != null || type == null ? "video/*" : type);
        out.setPackage(pkg);
        out.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        // Лампа шлёт «неизвестному» плееру title / android.intent.extra.TITLE
        String title = in.getStringExtra("title");
        if (title == null) title = in.getStringExtra(Intent.EXTRA_TITLE);
        if (title != null) out.putExtra("title", title);

        // ...и position (int, мс), когда надо продолжить с места.
        // VLC ждёт long и from_start, Just Player — int.
        int position = in.getIntExtra("position", 0);
        if (position > 0) {
            if (VLC.equals(pkg)) {
                out.putExtra("from_start", false);
                out.putExtra("position", (long) position);
            } else {
                out.putExtra("position", position);
            }
        }

        try {
            startActivity(out);
            Log.i(TAG, "Handed over to " + pkg + " (" + reason + "): " + in.getData()
                    + " position=" + position);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, pkg + " not found", e);
            Toast.makeText(this, "Плеер не установлен: " + pkg, Toast.LENGTH_LONG).show();
        }
        // Без данных в результате Лампа ничего не перезаписывает в истории
        setResult(RESULT_CANCELED);
        finish();
    }
}
