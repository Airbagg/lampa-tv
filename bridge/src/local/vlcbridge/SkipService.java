package local.vlcbridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Пропуск заставок и титров поверх VLC / Just Player.
 *
 * Это NotificationListenerService только ради одного: такой службе система
 * отдаёт медиасессии других приложений (позиция, название, перемотка). Пока
 * пользователь не включил «Доступ к уведомлениям», служба не запускается.
 *
 * Что играет: название из медиасессии сверяем со списком файлов раздачи,
 * который посредник сохранил при запуске (BridgeActivity → prefs "launch").
 * Где заставка и титры: спрашиваем сервер на маке (SkipClient).
 */
public class SkipService extends NotificationListenerService
        implements MediaSessionManager.OnActiveSessionsChangedListener {

    private static final String TAG = "VlcBridgeSkip";
    private static final String VLC = "org.videolan.vlc";
    private static final String JUST_PLAYER = "com.brouken.player";
    private static final long TICK_MS = 400;
    private static final long RETRY_PENDING_MS = 20000;
    private static final long RETRY_FAILED_MS = 60000;
    // Не предлагаем пропуск, если до конца отрезка осталось меньше
    private static final long MIN_LEFT_MS = 4000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSessionManager sessions;
    private ComponentName self;
    private MediaController player;
    private SkipClient client;
    private SkipOverlay overlay;

    private final Map<String, JSONObject> segments = new HashMap<>();
    private final Map<String, Long> retryAt = new HashMap<>();
    private final Set<String> inFlight = new HashSet<>();
    private final Set<String> handled = new HashSet<>();
    private String showing; // "key:intro" / "key:credits"

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
        client = new SkipClient(this);
        overlay = new SkipOverlay(this);
        sessions.addOnActiveSessionsChangedListener(this, self, main);
        onActiveSessionsChanged(sessions.getActiveSessions(self));
    }

    @Override
    public void onListenerDisconnected() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(this);
        main.removeCallbacks(tick);
        if (overlay != null) overlay.hide(false);
        if (client != null) client.close();
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
        } else if (wasIdle) {
            main.post(tick);
        }
    }

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
        long dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        CharSequence t = md.getDescription() != null ? md.getDescription().getTitle() : null;
        String key = resolveKey(t != null ? t.toString() : "");
        if (key == null) {
            hideOverlay();
            return;
        }
        overlay.setPaused(!playing);

        JSONObject seg = segments.get(key);
        if (seg == null) {
            request(key);
            return;
        }

        long[] intro = range(seg.optJSONArray("intro"));
        long[] credits = range(seg.optJSONArray("credits"));
        String kIntro = key + ":intro";
        String kCredits = key + ":credits";

        if (intro != null && !handled.contains(kIntro) && pos >= intro[0] && pos < intro[1] - MIN_LEFT_MS) {
            show(kIntro, SkipOverlay.Kind.INTRO, intro[1]);
        } else if (credits != null && !handled.contains(kCredits) && pos >= credits[0]
                && (dur <= 0 || pos < dur - MIN_LEFT_MS)) {
            show(kCredits, hasNext() ? SkipOverlay.Kind.NEXT : SkipOverlay.Kind.CREDITS, dur > 0 ? dur - 500 : credits[1]);
        } else if (showing != null) {
            // Ушли из отрезка (перемотали руками) — кнопку убираем, но не помечаем
            hideOverlay();
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
            List<android.media.session.MediaSession.QueueItem> q = player.getQueue();
            return q != null && q.size() > 1
                    && (player.getPlaybackState().getActions() & PlaybackState.ACTION_SKIP_TO_NEXT) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void request(final String key) {
        if (inFlight.contains(key)) return;
        Long at = retryAt.get(key);
        if (at != null && SystemClock.elapsedRealtime() < at) return;
        inFlight.add(key);
        String[] hk = key.split(":");
        client.fetch(hk[0], Integer.parseInt(hk[1]), new SkipClient.Callback() {
            @Override
            public void onResult(JSONObject seg, boolean pending) {
                inFlight.remove(key);
                if (seg != null) {
                    segments.put(key, seg);
                    Log.i(TAG, "Segments " + key + ": " + seg);
                } else {
                    retryAt.put(key, SystemClock.elapsedRealtime() + (pending ? RETRY_PENDING_MS : RETRY_FAILED_MS));
                }
            }
        });
    }

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
