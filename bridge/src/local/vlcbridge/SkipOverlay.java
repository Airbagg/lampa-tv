package local.vlcbridge;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;

/**
 * Окно с кнопкой «Пропустить …» поверх плеера.
 *
 * Пока кнопка на экране, окно в фокусе: ОК — пропустить сразу, Назад —
 * отменить автопропуск, остальные кнопки пульта тоже отменяют (зритель
 * взялся за управление сам). Если ничего не нажимать, кнопка заполняется
 * слева направо и в конце пропускает сама.
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
    private ValueAnimator countdown;
    private Listener listener;
    private boolean attached;

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
        view = new SkipButtonView(ctx, kind);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int code, KeyEvent e) {
                if (e.getAction() != KeyEvent.ACTION_UP) return true;
                if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                        || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A) {
                    view.press();
                    finish(true);
                } else {
                    finish(false);
                }
                return true;
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
        p.x = view.marginEnd();
        p.y = view.marginBottom();
        p.setTitle("LampaSkip");
        try {
            wm.addView(view, p);
            attached = true;
        } catch (Exception e) {
            Log.w(TAG, "addView failed", e);
            return;
        }
        view.requestFocus();
        view.enter();

        countdown = ValueAnimator.ofFloat(0f, 1f);
        countdown.setDuration(view.countdownMs());
        countdown.setStartDelay(view.enterMs());
        countdown.setInterpolator(new LinearInterpolator());
        countdown.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                if (view != null) view.setProgress((Float) a.getAnimatedValue());
            }
        });
        countdown.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator a) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator a) {
                if (!cancelled) finish(true);
            }
        });
        countdown.start();
    }

    /** Видео на паузе — отсчёт тоже на паузе. */
    void setPaused(boolean paused) {
        if (countdown == null || Build.VERSION.SDK_INT < 19) return;
        if (paused && countdown.isRunning() && !countdown.isPaused()) countdown.pause();
        else if (!paused && countdown.isPaused()) countdown.resume();
    }

    /** Убрать кнопку без решения (зритель перемотал за пределы отрезка). */
    void hide(boolean animate) {
        if (countdown != null) countdown.cancel();
        countdown = null;
        listener = null;
        if (!attached || view == null) return;
        if (animate) {
            final SkipButtonView v = view;
            v.exit(new Runnable() {
                @Override
                public void run() {
                    remove(v);
                }
            });
            view = null;
            attached = false;
        } else {
            removeNow();
        }
    }

    private void finish(boolean confirm) {
        Listener l = listener;
        listener = null;
        if (countdown != null) countdown.cancel();
        countdown = null;
        if (l != null) {
            if (confirm) l.onConfirm();
            else l.onDismiss();
        }
        hide(true);
    }

    private void removeNow() {
        if (attached && view != null) remove(view);
        view = null;
        attached = false;
    }

    private void remove(View v) {
        try {
            wm.removeViewImmediate(v);
        } catch (Exception ignored) {
        }
    }
}
