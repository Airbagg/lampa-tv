package local.vlcbridge;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * Окно с кнопкой «Пропустить …» поверх плеера.
 *
 * Пока кнопка на экране, окно в фокусе: ОК — пропустить сразу, Назад —
 * отменить автопропуск (событие съедаем, чтобы плеер не закрылся), стрелки —
 * кнопка упруго кивает. Если ничего не нажимать, кнопка заполняется слева
 * направо и в конце пропускает сама. Отсчёт и анимации — в SkipButtonView.
 */
class SkipOverlay {
    private static final String TAG = "VlcBridgeSkip";

    enum Kind { INTRO, CREDITS, NEXT }

    interface Listener {
        void onConfirm();

        void onDismiss();
    }

    private final Context ctx;
    private final WindowManager wm;
    private SkipButtonView view;
    private Listener listener;

    SkipOverlay(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.wm = (WindowManager) this.ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    void show(Kind kind, Listener l) {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(ctx)) {
            Log.w(TAG, "No overlay permission — skip button not shown");
            return;
        }
        removeNow();
        listener = l;
        final SkipButtonView v = new SkipButtonView(ctx, kind);
        view = v;
        v.setCallbacks(new SkipButtonView.Callbacks() {
            @Override
            public void onConfirm() {
                Listener cur = listener;
                listener = null;
                if (cur != null) cur.onConfirm();
            }

            @Override
            public void onGone() {
                remove(v);
                if (view == v) view = null;
            }
        });
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        v.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View view, int code, KeyEvent e) {
                boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
                if (e.getRepeatCount() > 0) return true;
                switch (code) {
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                    case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    case KeyEvent.KEYCODE_BUTTON_A:
                        if (down) v.pressDown();
                        else v.confirm();
                        return true;
                    case KeyEvent.KEYCODE_BACK:
                    case KeyEvent.KEYCODE_ESCAPE:
                        if (!down) dismiss();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        if (down) v.nod(-1, 0);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        if (down) v.nod(1, 0);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (down) v.nod(0, -1);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (down) v.nod(0, 1);
                        return true;
                    default:
                        // Остальное (громкость, пауза и т.п.) — не наше, но и плееру
                        // не дойдёт, пока мы в фокусе; отдаём системе
                        return false;
                }
            }
        });

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.BOTTOM | Gravity.END;
        p.x = v.windowX();
        p.y = v.windowY();
        p.setTitle("LampaSkip");
        try {
            wm.addView(v, p);
        } catch (Exception e) {
            Log.w(TAG, "addView failed", e);
            view = null;
            return;
        }
        v.requestFocus();
    }

    /** Видео на паузе — отсчёт тоже на паузе. */
    void setPaused(boolean paused) {
        if (view != null) view.setPaused(paused);
    }

    /** Убрать кнопку без решения (отрезок кончился или перемотали руками). */
    void hide(boolean animate) {
        listener = null;
        if (view == null) return;
        if (animate) view.end();
        else removeNow();
    }

    private void dismiss() {
        Listener cur = listener;
        listener = null;
        if (view != null) view.cancel();
        if (cur != null) cur.onDismiss();
    }

    private void removeNow() {
        if (view != null) remove(view);
        view = null;
    }

    private void remove(View v) {
        try {
            wm.removeViewImmediate(v);
        } catch (Exception ignored) {
        }
    }
}
