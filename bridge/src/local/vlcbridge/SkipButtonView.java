package local.vlcbridge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Choreographer;
import android.view.View;
import android.view.animation.PathInterpolator;

/**
 * Кнопка «Пропустить заставку» / «Следующая серия» по макету Даши
 * (design/SPEC.md, design/preview.html — числа совпадают).
 *
 * Тёмная стеклянная капсула, отсчёт до автопропуска — светлая заливка слева
 * направо, текст и иконка меняют цвет ровно по фронту заливки. Все движения
 * считаются от времени на каждом кадре (как SkipButton.params() в прототипе):
 * появление пружиной, «дыхание» фокуса, кивок на стрелки, подтверждение
 * (вверх и больше) и отмена (вниз и меньше).
 *
 * Размеры — пиксели кадра 1920×1080, переводятся через ширину экрана.
 */
class SkipButtonView extends View implements Choreographer.FrameCallback {

    interface Callbacks {
        /** Момент подтверждения (ОК или конец отсчёта) — тут и перематываем. */
        void onConfirm();

        /** Кнопка ушла с экрана — окно можно убирать. */
        void onGone();
    }

    // --- Геометрия, px кадра 1920×1080 (SPEC §2–4) ---
    private static final float H = 72, R = 36, PAD_L = 30, ICON = 32, GAP = 14, PAD_R = 36;
    private static final float BASELINE = 47, TEXT = 31, MARGIN = 48;
    static final float RIGHT = 80, BOTTOM = 180;

    // --- Отсчёт (SPEC §1, §6.2) ---
    private static final long FILL_DELAY = 450, INTRO_MS = 5000, CREDITS_MS = 8000;

    // --- Кривые (SPEC §6) ---
    private static final PathInterpolator DECEL = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final PathInterpolator ACCEL = new PathInterpolator(0.3f, 0f, 1f, 1f);
    private static final PathInterpolator INOUT = new PathInterpolator(0.45f, 0f, 0.55f, 1f);
    private static final PathInterpolator RETRACT = new PathInterpolator(0.4f, 0f, 0.2f, 1f);

    private enum Phase { LIVE, CONFIRM, CANCEL, END }

    private final SkipOverlay.Kind kind;
    private final float u;
    private final String label;
    private final long countdown;
    private Callbacks cb;

    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hairline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint front = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textLight = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textDark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconLight = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconDark = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF plate = new RectF();
    private final Path cap = new Path();
    private final Path icon = new Path();
    private final Matrix m = new Matrix();
    private Shader frontShader, glintShader;
    private float plateW, textW;

    // Время
    private long t0;                 // появление
    private long pausedAt = -1, pausedTotal;
    private Phase phase = Phase.LIVE;
    private long tPhase;             // начало подтверждения/отмены/ухода
    private float progressAtPhase;
    private long tPress = -1;        // ОК зажат
    private long tNod = -1;
    private float nodRotY, nodRotX, nodTx, nodTy;
    private boolean confirmedSent, goneSent;
    private float progress;          // для рисования
    private float frontA = 0, fillA = 0.96f, glowA = 0.07f, rimTop = 0.44f;
    private float contentA = 0, contentTx = 12, iconPush = 0, glintX = -1;

    SkipButtonView(Context ctx, SkipOverlay.Kind kind) {
        super(ctx);
        this.kind = kind;
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        u = Math.max(dm.widthPixels, dm.heightPixels) / 1920f;
        label = kind == SkipOverlay.Kind.INTRO ? "Пропустить заставку"
                : kind == SkipOverlay.Kind.NEXT ? "Следующая серия" : "Пропустить титры";
        countdown = kind == SkipOverlay.Kind.INTRO ? INTRO_MS : CREDITS_MS;

        setLayerType(LAYER_TYPE_SOFTWARE, null); // тени setShadowLayer у фигур
        setCameraDistance(1400 * dm.densityDpi / 72f);

        Typeface tf = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        for (Paint p : new Paint[]{textLight, textDark}) {
            p.setTypeface(tf);
            p.setTextSize(TEXT * u);
        }
        textLight.setColor(0xFFFFFFFF);
        textLight.setShadowLayer(2 * u, 0, 1 * u, 0x59000000);
        textDark.setColor(0xFF111113);
        iconLight.setColor(0xFFFFFFFF);
        iconDark.setColor(0xFF111113);

        shadow.setColor(0xFF000000);
        shadow.setShadowLayer(34 * u, 0, 14 * u, 0x73000000);
        glow.setColor(0xFFFFFFFF);
        hairline.setStyle(Paint.Style.STROKE);
        hairline.setStrokeWidth(1 * u);
        hairline.setColor(0x1A000000);
        glass.setColor(0xC216161A);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.5f * u);
        hot.setColor(0xFFFFFFFF);

        textW = textLight.measureText(label);
        plateW = (PAD_L + ICON + GAP + PAD_R) * u + textW;
        String[] glyph = kind == SkipOverlay.Kind.INTRO ? new String[]{
                "M3,9.4Q3,7 4.97,8.37L14.03,14.63Q16,16 14.03,17.37L4.97,23.63Q3,25 3,22.6Z",
                "M16,9.4Q16,7 17.97,8.37L27.03,14.63Q29,16 27.03,17.37L17.97,23.63Q16,25 16,22.6Z"}
                : new String[]{
                "M5,9.4Q5,7 7.11,8.15L19.39,14.85Q21.5,16 19.39,17.15L7.11,23.85Q5,25 5,22.6Z",
                "M25.5,7H25.5Q27.5,7 27.5,9V23Q27.5,25 25.5,25H25.5Q23.5,25 23.5,23V9Q23.5,7 25.5,7Z"};
        for (String d : glyph) SvgPath.append(icon, d);

        setAlpha(0f);
    }

    void setCallbacks(Callbacks cb) {
        this.cb = cb;
    }

    int windowX() { return Math.round((RIGHT - MARGIN) * u); }

    int windowY() { return Math.round((BOTTOM - MARGIN) * u); }

    // ------------------------------------------------------------------ размеры

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(Math.round(plateW + 2 * MARGIN * u), Math.round((H + 2 * MARGIN) * u));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float mg = MARGIN * u;
        plate.set(mg, mg, mg + plateW, mg + H * u);
        cap.reset();
        cap.addRoundRect(plate, R * u, R * u, Path.Direction.CW);
        setPivotX(plate.centerX());
        setPivotY(plate.centerY());

        sheen.setShader(new LinearGradient(0, plate.top, 0, plate.bottom,
                new int[]{0x24FFFFFF, 0x0DFFFFFF, 0x00FFFFFF, 0x1A000000},
                new float[]{0f, 0.46f, 0.60f, 1f}, Shader.TileMode.CLAMP));
        fill.setShader(new LinearGradient(0, plate.top, 0, plate.bottom,
                0xF7FFFFFF, 0xF2ECEDF1, Shader.TileMode.CLAMP));
        float fw = 42 * u;
        frontShader = new LinearGradient(0, 0, fw, 0,
                new int[]{0x57FFFFFF, 0x42FFFFFF, 0x29FFFFFF, 0x14FFFFFF, 0x08FFFFFF, 0x00FFFFFF},
                new float[]{0f, 4 / 42f, 10 / 42f, 18 / 42f, 28 / 42f, 1f}, Shader.TileMode.CLAMP);
        front.setShader(frontShader);
        glintShader = new LinearGradient(-60 * u, 0, 60 * u, 0,
                new int[]{0x00FFFFFF, 0x42FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
        glint.setShader(glintShader);
    }

    // ------------------------------------------------------------------ жизнь

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        t0 = SystemClock.uptimeMillis();
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(this);
        super.onDetachedFromWindow();
    }

    /** Видео на паузе — отсчёт стоит. */
    void setPaused(boolean paused) {
        long now = SystemClock.uptimeMillis();
        if (paused && pausedAt < 0) pausedAt = now;
        else if (!paused && pausedAt >= 0) {
            pausedTotal += now - pausedAt;
            pausedAt = -1;
        }
    }

    void pressDown() {
        if (phase == Phase.LIVE && tPress < 0) tPress = SystemClock.uptimeMillis();
    }

    void confirm() {
        if (phase != Phase.LIVE) return;
        startPhase(Phase.CONFIRM);
    }

    void cancel() {
        if (phase != Phase.LIVE) return;
        startPhase(Phase.CANCEL);
    }

    /** Отрезок кончился сам — тихий уход (SPEC §6.6). */
    void end() {
        if (phase != Phase.LIVE) return;
        startPhase(Phase.END);
    }

    /** Стрелки пульта: упругий кивок к нажатой стороне. */
    void nod(int dx, int dy) {
        if (phase != Phase.LIVE) return;
        tNod = SystemClock.uptimeMillis();
        nodRotY = dx * 214f;
        nodTx = dx * 430f;
        nodRotX = dy == 0 ? 0 : (dy < 0 ? 214f : -214f);
        nodTy = dy * 430f;
    }

    private void startPhase(Phase p) {
        progressAtPhase = progress;
        phase = p;
        tPhase = SystemClock.uptimeMillis();
        if (p == Phase.CONFIRM && cb != null && !confirmedSent) {
            confirmedSent = true;
            cb.onConfirm(); // перемотка в момент подтверждения, не после ухода
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        long now = SystemClock.uptimeMillis();
        long t = now - t0;
        float scale, ty = 0, alpha;

        // Появление (§6.1)
        scale = spring(t, 0.90f, 1.06f, 650, 0.62f, 0);
        ty = spring(t, 36, 0, 530, 0.82f, 0);
        alpha = DECEL.getInterpolation(clamp(t / 180f));
        contentA = DECEL.getInterpolation(clamp((t - 35) / 155f));
        contentTx = spring(t - 35, 12, 0, 530, 0.9f, 0);
        float g = (t - 195) / 700f;
        glintX = g >= 0 && g <= 1 ? INOUT.getInterpolation(g) : -1;

        // Дыхание фокуса (§6.3)
        float b = t < 420 ? 0 : (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * (t - 420) / 2800.0));
        rimTop = 0.44f + 0.16f * b;
        glowA = 0.07f + 0.07f * b;

        // Отсчёт (§6.2)
        long paused = pausedTotal + (pausedAt >= 0 ? now - pausedAt : 0);
        long tc = t - FILL_DELAY - (phase == Phase.LIVE ? paused : 0);
        if (phase == Phase.LIVE) {
            progress = clamp(tc / (float) countdown);
            frontA = DECEL.getInterpolation(clamp(tc / 280f));
            fillA = 0.96f;
            if (progress >= 1f) startPhase(Phase.CONFIRM);
        }

        // Нажатие ОК (§6.4): к 1.00 за 80 мс, тень ниже
        if (tPress >= 0 && phase == Phase.LIVE) {
            float k = DECEL.getInterpolation(clamp((now - tPress) / 80f));
            scale = scale + (1.00f - scale) * k;
            shadow.setShadowLayer((34 - 18 * k) * u, 0, (14 - 8 * k) * u, 0x73000000);
        }

        // Кивок на стрелки
        float rotX = 0, rotY = 0, tx = 0;
        if (tNod >= 0) {
            long tn = now - tNod;
            rotY = spring(tn, 0, 0, 1060, 0.42f, nodRotY);
            rotX = spring(tn, 0, 0, 1060, 0.42f, nodRotX);
            tx = spring(tn, 0, 0, 1060, 0.42f, nodTx) * u;
            ty += spring(tn, 0, 0, 1060, 0.42f, nodTy);
            if (tn > 600) tNod = -1;
        }

        iconPush = 0;
        if (phase != Phase.LIVE) {
            long tp = now - tPhase;
            switch (phase) {
                case CONFIRM: {
                    // Заливка добегает, отскок, толчок иконки, вспышка, уход вверх и больше
                    progress = progressAtPhase + (1 - progressAtPhase) * DECEL.getInterpolation(clamp(tp / 155f));
                    float bounce = spring(tp, tPress >= 0 ? 1.00f : 1.06f, 1.06f, 1220, 0.55f, 0);
                    iconPush = spring(tp, 0, 0, 860, 0.45f, 515);
                    float flash = tp < 85 ? DECEL.getInterpolation(tp / 85f)
                            : 1 - DECEL.getInterpolation(clamp((tp - 85) / 280f));
                    glowA += 0.30f * flash;
                    rimTop = Math.min(1f, rimTop + 0.30f * flash);
                    fillA = 0.96f + 0.04f * Math.min(1f, tp / 85f);
                    float out = ACCEL.getInterpolation(clamp((tp - 170) / 170f));
                    scale = bounce * (1 + 0.05f * out);
                    alpha = 1 - out;
                    if (tp >= 340) gone();
                    break;
                }
                case CANCEL: {
                    // Заливка втягивается, уход вниз и меньше
                    progress = progressAtPhase * (1 - RETRACT.getInterpolation(clamp(tp / 210f)));
                    frontA = 1 - DECEL.getInterpolation(clamp(tp / 125f));
                    float out = ACCEL.getInterpolation(clamp((tp - 70) / 195f));
                    alpha = 1 - out;
                    ty += 24 * out;
                    scale = 1.06f * (1 - 0.06f * out);
                    if (tp >= 265) gone();
                    break;
                }
                case END: {
                    float out = ACCEL.getInterpolation(clamp(tp / 210f));
                    alpha = 1 - out;
                    ty += 20 * out;
                    scale = 1.06f * (1 - 0.04f * out);
                    if (tp >= 210) gone();
                    break;
                }
                default:
                    break;
            }
        }

        setScaleX(scale);
        setScaleY(scale);
        setTranslationX(tx);
        setTranslationY(ty * u);
        setRotationX(rotX); // пружина с v0 214°/с даёт пик ≈ 3.9°
        setRotationY(rotY);
        setAlpha(alpha);
        invalidate();
        if (!goneSent) Choreographer.getInstance().postFrameCallback(this);
    }

    private void gone() {
        if (goneSent) return;
        goneSent = true;
        setAlpha(0);
        if (cb != null) cb.onGone();
    }

    // ------------------------------------------------------------------ рисование (§5)

    @Override
    protected void onDraw(Canvas c) {
        float r = R * u;
        float fx = plate.left + plate.width() * progress;

        // 1–2. Тень и свечение — снаружи капсулы, чтобы не просвечивали сквозь стекло
        c.save();
        c.clipOutPath(cap);
        c.drawPath(cap, shadow);
        glow.setShadowLayer(22 * u, 0, 0, ((int) (clamp(glowA) * 255) << 24) | 0xFFFFFF);
        c.drawPath(cap, glow);
        c.restore();

        // 3. Волосяная кромка снаружи
        RectF outer = new RectF(plate);
        outer.inset(-0.5f * u, -0.5f * u);
        c.drawRoundRect(outer, r + 0.5f * u, r + 0.5f * u, hairline);

        // 4–5. Стекло
        c.drawPath(cap, glass);
        c.drawPath(cap, sheen);

        // 6. Блик при появлении
        if (glintX >= 0) {
            float x = plate.left + plate.width() * (-0.35f + 1.60f * glintX);
            c.save();
            c.clipPath(cap);
            m.reset();
            m.setTranslate(x, 0);
            glintShader.setLocalMatrix(m);
            c.skew((float) Math.tan(Math.toRadians(-18)), 0);
            c.drawRect(x - 60 * u - plate.height(), plate.top, x + 60 * u + plate.height(), plate.bottom, glint);
            c.restore();
        }

        // 7. Заливка
        if (progress > 0) {
            c.save();
            c.clipPath(cap);
            c.clipRect(plate.left, plate.top, fx, plate.bottom);
            fill.setAlpha(Math.round(fillA * 255));
            c.drawRect(plate, fill);
            c.restore();
        }

        // 8. Фронт заливки: горячая кромка и засветка вперёд
        if (progress > 0 && progress < 1 && frontA > 0) {
            c.save();
            c.clipPath(cap);
            hot.setAlpha(Math.round(255 * frontA));
            c.drawRect(fx - 2 * u, plate.top, fx, plate.bottom, hot);
            m.reset();
            m.setTranslate(fx, 0);
            frontShader.setLocalMatrix(m);
            front.setAlpha(Math.round(255 * frontA));
            c.drawRect(fx, plate.top, fx + 42 * u, plate.bottom, front);
            c.restore();
        }

        // 9–10. Содержимое: светлое на стекле, тёмное на заливке
        if (contentA > 0) {
            int a = Math.round(255 * contentA);
            c.save();
            c.clipRect(fx, plate.top, plate.right, plate.bottom);
            drawContent(c, iconLight, textLight, a);
            c.restore();
            if (progress > 0) {
                c.save();
                c.clipRect(plate.left, plate.top, fx, plate.bottom);
                drawContent(c, iconDark, textDark, a);
                c.restore();
            }
        }

        // 11. Обводка с градиентом сверху вниз
        rim.setShader(new LinearGradient(0, plate.top, 0, plate.bottom,
                new int[]{((int) (clamp(rimTop) * 255) << 24) | 0xFFFFFF, 0x1FFFFFFF, 0x38FFFFFF},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        RectF in = new RectF(plate);
        in.inset(0.75f * u, 0.75f * u);
        c.drawRoundRect(in, r - 0.75f * u, r - 0.75f * u, rim);
    }

    private void drawContent(Canvas c, Paint iconPaint, Paint textPaint, int alpha) {
        float x = plate.left + PAD_L * u + contentTx * u;
        float iconTop = plate.top + (H - ICON) / 2 * u;
        iconPaint.setAlpha(alpha);
        c.save();
        c.translate(x + iconPush * u, iconTop);
        c.scale(u, u); // глиф в координатах 32×32
        c.drawPath(icon, iconPaint);
        c.restore();
        textPaint.setAlpha(alpha);
        c.drawText(label, x + (ICON + GAP) * u, plate.top + BASELINE * u, textPaint);
    }

    // ------------------------------------------------------------------ математика

    /** Затухающая пружина в замкнутом виде (SPEC §6): x0 → x1, v0 ед/с, t мс. */
    static float spring(float tMs, float x0, float x1, float k, float z, float v0) {
        if (tMs <= 0) return x0;
        double s = tMs / 1000.0, w = Math.sqrt(k), y0 = x0 - x1;
        if (z < 1) {
            double wd = w * Math.sqrt(1 - z * z);
            return (float) (x1 + Math.exp(-z * w * s) * (y0 * Math.cos(wd * s) + (v0 + z * w * y0) / wd * Math.sin(wd * s)));
        }
        return (float) (x1 + Math.exp(-w * s) * (y0 + (v0 + w * y0) * s));
    }

    private static float clamp(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    /** Разбор SVG-пути из макета: только M L H V Q Z, абсолютные координаты. */
    static final class SvgPath {
        static void append(Path p, String d) {
            java.util.regex.Matcher mt = java.util.regex.Pattern
                    .compile("([MLHVQZ])([^MLHVQZ]*)").matcher(d);
            float cx = 0, cy = 0;
            while (mt.find()) {
                char cmd = mt.group(1).charAt(0);
                String[] s = mt.group(2).trim().isEmpty() ? new String[0] : mt.group(2).trim().split("[ ,]+");
                float[] v = new float[s.length];
                for (int i = 0; i < s.length; i++) v[i] = Float.parseFloat(s[i]);
                switch (cmd) {
                    case 'M': p.moveTo(cx = v[0], cy = v[1]); break;
                    case 'L': p.lineTo(cx = v[0], cy = v[1]); break;
                    case 'H': p.lineTo(cx = v[0], cy); break;
                    case 'V': p.lineTo(cx, cy = v[0]); break;
                    case 'Q': p.quadTo(v[0], v[1], cx = v[2], cy = v[3]); break;
                    case 'Z': p.close(); break;
                    default: break;
                }
            }
        }
    }
}
