package local.vlcbridge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * Кнопка «Пропустить заставку» / «Следующая серия» в духе tvOS.
 *
 * Стеклянная подложка без настоящего размытия (оверлей не может размыть
 * чужое видео), заливка слева направо — отсчёт до автопропуска. Текст и
 * иконка рисуются дважды: светлые поверх стекла и тёмные поверх заливки,
 * граница — по фронту заливки.
 *
 * Все размеры заданы для экрана 1920×1080 и масштабируются под реальный.
 */
class SkipButtonView extends View {
    // --- Геометрия (px при 1920×1080) ---
    private static final float HEIGHT = 76;
    private static final float PAD_START = 30;
    private static final float PAD_END = 36;
    private static final float RADIUS = 18;
    private static final float ICON = 30;
    private static final float ICON_GAP = 16;
    private static final float TEXT_SIZE = 30;
    private static final float STROKE = 1.5f;
    private static final float SHADOW_R = 28;
    private static final float SHADOW_DY = 10;
    private static final float MARGIN_END = 96;
    private static final float MARGIN_BOTTOM = 128;

    // --- Цвета ---
    private static final int GLASS = 0xB81C1C1E;
    private static final int GLASS_TOP = 0x26FFFFFF;
    private static final int STROKE_C = 0x3DFFFFFF;
    private static final int FILL = 0xF2FFFFFF;
    private static final int ON_GLASS = 0xFFFFFFFF;
    private static final int ON_FILL = 0xFF1C1C1E;
    private static final int SHADOW = 0x66000000;

    // --- Время ---
    private static final int ENTER_MS = 420;
    private static final int EXIT_MS = 240;
    private static final int INTRO_COUNTDOWN_MS = 6000;
    private static final int CREDITS_COUNTDOWN_MS = 10000;

    private final SkipOverlay.Kind kind;
    private final float s; // масштаб под экран
    private final String label;
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Path clip = new Path();
    private float progress;

    SkipButtonView(Context ctx, SkipOverlay.Kind kind) {
        super(ctx);
        this.kind = kind;
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        s = Math.max(dm.widthPixels, dm.heightPixels) / 1920f;
        label = kind == SkipOverlay.Kind.INTRO ? "Пропустить заставку"
                : kind == SkipOverlay.Kind.NEXT ? "Следующая серия" : "Пропустить титры";

        setLayerType(LAYER_TYPE_SOFTWARE, null); // ради тени setShadowLayer
        glass.setColor(GLASS);
        glass.setShadowLayer(SHADOW_R * s, 0, SHADOW_DY * s, SHADOW);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(STROKE * s);
        stroke.setColor(STROKE_C);
        fill.setColor(FILL);
        text.setTextSize(TEXT_SIZE * s);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        icon.setStyle(Paint.Style.FILL);
        setAlpha(0f);
    }

    int marginEnd() { return Math.round(MARGIN_END * s); }

    int marginBottom() { return Math.round(MARGIN_BOTTOM * s); }

    int enterMs() { return ENTER_MS; }

    int countdownMs() { return kind == SkipOverlay.Kind.INTRO ? INTRO_COUNTDOWN_MS : CREDITS_COUNTDOWN_MS; }

    void setProgress(float p) {
        progress = p;
        invalidate();
    }

    private float shadowPad() { return (SHADOW_R + SHADOW_DY) * s; }

    @Override
    protected void onMeasure(int w, int h) {
        float content = PAD_START + ICON + ICON_GAP + PAD_END;
        float width = content * s + text.measureText(label);
        float pad = shadowPad();
        setMeasuredDimension(Math.round(width + pad * 2), Math.round(HEIGHT * s + pad * 2));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float pad = shadowPad();
        box.set(pad, pad, w - pad, h - pad);
        clip.reset();
        clip.addRoundRect(box, RADIUS * s, RADIUS * s, Path.Direction.CW);
        sheen.setShader(new LinearGradient(0, box.top, 0, box.bottom,
                GLASS_TOP, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        setPivotX(w * 0.5f);
        setPivotY(h * 0.5f);
    }

    @Override
    protected void onDraw(Canvas c) {
        float r = RADIUS * s;
        c.drawRoundRect(box, r, r, glass);
        c.drawRoundRect(box, r, r, sheen);

        float front = box.left + box.width() * progress;

        // Заливка слева направо со светлым фронтом
        if (progress > 0) {
            c.save();
            c.clipPath(clip);
            c.clipRect(box.left, box.top, front, box.bottom);
            c.drawRect(box, fill);
            c.restore();

            float glow = 26 * s;
            edge.setShader(new LinearGradient(front - glow, 0, front + 2 * s, 0,
                    0x00FFFFFF, 0x99FFFFFF, Shader.TileMode.CLAMP));
            c.save();
            c.clipPath(clip);
            c.drawRect(Math.max(box.left, front - glow), box.top, front + 2 * s, box.bottom, edge);
            c.restore();
        }
        c.drawRoundRect(box, r, r, stroke);

        // Содержимое: светлое поверх стекла, тёмное поверх заливки
        c.save();
        c.clipRect(front, box.top, box.right, box.bottom);
        drawContent(c, ON_GLASS);
        c.restore();
        if (progress > 0) {
            c.save();
            c.clipRect(box.left, box.top, front, box.bottom);
            drawContent(c, ON_FILL);
            c.restore();
        }
    }

    private void drawContent(Canvas c, int color) {
        float cy = box.centerY();
        float x = box.left + PAD_START * s;
        icon.setColor(color);
        drawSkipIcon(c, x, cy, ICON * s);
        text.setColor(color);
        Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText(label, x + (ICON + ICON_GAP) * s, cy - (fm.ascent + fm.descent) / 2, text);
    }

    /** Два треугольника «вперёд» с чертой — значок перемотки/следующей. */
    private void drawSkipIcon(Canvas c, float x, float cy, float size) {
        float h = size * 0.62f;
        float w = size * 0.40f;
        Path p = new Path();
        for (int i = 0; i < 2; i++) {
            float sx = x + i * w * 0.92f;
            p.moveTo(sx, cy - h / 2);
            p.lineTo(sx + w, cy);
            p.lineTo(sx, cy + h / 2);
            p.close();
        }
        c.drawPath(p, icon);
        float bx = x + w * 1.92f + size * 0.04f;
        c.drawRoundRect(new RectF(bx, cy - h / 2, bx + size * 0.09f, cy + h / 2), size * 0.03f, size * 0.03f, icon);
    }

    void enter() {
        setAlpha(0f);
        setTranslationY(28 * s);
        setScaleX(0.92f);
        setScaleY(0.92f);
        animate().alpha(1f).translationY(0).scaleX(1f).scaleY(1f)
                .setDuration(ENTER_MS).setInterpolator(new OvershootInterpolator(1.15f)).start();
    }

    void press() {
        animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start();
    }

    void exit(final Runnable done) {
        animate().alpha(0f).translationX(36 * s).scaleX(0.97f).scaleY(0.97f)
                .setDuration(EXIT_MS).setInterpolator(new DecelerateInterpolator())
                .withEndAction(done).start();
    }
}
