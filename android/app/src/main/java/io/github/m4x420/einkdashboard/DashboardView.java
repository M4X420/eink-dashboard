package io.github.m4x420.einkdashboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/**
 * Zeichnet alle Kacheln selbst: keine Widgets, keine Animationen, keine Grauverlaeufe,
 * kein Pressed-State-Drawable – jede Zustandsaenderung waere sonst ein E-Ink-Refresh.
 */
final class DashboardView extends View {

    interface Listener {
        void onTileTapped(int index);

        /** Statuszeile EXIT_HOLD_MS lang gedrueckt: Notausgang aus dem Kiosk. */
        void onExitGesture();
    }

    private static final long EXIT_HOLD_MS = 5000;

    private static final int GAP = 24;
    private static final int STATUS_H = 44;

    private final Tile[] tiles;
    private final int columns;
    private final String message;
    private final EntityState[] states;
    private final boolean[] pending;
    private final Paint fill = new Paint();
    private final Paint border = new Paint();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect rect = new Rect();
    private String status = "";
    private boolean flashing;
    private int downIndex = -1;
    private Listener listener;
    private final Runnable exitGesture = () -> {
        if (listener != null) listener.onExitGesture();
    };

    /** @param message wird statt Kacheln angezeigt, wenn tiles leer ist (Zeilenumbruch mit \n). */
    DashboardView(Context context, Tile[] tiles, int columns, String message) {
        super(context);
        this.tiles = tiles;
        this.columns = Math.max(1, columns);
        this.message = message;
        this.states = new EntityState[tiles.length];
        this.pending = new boolean[tiles.length];
        fill.setStyle(Paint.Style.FILL);
        border.setStyle(Paint.Style.STROKE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    /** @return true, wenn sich sichtbar etwas geaendert hat. */
    boolean setState(int i, EntityState s) {
        boolean changed = pending[i] || !s.sameAs(states[i]);
        states[i] = s;
        pending[i] = false;
        return changed;
    }

    boolean isPending(int i) {
        return pending[i];
    }

    void setPending(int i, boolean p) {
        pending[i] = p;
    }

    boolean setStatus(String s) {
        if (s.equals(status)) return false;
        status = s;
        return true;
    }

    void invalidateTile(int i) {
        invalidate(tileRect(i, new Rect()));
    }

    void invalidateStatus() {
        invalidate(0, getHeight() - STATUS_H, getWidth(), getHeight());
    }

    /**
     * Full-Refresh gegen Ghosting. Auf dem Tolino ueber die Freescale-API (GC16),
     * sonst Fallback: komplett schwarz, dann normal zeichnen.
     */
    void flash() {
        if (Eink.fullRefresh(this)) return;
        flashing = true;
        invalidate();
        postDelayed(() -> {
            flashing = false;
            invalidate();
        }, Config.FLASH_MS);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (flashing) {
            canvas.drawColor(Color.BLACK);
            return;
        }
        canvas.drawColor(Color.WHITE);
        for (int i = 0; i < tiles.length; i++) {
            drawTile(canvas, i, tileRect(i, rect));
        }
        text.setColor(Color.BLACK);
        if (tiles.length == 0 && message != null) {
            text.setTextSize(30);
            String[] lines = message.split("\n");
            float y = getHeight() / 2f - (lines.length - 1) * 22;
            for (String line : lines) {
                canvas.drawText(line, getWidth() / 2f, y, text);
                y += 44;
            }
        }
        text.setTextSize(22);
        canvas.drawText(status, getWidth() / 2f, getHeight() - 14, text);
    }

    private void drawTile(Canvas c, int i, Rect r) {
        EntityState s = states[i];
        boolean active = s != null && isActive(s.state);
        int fg = active ? Color.WHITE : Color.BLACK;

        // "An" = invertierte Kachel: auf 16 Graustufen der staerkste moegliche Kontrast.
        if (active) {
            fill.setColor(Color.BLACK);
            c.drawRect(r, fill);
        } else {
            border.setColor(Color.BLACK);
            border.setStrokeWidth(4);
            c.drawRect(r, border);
        }
        if (pending[i]) {
            border.setColor(fg);
            border.setStrokeWidth(10);
            c.drawRect(r.left + 18, r.top + 18, r.right - 18, r.bottom - 18, border);
        }

        text.setColor(fg);
        String label = tiles[i].label != null ? tiles[i].label
                : (s != null && s.name != null ? s.name : tiles[i].entityId);
        drawFitted(c, label, r, r.top + r.height() * 0.35f, 38);
        drawFitted(c, formatValue(s), r, r.top + r.height() * 0.72f, 72);
    }

    private void drawFitted(Canvas c, String str, Rect r, float baseline, float maxSize) {
        float size = maxSize;
        text.setTextSize(size);
        float max = r.width() - 40;
        while (size > 16 && text.measureText(str) > max) {
            size -= 4;
            text.setTextSize(size);
        }
        c.drawText(str, r.exactCenterX(), baseline, text);
    }

    private static boolean isActive(String state) {
        return "on".equals(state) || "open".equals(state) || "playing".equals(state) || "home".equals(state);
    }

    private static String formatValue(EntityState s) {
        if (s == null || s.state == null) return "...";
        String v = s.state;
        if ("unavailable".equals(v) || "unknown".equals(v)) return "n/v";
        if ("on".equals(v)) return "AN";
        if ("off".equals(v)) return "AUS";
        if ("open".equals(v)) return "OFFEN";
        if ("closed".equals(v)) return "ZU";
        return s.unit != null && s.unit.length() > 0 ? v + " " + s.unit : v;
    }

    private Rect tileRect(int i, Rect out) {
        int cols = columns;
        int rows = (tiles.length + cols - 1) / cols;
        int w = (getWidth() - GAP * (cols + 1)) / cols;
        int h = (getHeight() - STATUS_H - GAP * (rows + 1)) / rows;
        int left = GAP + (i % cols) * (w + GAP);
        int top = GAP + (i / cols) * (h + GAP);
        out.set(left, top, left + w, top + h);
        return out;
    }

    /** Trefferflaeche um GAP/2 groesser als die gezeichnete Kachel (IR-Touch ist ungenau). */
    private int hitTest(int x, int y) {
        for (int i = 0; i < tiles.length; i++) {
            tileRect(i, rect).inset(-GAP / 2, -GAP / 2);
            if (rect.contains(x, y)) return i;
        }
        return -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getAction() & MotionEvent.ACTION_MASK;
        int hit = hitTest((int) e.getX(), (int) e.getY());
        if (action == MotionEvent.ACTION_DOWN) {
            downIndex = hit;
            // Statuszeile inkl. Abstand darueber (~8 mm), damit der IR-Touch sie sicher trifft.
            if (e.getY() >= getHeight() - STATUS_H - GAP) postDelayed(exitGesture, EXIT_HOLD_MS);
        } else if (action == MotionEvent.ACTION_UP) {
            removeCallbacks(exitGesture);
            if (hit >= 0 && hit == downIndex && listener != null) listener.onTileTapped(hit);
            downIndex = -1;
        } else if (action == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(exitGesture);
            downIndex = -1;
        }
        return true;
    }
}
