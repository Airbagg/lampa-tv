package local.vlcbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Связь с сервером пропуска заставок на маке (skipper/server.py).
 * Мак ищем через Bonjour (_lampaskip._tcp) — адрес мака может меняться;
 * последний найденный запоминаем.
 */
class SkipClient {
    private static final String TAG = "VlcBridgeSkip";
    private static final String SERVICE_TYPE = "_lampaskip._tcp.";
    private static final String FALLBACK = "http://192.168.0.139:8780";

    interface Callback {
        /** segments == null — сервер недоступен или ещё считает (pending). */
        void onResult(JSONObject segments, boolean pending);
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private boolean resolving;

    SkipClient(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = ctx.getSharedPreferences("skip", Context.MODE_PRIVATE);
        discover();
    }

    String base() {
        return prefs.getString("base", FALLBACK);
    }

    void fetch(final String hash, final int index, final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                JSONObject res = null;
                boolean pending = false;
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(base() + "/segments?hash=" + hash + "&index=" + index);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(2500);
                    conn.setReadTimeout(4000);
                    InputStream is = conn.getInputStream();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = is.read(b)) > 0) bos.write(b, 0, n);
                    JSONObject json = new JSONObject(bos.toString("UTF-8"));
                    if ("pending".equals(json.optString("status"))) pending = true;
                    else res = json;
                } catch (Exception e) {
                    Log.w(TAG, "Segments request failed: " + e);
                } finally {
                    if (conn != null) conn.disconnect();
                }
                final JSONObject r = res;
                final boolean p = pending;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onResult(r, p);
                    }
                });
            }
        }, "skip-fetch").start();
    }

    private void discover() {
        try {
            nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
            discovery = new NsdManager.DiscoveryListener() {
                @Override public void onStartDiscoveryFailed(String t, int e) { Log.w(TAG, "NSD start failed " + e); }
                @Override public void onStopDiscoveryFailed(String t, int e) { }
                @Override public void onDiscoveryStarted(String t) { }
                @Override public void onDiscoveryStopped(String t) { }
                @Override public void onServiceLost(NsdServiceInfo s) { }

                @Override
                public void onServiceFound(NsdServiceInfo s) {
                    if (resolving) return;
                    resolving = true;
                    nsd.resolveService(s, new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo s, int e) {
                            resolving = false;
                        }

                        @Override
                        public void onServiceResolved(NsdServiceInfo s) {
                            resolving = false;
                            if (s.getHost() == null) return;
                            String b = "http://" + s.getHost().getHostAddress() + ":" + s.getPort();
                            prefs.edit().putString("base", b).apply();
                            Log.i(TAG, "Skipper found at " + b);
                        }
                    });
                }
            };
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (Exception e) {
            Log.w(TAG, "NSD unavailable: " + e);
        }
    }

    void close() {
        try {
            if (nsd != null && discovery != null) nsd.stopServiceDiscovery(discovery);
        } catch (Exception ignored) {
        }
    }
}
