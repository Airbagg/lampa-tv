package local.vlcbridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Пропуск заставок и титров поверх VLC / Just Player — целиком на телевизоре.
 *
 * NotificationListenerService нужен только ради медиасессий других приложений
 * (позиция, название, перемотка); служба запускается, когда пользователь
 * включил ей «Доступ к уведомлениям».
 *
 * Как находим заставку: пока серия играет, слушаем её звук (EpisodeListener) —
 * первые 10 минут и последние 5 — и сравниваем отпечаток с другими сериями той
 * же раздачи, которые уже слышали. Общий кусок в начале — заставка, в конце —
 * титры. Первую серию сериала пропустить не получится — не с чем сравнить.
 */
public class SkipService extends NotificationListenerService
        implements MediaSessionManager.OnActiveSessionsChangedListener {

    private static final String TAG = "VlcBridgeSkip";
    private static final String VLC = "org.videolan.vlc";
    private static final String JUST_PLAYER = "com.brouken.player";
    private static final long TICK_MS = 400;
    // Мало осталось — кнопку не показываем: отсчёт 5/8 с + 1.5 с на «Назад» (SPEC §1)
    private static final long MIN_LEFT_INTRO_MS = 6500, MIN_LEFT_CREDITS_MS = 9500;

    private static final double START_SCAN_SEC = 600;  // заставку ищем в первых 10 минутах
    private static final double END_SCAN_SEC = 300;    // титры — в последних 5
    private static final double START_LISTEN_MAX_POS = 120; // позже начала — начало уже не слушаем
    private static final int MAX_BITS = 8;
    private static final int MAX_GAP = 8;
    // Заставка короче 12 с (у «Сверхъестественного» — надпись на 7 с) кнопки не стоит
    private static final double MIN_INTRO = 12, MAX_INTRO = 150, MIN_CREDITS = 10;
    private static final int COMPARE_WITH = 4;          // сколько соседних серий сравнивать

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSessionManager sessions;
    private ComponentName self;
    private MediaController player;
    private SkipOverlay overlay;

    private String key;                // hash:index текущей серии
    private long durMs;
    private volatile long playerUs = -1;
    private EpisodeListener startEar, endEar;
    private boolean startTried, endTried;
    private JSONObject seg = new JSONObject();
    private final List<String> handled = new ArrayList<>();
    private String showing;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                update();
            } catch (Exception e) {
                Log.w(TAG, "tick failed", e);
            }
            if (player != null) main.postDelayed(this, TICK_MS);
        }
    };

    @Override
    public void onListenerConnected() {
        Log.i(TAG, "Skip service connected");
        sessions = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        self = new ComponentName(this, SkipService.class);
        overlay = new SkipOverlay(this);
        registerDebug();
        sessions.addOnActiveSessionsChangedListener(this, self, main);
        onActiveSessionsChanged(sessions.getActiveSessions(self));
    }

    /**
     * Показать кнопку без серии — посмотреть вид и анимацию на телевизоре:
     * adb shell am broadcast -a local.vlcbridge.DEBUG_SHOW --es kind intro|next -p local.vlcbridge
     */
    private android.content.BroadcastReceiver debug;

    private void registerDebug() {
        debug = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context c, android.content.Intent i) {
                if ("local.vlcbridge.DEBUG_LISTEN".equals(i.getAction())) {
                    debugListen(i.getStringExtra("hash"), i.getIntExtra("index", 0),
                            "e".equals(i.getStringExtra("part")) ? "e" : "s", i.getIntExtra("sec", 600));
                    return;
                }
                String k = i.getStringExtra("kind");
                SkipOverlay.Kind kind = "next".equals(k) ? SkipOverlay.Kind.NEXT
                        : "credits".equals(k) ? SkipOverlay.Kind.CREDITS : SkipOverlay.Kind.INTRO;
                overlay.show(kind, new SkipOverlay.Listener() {
                    @Override public void onConfirm() { Log.i(TAG, "debug: confirm"); }
                    @Override public void onDismiss() { Log.i(TAG, "debug: dismiss"); }
                });
            }
        };
        android.content.IntentFilter f = new android.content.IntentFilter("local.vlcbridge.DEBUG_SHOW");
        f.addAction("local.vlcbridge.DEBUG_LISTEN");
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(debug, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(debug, f);
    }

    /**
     * Прослушать кусок серии без просмотра и сравнить с уже услышанными:
     * adb shell am broadcast -a local.vlcbridge.DEBUG_LISTEN --es hash H --ei index N --es part s --ei sec 420 -p local.vlcbridge
     */
    private void debugListen(final String hash, final int index, final String part, final int sec) {
        final String k = hash + ":" + index;
        final long t0 = SystemClock.elapsedRealtime();
        new Thread(new EpisodeListener(ffmpeg(), streamUrl(k), 0, sec * 1_000_000L, new EpisodeListener.Clock() {
            @Override
            public long playerUs() {
                return Long.MAX_VALUE / 4; // без привязки к плееру
            }
        }, new EpisodeListener.Progress() {
            @Override
            public void onPrint(int[] print, double head) {
                Log.i(TAG, "debug listen " + k + ": " + Math.round(head) + " s heard");
            }

            @Override
            public void onDone(int[] print, boolean complete) {
                Log.i(TAG, "debug listen " + k + " done: " + print.length + " frames, complete=" + complete
                        + ", " + (SystemClock.elapsedRealtime() - t0) / 1000 + " s");
                savePrint(k, part, 0, print);
                for (Other o : others(k, part)) {
                    AudioPrint.Match m = AudioPrint.bestCommon(print, o.print,
                            Math.max(print.length, o.print.length), MAX_BITS, MAX_GAP);
                    Log.i(TAG, "debug match " + k + " vs " + o.key + ": " + m);
                }
            }
        }), "ear-debug").start();
    }

    @Override
    public void onListenerDisconnected() {
        try { if (debug != null) unregisterReceiver(debug); } catch (Exception ignored) {}
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(this);
        main.removeCallbacks(tick);
        stopEars();
        if (overlay != null) overlay.hide(false);
        player = null;
    }

    @Override
    public void onActiveSessionsChanged(List<MediaController> list) {
        MediaController found = null;
        if (list != null) {
            for (MediaController c : list) {
                String pkg = c.getPackageName();
                if (VLC.equals(pkg) || JUST_PLAYER.equals(pkg)) {
                    found = c;
                    break;
                }
            }
        }
        boolean wasIdle = player == null;
        player = found;
        if (player == null) {
            main.removeCallbacks(tick);
            hideOverlay();
            stopEars();
            key = null;
        } else if (wasIdle) {
            main.post(tick);
        }
    }

    // ------------------------------------------------------------------ плеер

    private void update() {
        if (player == null) return;
        PlaybackState st = player.getPlaybackState();
        MediaMetadata md = player.getMetadata();
        if (st == null || md == null) return;

        boolean playing = st.getState() == PlaybackState.STATE_PLAYING;
        long pos = st.getPosition();
        if (playing && st.getLastPositionUpdateTime() > 0) {
            pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
        }
        playerUs = pos * 1000;
        CharSequence t = md.getDescription() != null ? md.getDescription().getTitle() : null;
        String k = resolveKey(t != null ? t.toString() : "");
        if (k == null) {
            hideOverlay();
            return;
        }
        if (!k.equals(key)) switchEpisode(k);
        long d = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (d > 0) durMs = d;
        overlay.setPaused(!playing);

        listen(pos);
        decide(pos);
    }

    private void switchEpisode(String k) {
        Log.i(TAG, "Episode " + k);
        hideOverlay();
        stopEars();
        key = k;
        durMs = 0;
        startTried = endTried = false;
        seg = loadSegments(k);
    }

    private void decide(long pos) {
        long[] intro = range(seg.optJSONArray("intro"));
        long[] credits = range(seg.optJSONArray("credits"));
        String kIntro = key + ":intro";
        String kCredits = key + ":credits";

        if (intro != null && !handled.contains(kIntro) && pos >= intro[0] && pos < intro[1] - MIN_LEFT_INTRO_MS) {
            show(kIntro, SkipOverlay.Kind.INTRO, intro[1]);
        } else if (credits != null && !handled.contains(kCredits) && pos >= credits[0]
                && (durMs <= 0 || pos < durMs - MIN_LEFT_CREDITS_MS)) {
            show(kCredits, hasNext() ? SkipOverlay.Kind.NEXT : SkipOverlay.Kind.CREDITS,
                    durMs > 0 ? durMs - 500 : credits[1]);
        } else if (showing != null) {
            hideOverlay(); // перемотали за пределы отрезка руками
        }
    }

    private void show(final String what, final SkipOverlay.Kind kind, final long target) {
        if (what.equals(showing)) return;
        hideOverlay();
        showing = what;
        Log.i(TAG, "Show " + kind + " for " + what + " → " + target);
        overlay.show(kind, new SkipOverlay.Listener() {
            @Override
            public void onConfirm() {
                handled.add(what);
                showing = null;
                MediaController p = player;
                if (p == null) return;
                if (kind == SkipOverlay.Kind.NEXT) p.getTransportControls().skipToNext();
                else p.getTransportControls().seekTo(target);
            }

            @Override
            public void onDismiss() {
                handled.add(what);
                showing = null;
            }
        });
    }

    private void hideOverlay() {
        if (showing != null) {
            showing = null;
            overlay.hide(true);
        }
    }

    private boolean hasNext() {
        try {
            List<MediaSession.QueueItem> q = player.getQueue();
            return q != null && q.size() > 1
                    && (player.getPlaybackState().getActions() & PlaybackState.ACTION_SKIP_TO_NEXT) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ слушаем серию

    private void listen(long posMs) {
        final String k = key;
        String url = streamUrl(k);
        if (url == null) return;
        EpisodeListener.Clock clock = new EpisodeListener.Clock() {
            @Override
            public long playerUs() {
                return playerUs;
            }
        };

        // Начало: только если серию смотрят с начала — иначе его уже нет в кэше TorrServer
        if (!startTried) {
            startTried = true;
            if (posMs / 1000.0 < START_LISTEN_MAX_POS && !seg.has("intro")) {
                startEar = new EpisodeListener(ffmpeg(), url, 0, (long) (START_SCAN_SEC * 1e6), clock,
                        new EpisodeListener.Progress() {
                            @Override
                            public void onPrint(final int[] print, final double head) {
                                main.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        findIntro(k, print, head);
                                    }
                                });
                            }

                            @Override
                            public void onDone(final int[] print, boolean complete) {
                                savePrint(k, "s", 0, print);
                                main.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        findIntro(k, print, Double.MAX_VALUE);
                                    }
                                });
                            }
                        });
                new Thread(startEar, "ear-start").start();
            }
        }

        // Конец: начинаем слушать за 5.5 минут до конца
        if (!endTried && durMs > 0 && posMs >= durMs - (END_SCAN_SEC + 30) * 1000) {
            endTried = true;
            if (!seg.has("credits")) {
                final double from = Math.max(0, durMs / 1000.0 - END_SCAN_SEC);
                endEar = new EpisodeListener(ffmpeg(), url, (long) (from * 1e6), durMs * 1000, clock,
                        new EpisodeListener.Progress() {
                            @Override
                            public void onPrint(final int[] print, double head) {
                                main.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        findCredits(k, print, from);
                                    }
                                });
                            }

                            @Override
                            public void onDone(final int[] print, boolean complete) {
                                savePrint(k, "e", from, print);
                                main.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        findCredits(k, print, from);
                                    }
                                });
                            }
                        });
                new Thread(endEar, "ear-end").start();
            }
        }
    }

    private void stopEars() {
        if (startEar != null) startEar.cancel();
        if (endEar != null) endEar.cancel();
        startEar = endEar = null;
    }

    /**
     * Заставка = общий кусок начала с другой серией. Ждём, пока кусок
     * закончится (за ним уже разный звук), — тогда знаем и конец.
     */
    private void findIntro(String k, int[] print, double headSec) {
        if (!k.equals(key) || seg.has("intro") || print.length < 200) return;
        for (Other o : others(k, "s")) {
            AudioPrint.Match m = AudioPrint.bestCommon(print, o.print, Math.max(print.length, o.print.length),
                    MAX_BITS, MAX_GAP);
            double len = m.seconds();
            double endA = m.startSecA() + len;
            boolean finished = endA < Math.min(headSec, print.length * AudioPrint.FRAME_SEC) - 2;
            if (len >= MIN_INTRO && len <= MAX_INTRO && finished) {
                Log.i(TAG, "Intro " + k + " vs " + o.key + ": " + m);
                putSegment(k, "intro", m.startSecA(), endA);
                putSegment(o.key, "intro", m.startSecB(), m.startSecB() + len);
                return;
            }
        }
    }

    /** Титры = общий кусок конца с другой серией; от его начала до конца серии. */
    private void findCredits(String k, int[] print, double fromSec) {
        if (!k.equals(key) || seg.has("credits") || print.length < 100) return;
        for (Other o : others(k, "e")) {
            AudioPrint.Match m = AudioPrint.bestCommon(print, o.print, Math.max(print.length, o.print.length),
                    MAX_BITS, MAX_GAP);
            if (m.seconds() >= MIN_CREDITS) {
                Log.i(TAG, "Credits " + k + " vs " + o.key + ": " + m);
                double start = fromSec + m.startSecA();
                putSegment(k, "credits", start, durMs > 0 ? durMs / 1000.0 : start + m.seconds());
                putSegment(o.key, "credits", o.offset + m.startSecB(), o.offset + m.startSecB() + m.seconds());
                return;
            }
        }
    }

    // ------------------------------------------------------------------ хранение

    private static final class Other {
        final String key;
        final double offset;
        final int[] print;

        Other(String key, double offset, int[] print) {
            this.key = key;
            this.offset = offset;
            this.print = print;
        }
    }

    private File printsDir() {
        File d = new File(getFilesDir(), "prints");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private File printFile(String k, String part) {
        return new File(printsDir(), k.replace(':', '_') + "_" + part + ".bin");
    }

    private void savePrint(String k, String part, double offset, int[] print) {
        if (print.length < 100) return;
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(printFile(k, part)))) {
            out.writeDouble(offset);
            out.writeInt(print.length);
            for (int v : print) out.writeInt(v);
        } catch (Exception e) {
            Log.w(TAG, "savePrint failed: " + e);
        }
    }

    /** Отпечатки других серий той же раздачи, ближайшие по номеру первыми. */
    private List<Other> others(String k, String part) {
        String[] hk = k.split(":");
        final int index = Integer.parseInt(hk[1]);
        List<Other> res = new ArrayList<>();
        File[] files = printsDir().listFiles();
        if (files == null) return res;
        List<File> mine = new ArrayList<>();
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith(hk[0] + "_") && n.endsWith("_" + part + ".bin")
                    && !n.equals(printFile(k, part).getName())) mine.add(f);
        }
        java.util.Collections.sort(mine, new java.util.Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Integer.compare(Math.abs(indexOf(a) - index), Math.abs(indexOf(b) - index));
            }
        });
        for (File f : mine) {
            if (res.size() >= COMPARE_WITH) break;
            try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
                double off = in.readDouble();
                int[] p = new int[in.readInt()];
                for (int i = 0; i < p.length; i++) p[i] = in.readInt();
                res.add(new Other(hk[0] + ":" + indexOf(f), off, p));
            } catch (Exception ignored) {
            }
        }
        return res;
    }

    private static int indexOf(File f) {
        String[] p = f.getName().split("_");
        try {
            return Integer.parseInt(p[1]);
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    private JSONObject loadSegments(String k) {
        try {
            String s = getSharedPreferences("segments", MODE_PRIVATE).getString(k, null);
            return s != null ? new JSONObject(s) : new JSONObject();
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void putSegment(String k, String name, double from, double to) {
        try {
            JSONObject s = k.equals(key) ? seg : loadSegments(k);
            s.put(name, new JSONArray().put(Math.round(from * 10) / 10.0).put(Math.round(to * 10) / 10.0));
            getSharedPreferences("segments", MODE_PRIVATE).edit().putString(k, s.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "putSegment failed: " + e);
        }
    }

    // ------------------------------------------------------------------ что играет

    /**
     * hash:index текущей серии. Плейлист VLC называет пункты по имени файла
     * (EXTINF из m3u TorrServer), одиночный запуск — по title от Лампы.
     */
    private String resolveKey(String title) {
        SharedPreferences launch = getSharedPreferences("launch", MODE_PRIVATE);
        String hash = launch.getString("hash", null);
        if (hash == null) return null;
        int index = launch.getInt("index", -1);
        String norm = stripExt(title).trim().toLowerCase();
        try {
            JSONArray files = new JSONArray(launch.getString("files", "[]"));
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.getJSONObject(i);
                if (stripExt(f.optString("name")).trim().toLowerCase().equals(norm)) {
                    return hash + ":" + f.optInt("id");
                }
            }
        } catch (Exception ignored) {
        }
        if (launch.getBoolean("playlist", false)) return null; // пункт плейлиста, а мы его не узнали
        return index >= 0 ? hash + ":" + index : null;
    }

    /** Встроенный ffmpeg — Android распаковал его как нативную библиотеку. */
    private String ffmpeg() {
        return getApplicationInfo().nativeLibraryDir + "/libffmpeg.so";
    }

    private String streamUrl(String k) {
        if (k == null) return null;
        String[] hk = k.split(":");
        return "http://127.0.0.1:8090/stream/f?link=" + hk[0] + "&index=" + hk[1] + "&play";
    }

    private static String stripExt(String s) {
        if (s == null) return "";
        int dot = s.lastIndexOf('.');
        return dot > 0 && s.length() - dot <= 5 ? s.substring(0, dot) : s;
    }

    private static long[] range(JSONArray a) {
        if (a == null || a.length() < 2) return null;
        return new long[]{(long) (a.optDouble(0) * 1000), (long) (a.optDouble(1) * 1000)};
    }
}
