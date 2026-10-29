package jp.yuta.slmpmonitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/**
 * 操作画面の描画とタッチ処理。
 * 運転モード：ボタン操作・数値入力・画面切替
 * 編集モード：部品の選択・ドラッグ移動・右下つまみでサイズ変更
 */
public class PanelView extends View {

    public interface Host {
        /** 操作してよければ null、だめなら理由 */
        String canOperate();

        void message(String msg);

        void confirmThen(String msg, Runnable action);

        void requestInput(Panel.Part p);

        void switchScreen(int index);

        /** 操作が行われた（自動ロックのタイマー延長用） */
        void operated();

        /** 編集モードで選択中の部品をタップ */
        void editPart(Panel.Part p);

        void selectionChanged(Panel.Part p);
    }

    private static final int BG_OUTSIDE = 0xFF101316;
    private static final int BG_PANEL = 0xFF1E2329;
    private static final int COLOR_SEL = 0xFF00E5FF;

    private Panel panel;
    private int screenIndex;
    private boolean editMode;
    private boolean fresh = true;
    private Host host;
    private final DeviceMemory mem = PlcLink.mem;

    private float scale = 1, ox, oy;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    // 運転モードのタッチ
    private Panel.Part pressed;
    private boolean pressedInside;
    private Panel.Part momentaryOn;
    private long momentaryOnAt;
    private Runnable pendingRelease;

    // 編集モードのタッチ
    private Panel.Part selected;
    private boolean resizing, moved, wasSelected;
    private float downX, downY;
    private int origX, origY, origW, origH;

    public PanelView(Context c) {
        super(c);
        stroke.setStyle(Paint.Style.STROKE);
        text.setTextAlign(Paint.Align.CENTER);
        setContentDescription("panel");
    }

    public void setHost(Host h) {
        host = h;
    }

    public void setPanel(Panel p, int screen) {
        panel = p;
        screenIndex = screen;
        selected = null;
        recalc();
        invalidate();
    }

    public void setScreen(int index) {
        releaseMomentary();
        pressed = null;
        screenIndex = index;
        selected = null;
        if (host != null) host.selectionChanged(null);
        invalidate();
    }

    public void setEditMode(boolean e) {
        releaseMomentary();
        pressed = null;
        editMode = e;
        selected = null;
        if (host != null) host.selectionChanged(null);
        invalidate();
    }

    public void setFresh(boolean f) {
        fresh = f;
    }

    public Panel.Part selected() {
        return selected;
    }

    public void select(Panel.Part p) {
        selected = p;
        if (host != null) host.selectionChanged(p);
        invalidate();
    }

    private Panel.Screen screen() {
        if (panel == null || panel.screens.isEmpty()) return null;
        return panel.screens.get(Math.min(screenIndex, panel.screens.size() - 1));
    }

    // ------------------------------------------------------------ 座標

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        recalc();
    }

    private void recalc() {
        if (panel == null || getWidth() == 0) return;
        scale = Math.min((float) getWidth() / panel.width(), (float) getHeight() / panel.height());
        ox = (getWidth() - panel.width() * scale) / 2f;
        oy = (getHeight() - panel.height() * scale) / 2f;
    }

    private float vx(float sx) {
        return (sx - ox) / scale;
    }

    private float vy(float sy) {
        return (sy - oy) / scale;
    }

    private void toScreen(Panel.Part p, RectF out) {
        out.set(ox + p.x * scale, oy + p.y * scale, ox + (p.x + p.w) * scale, oy + (p.y + p.h) * scale);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    // ------------------------------------------------------------ 描画

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(BG_OUTSIDE);
        Panel.Screen s = screen();
        if (s == null) return;
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(BG_PANEL);
        c.drawRect(ox, oy, ox + panel.width() * scale, oy + panel.height() * scale, fill);

        if (editMode) drawGrid(c);
        for (Panel.Part p : s.parts) drawPart(c, p);
        if (editMode) {
            for (Panel.Part p : s.parts) drawEditInfo(c, p);
            if (selected != null && s.parts.contains(selected)) drawSelection(c, selected);
        }
    }

    private void drawGrid(Canvas c) {
        fill.setColor(0x33FFFFFF);
        float step = Panel.GRID * 2 * scale;
        float rad = Math.max(1f, scale);
        for (float x = ox; x <= ox + panel.width() * scale + 0.5f; x += step) {
            for (float y = oy; y <= oy + panel.height() * scale + 0.5f; y += step) {
                c.drawCircle(x, y, rad, fill);
            }
        }
    }

    private void drawPart(Canvas c, Panel.Part p) {
        toScreen(p, r);
        float rad = Math.min(r.width(), r.height()) * 0.12f;
        boolean stale = !fresh && !editMode;
        switch (p.type) {
            case LAMP: {
                boolean on = p.isOn(mem);
                fill.setColor(on ? p.onColor : p.offColor);
                c.drawRoundRect(r, rad, rad, fill);
                if (on) {   // 点灯時の光り
                    stroke.setColor(0x66FFFFFF);
                    stroke.setStrokeWidth(Math.max(2f, 2 * scale));
                    RectF in = new RectF(r);
                    in.inset(3 * scale, 3 * scale);
                    c.drawRoundRect(in, rad, rad, stroke);
                }
                String t = on ? (p.onText.isEmpty() ? p.label : p.onText) : (p.offText.isEmpty() ? p.label : p.offText);
                drawFitText(c, t, r, contrast(on ? p.onColor : p.offColor), true, 0.42f);
                if (stale) dim(c, rad);
                break;
            }
            case BUTTON:
            case SWITCH: {
                boolean on = p.type == Panel.Type.BUTTON ? p.isOn(mem) : false;
                boolean down = (p == pressed && pressedInside) || p == momentaryOn;
                int base = on ? p.onColor : p.offColor;
                // 影（押していないときだけ）
                float depth = down ? 0 : 4 * scale;
                if (depth > 0) {
                    fill.setColor(0xFF0B0D10);
                    RectF sh = new RectF(r);
                    sh.offset(0, depth);
                    c.drawRoundRect(sh, rad, rad, fill);
                }
                RectF b = new RectF(r);
                if (down) b.offset(0, 3 * scale);
                fill.setColor(down ? darker(base) : base);
                c.drawRoundRect(b, rad, rad, fill);
                stroke.setColor(0x55FFFFFF);
                stroke.setStrokeWidth(Math.max(1.5f, 1.5f * scale));
                c.drawRoundRect(b, rad, rad, stroke);
                String t = p.label;
                if (p.type == Panel.Type.BUTTON) {
                    if (on && !p.onText.isEmpty()) t = p.onText;
                    if (!on && !p.offText.isEmpty()) t = p.offText;
                } else {
                    t = "▶ " + t;
                }
                drawFitText(c, t, b, contrast(down ? darker(base) : base), true, 0.38f);
                break;
            }
            case NUMBER:
            case INPUT: {
                boolean input = p.type == Panel.Type.INPUT;
                boolean down = input && p == pressed && pressedInside;
                fill.setColor(down ? 0xFF2B3440 : 0xFF0C0F12);
                c.drawRoundRect(r, rad * 0.6f, rad * 0.6f, fill);
                stroke.setColor(input ? 0xFF64B5F6 : 0xFF455A64);
                stroke.setStrokeWidth(Math.max(1.5f, (input ? 2.5f : 1.5f) * scale));
                c.drawRoundRect(r, rad * 0.6f, rad * 0.6f, stroke);

                float pad = Math.min(r.height(), r.width()) * 0.1f;
                boolean showLabel = !p.label.isEmpty() && p.h >= 50;
                RectF valueArea = new RectF(r.left + pad, r.top, r.right - pad, r.bottom);
                if (showLabel) {
                    RectF la = new RectF(r.left + pad, r.top + pad * 0.5f, r.right - pad, r.top + r.height() * 0.36f);
                    text.setTextAlign(Paint.Align.LEFT);
                    drawFitTextAligned(c, p.label + (input ? "  ✎" : ""), la, 0xFFB0BEC5, false, 0.9f, Paint.Align.LEFT);
                    valueArea.top = la.bottom;
                }
                String v = p.displayValue(mem);
                String unit = p.unit.isEmpty() ? "" : " " + p.unit;
                int vc = stale ? 0xFF78909C : (input ? 0xFFFFFFFF : 0xFF69F0AE);
                drawFitTextAligned(c, v + unit, valueArea, vc, true, showLabel ? 0.8f : 0.6f, Paint.Align.RIGHT);
                break;
            }
            case TEXT:
            default:
                drawFitText(c, p.label, r, p.textColor, true, 0.7f);
        }
    }

    private void dim(Canvas c, float rad) {
        fill.setColor(0x99202428);
        c.drawRoundRect(r, rad, rad, fill);
    }

    private void drawEditInfo(Canvas c, Panel.Part p) {
        toScreen(p, r);
        stroke.setColor(0x55FFFFFF);
        stroke.setStrokeWidth(1);
        c.drawRect(r, stroke);
        String info = p.deviceSummary();
        if (info.isEmpty()) return;
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(dp(10));
        text.setTextAlign(Paint.Align.LEFT);
        float tw = text.measureText(info);
        fill.setColor(0xCC000000);
        c.drawRect(r.left, r.bottom - dp(13), r.left + tw + dp(6), r.bottom, fill);
        text.setColor(0xFFFFEB3B);
        c.drawText(info, r.left + dp(3), r.bottom - dp(3), text);
    }

    private void drawSelection(Canvas c, Panel.Part p) {
        toScreen(p, r);
        stroke.setColor(COLOR_SEL);
        stroke.setStrokeWidth(dp(2));
        c.drawRect(r, stroke);
        float hs = handleSize();
        fill.setColor(COLOR_SEL);
        c.drawRect(r.right - hs, r.bottom - hs, r.right, r.bottom, fill);
        stroke.setColor(0xFF000000);
        stroke.setStrokeWidth(dp(1.5f));
        // つまみの斜線
        c.drawLine(r.right - hs * 0.75f, r.bottom - hs * 0.2f, r.right - hs * 0.2f, r.bottom - hs * 0.75f, stroke);
        c.drawLine(r.right - hs * 0.45f, r.bottom - hs * 0.2f, r.right - hs * 0.2f, r.bottom - hs * 0.45f, stroke);
    }

    private float handleSize() {
        return dp(22);
    }

    private void drawFitText(Canvas c, String s, RectF box, int color, boolean bold, float maxFrac) {
        drawFitTextAligned(c, s, box, color, bold, maxFrac, Paint.Align.CENTER);
    }

    /** 枠に収まる大きさで文字を描く（改行対応） */
    private void drawFitTextAligned(Canvas c, String s, RectF box, int color, boolean bold, float maxFrac, Paint.Align align) {
        if (s == null || s.isEmpty()) return;
        String[] lines = s.split("\n");
        text.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        text.setTextAlign(align);
        float pad = Math.min(box.width(), box.height()) * 0.08f;
        float availW = box.width() - pad * 2;
        float availH = box.height() - pad * 2;
        float size = Math.min(availH * maxFrac, availH / lines.length * 0.85f);
        size = Math.max(size, 6);
        text.setTextSize(size);
        float widest = 0;
        for (String l : lines) widest = Math.max(widest, text.measureText(l));
        if (widest > availW && widest > 0) {
            size = Math.max(6, size * availW / widest);
            text.setTextSize(size);
        }
        text.setColor(color);
        Paint.FontMetrics fm = text.getFontMetrics();
        float lineH = fm.descent - fm.ascent;
        float total = lineH * lines.length;
        float y = box.centerY() - total / 2f - fm.ascent;
        float x = align == Paint.Align.LEFT ? box.left + pad : align == Paint.Align.RIGHT ? box.right - pad : box.centerX();
        for (String l : lines) {
            c.drawText(l, x, y, text);
            y += lineH;
        }
    }

    private static int contrast(int bg) {
        int rr = (bg >> 16) & 0xFF, gg = (bg >> 8) & 0xFF, bb = bg & 0xFF;
        double lum = 0.299 * rr + 0.587 * gg + 0.114 * bb;
        return lum > 160 ? 0xFF000000 : 0xFFFFFFFF;
    }

    private static int darker(int c) {
        int rr = (int) (((c >> 16) & 0xFF) * 0.7), gg = (int) (((c >> 8) & 0xFF) * 0.7), bb = (int) ((c & 0xFF) * 0.7);
        return 0xFF000000 | (rr << 16) | (gg << 8) | bb;
    }

    // ------------------------------------------------------------ タッチ

    private Panel.Part hit(float vx, float vy) {
        Panel.Screen s = screen();
        if (s == null) return null;
        for (int i = s.parts.size() - 1; i >= 0; i--) {
            Panel.Part p = s.parts.get(i);
            if (p.contains(vx, vy)) return p;
        }
        return null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (panel == null || host == null) return false;
        return editMode ? touchEdit(e) : touchRun(e);
    }

    private boolean touchRun(MotionEvent e) {
        float vx = vx(e.getX()), vy = vy(e.getY());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                Panel.Part p = hit(vx, vy);
                if (p == null || p.type == Panel.Type.LAMP || p.type == Panel.Type.NUMBER || p.type == Panel.Type.TEXT) {
                    pressed = null;
                    return true;
                }
                if (p.operable()) {
                    String why = host.canOperate();
                    if (why != null) {
                        host.message(why);
                        pressed = null;
                        return true;
                    }
                }
                pressed = p;
                pressedInside = true;
                if (p.type == Panel.Type.BUTTON && p.action == Panel.Action.MOMENTARY) {
                    pressMomentary(p);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (pressed != null) {
                    boolean in = pressed.contains(vx, vy);
                    if (in != pressedInside) {
                        pressedInside = in;
                        invalidate();
                    }
                }
                return true;
            case MotionEvent.ACTION_UP: {
                Panel.Part p = pressed;
                boolean inside = p != null && p.contains(vx, vy);
                pressed = null;
                if (p != null) {
                    if (p.type == Panel.Type.BUTTON && p.action == Panel.Action.MOMENTARY) {
                        releaseMomentaryLater();
                    } else if (inside) {
                        tapped(p);
                    }
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                pressed = null;
                releaseMomentaryLater();
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private void tapped(final Panel.Part p) {
        switch (p.type) {
            case SWITCH:
                host.switchScreen(p.target);
                return;
            case INPUT:
                host.requestInput(p);
                return;
            case BUTTON: {
                final Runnable act = () -> {
                    String why = host.canOperate();
                    if (why != null) {
                        host.message(why);
                        return;
                    }
                    try {
                        switch (p.action) {
                            case ALTERNATE:
                                p.writeBit(mem, !p.readBit(mem));
                                break;
                            case SET:
                                p.writeBit(mem, true);
                                break;
                            case RESET:
                                p.writeBit(mem, false);
                                break;
                            case WORD_SET:
                                p.writeNumber(mem, p.setValue);
                                break;
                            default:
                        }
                        host.operated();
                    } catch (RuntimeException ex) {
                        host.message("設定エラー: " + ex.getMessage());
                    }
                    invalidate();
                };
                if (p.confirm) {
                    String what = p.action == Panel.Action.WORD_SET ? p.device + " に " + p.setValue + " を書込みます"
                            : p.action == Panel.Action.ALTERNATE ? p.device + " を切り替えます"
                            : p.device + " を " + (p.action == Panel.Action.SET ? "ON" : "OFF") + " にします";
                    host.confirmThen("「" + p.label + "」\n" + what, act);
                } else {
                    act.run();
                }
                return;
            }
            default:
        }
    }

    private void pressMomentary(Panel.Part p) {
        if (pendingRelease != null) {
            removeCallbacks(pendingRelease);
            pendingRelease.run();
        }
        try {
            p.writeBit(mem, true);
            momentaryOn = p;
            momentaryOnAt = System.currentTimeMillis();
            host.operated();
        } catch (RuntimeException ex) {
            host.message("設定エラー: " + ex.getMessage());
        }
    }

    /** 最小ON時間を守ってからOFF（PLCの読出し周期より短い押下を取りこぼさないため） */
    private void releaseMomentaryLater() {
        final Panel.Part p = momentaryOn;
        if (p == null) return;
        long remain = p.minOnMs - (System.currentTimeMillis() - momentaryOnAt);
        pendingRelease = () -> {
            pendingRelease = null;
            try {
                p.writeBit(mem, false);
            } catch (RuntimeException ignored) {
            }
            if (momentaryOn == p) momentaryOn = null;
            invalidate();
        };
        if (remain > 0) postDelayed(pendingRelease, remain);
        else pendingRelease.run();
    }

    /** 画面を離れるときなど、押しっぱなしのモーメンタリを即OFF */
    public void releaseMomentary() {
        if (pendingRelease != null) {
            removeCallbacks(pendingRelease);
            pendingRelease.run();
        }
        if (momentaryOn != null) {
            try {
                momentaryOn.writeBit(mem, false);
            } catch (RuntimeException ignored) {
            }
            momentaryOn = null;
        }
        pressed = null;
        invalidate();
    }

    private boolean touchEdit(MotionEvent e) {
        float vx = vx(e.getX()), vy = vy(e.getY());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                moved = false;
                resizing = false;
                downX = e.getX();
                downY = e.getY();
                if (selected != null) {
                    toScreen(selected, r);
                    float hs = handleSize() * 1.4f;   // つまみは少し広めに判定
                    if (e.getX() >= r.right - hs && e.getX() <= r.right + hs * 0.3f
                            && e.getY() >= r.bottom - hs && e.getY() <= r.bottom + hs * 0.3f) {
                        resizing = true;
                        wasSelected = true;
                        remember(selected);
                        return true;
                    }
                }
                Panel.Part p = hit(vx, vy);
                wasSelected = p != null && p == selected;
                if (p != selected) select(p);
                if (p != null) remember(p);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (selected == null) return true;
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (!moved && Math.hypot(dx, dy) < dp(6)) return true;
                moved = true;
                float vdx = dx / scale, vdy = dy / scale;
                if (resizing) {
                    selected.w = Math.max(Panel.GRID * 2, Panel.snap(origW + vdx));
                    selected.h = Math.max(Panel.GRID * 2, Panel.snap(origH + vdy));
                    selected.w = Math.min(selected.w, panel.width() - selected.x);
                    selected.h = Math.min(selected.h, panel.height() - selected.y);
                } else {
                    selected.x = Panel.snap(origX + vdx);
                    selected.y = Panel.snap(origY + vdy);
                    panel.clamp(selected);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (selected != null && !moved && wasSelected && !resizing) {
                    host.editPart(selected);
                }
                if (moved) host.selectionChanged(selected);
                return true;
            default:
                return true;
        }
    }

    private void remember(Panel.Part p) {
        origX = p.x;
        origY = p.y;
        origW = p.w;
        origH = p.h;
    }

    @Override
    protected void onDetachedFromWindow() {
        releaseMomentary();
        super.onDetachedFromWindow();
    }
}
