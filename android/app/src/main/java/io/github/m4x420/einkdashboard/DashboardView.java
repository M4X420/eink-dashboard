package io.github.m4x420.einkdashboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/**
 * Zeichnet alle Kacheln selbst: keine Widgets, keine Animationen, keine Grauverlaeufe,
 * kein Pressed-State-Drawable – jede Zustandsaenderung waere sonst ein E-Ink-Refresh.
 *
 * Aufbau von oben nach unten: Kopfzeile (Akku), Kacheln, ggf. Blaetterleiste, Statuszeile.
 * Passen nicht alle Kacheln auf den Bildschirm, werden sie auf Seiten verteilt.
 */
final class DashboardView extends View {

    interface Listener {
        void onTileTapped(int index);

        /** Statuszeile EXIT_HOLD_MS lang gedrueckt: Notausgang aus dem Kiosk. */
        void onExitGesture();

        void onPageChanged(int page);
    }

    private static final long EXIT_HOLD_MS = 5000;

    private static final int GAP = 24;
    private static final int HEADER_H = 52;
    private static final int STATUS_H = 44;
    private static final int NAV_H = 104;
    private static final int NAV_BUTTON_W = 220;
    /** Mindesthoehe einer Kachelzeile inkl. Abstand (~23 mm): darunter wird geblaettert. */
    private static final int MIN_ROW_H = 190;

    private final Tile[] tiles;
    private final int columns;
    private final String message;
    private final EntityState[] states;
    private final boolean[] pending;
    private final Paint fill = new Paint();
    private final Paint border = new Paint();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrowPath = new Path();
    private final Rect rect = new Rect();
    private String status = "";
    private int batteryPercent = -1;
    private boolean charging;
    private boolean flashing;
    private int downIndex = -1;
    private int downNav;
    private Listener listener;
    private final Runnable exitGesture = () -> {
        if (listener != null) listener.onExitGesture();
    };

    // Seitenaufteilung, abhaengig von der Groesse der View (computePages)
    private int rowsPerPage = 1;
    private int pageSize = 1;
    private int pageCount = 1;
    private int page;

    /**
     * @param message wird statt Kacheln angezeigt, wenn tiles leer ist (Zeilenumbruch mit \n).
     * @param page    Seite, die angezeigt werden soll (wird auf die vorhandenen Seiten begrenzt).
     */
    DashboardView(Context context, Tile[] tiles, int columns, String message, int page) {
        super(context);
        this.tiles = tiles;
        this.columns = Math.max(1, columns);
        this.message = message;
        this.states = new EntityState[tiles.length];
        this.pending = new boolean[tiles.length];
        this.page = page;
        fill.setStyle(Paint.Style.FILL);
        border.setStyle(Paint.Style.STROKE);
        arrow.setStyle(Paint.Style.FILL);
        arrow.setColor(Color.BLACK);
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

    /** @return true, wenn sich die Anzeige aendert. */
    boolean setBattery(int percent, boolean charging) {
        if (percent == batteryPercent && charging == this.charging) return false;
        batteryPercent = percent;
        this.charging = charging;
        return true;
    }

    int getPage() {
        return page;
    }

    void invalidateTile(int i) {
        if (!isOnPage(i)) return;
        invalidate(tileRect(i, new Rect()));
    }

    void invalidateStatus() {
        invalidate(0, getHeight() - STATUS_H, getWidth(), getHeight());
    }

    void invalidateHeader() {
        invalidate(0, 0, getWidth(), HEADER_H);
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
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        computePages();
    }

    /** Wie viele Zeilen passen auf eine Seite? Erst ohne Blaetterleiste versuchen, sonst mit. */
    private void computePages() {
        int totalRows = (tiles.length + columns - 1) / columns;
        int area = getHeight() - HEADER_H - STATUS_H - GAP;
        int fitWithoutNav = Math.max(1, area / MIN_ROW_H);
        if (totalRows <= fitWithoutNav) {
            rowsPerPage = Math.max(1, totalRows);
        } else {
            rowsPerPage = Math.max(1, (area - NAV_H) / MIN_ROW_H);
        }
        pageSize = rowsPerPage * columns;
        pageCount = Math.max(1, (tiles.length + pageSize - 1) / pageSize);
        page = Math.max(0, Math.min(page, pageCount - 1));
    }

    private boolean paged() {
        return pageCount > 1;
    }

    private boolean isOnPage(int i) {
        return i >= page * pageSize && i < (page + 1) * pageSize;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (flashing) {
            canvas.drawColor(Color.BLACK);
            return;
        }
        canvas.drawColor(Color.WHITE);
        drawHeader(canvas);

        int first = page * pageSize;
        int last = Math.min(tiles.length, first + pageSize);
        for (int i = first; i < last; i++) {
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
        if (paged()) drawNav(canvas);

        text.setColor(Color.BLACK);
        text.setTextSize(22);
        canvas.drawText(status, getWidth() / 2f, getHeight() - 14, text);
    }

    /** Akku oben rechts: Symbol mit Fuellstand plus Prozent. */
    private void drawHeader(Canvas c) {
        if (batteryPercent < 0) return;
        int right = getWidth() - GAP;
        int midY = HEADER_H / 2;

        // Symbol: Gehaeuse 48x24, Pol rechts, Fuellung nach Ladestand
        int bodyRight = right - 6;
        int bodyLeft = bodyRight - 48;
        border.setColor(Color.BLACK);
        border.setStrokeWidth(3);
        c.drawRect(bodyLeft, midY - 12, bodyRight, midY + 12, border);
        fill.setColor(Color.BLACK);
        c.drawRect(bodyRight, midY - 5, right, midY + 5, fill);
        int inner = 42 * Math.max(0, Math.min(100, batteryPercent)) / 100;
        if (inner > 0) c.drawRect(bodyLeft + 3, midY - 9, bodyLeft + 3 + inner, midY + 9, fill);

        String label = charging ? L10n.t("battery_charging", batteryPercent) : L10n.t("battery", batteryPercent);
        text.setColor(Color.BLACK);
        text.setTextSize(26);
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText(label, bodyLeft - 12, midY + 9, text);
        text.setTextAlign(Paint.Align.CENTER);
    }

    /** Pfeiltasten links/rechts (nur wenn es dorthin etwas gibt), Seitenzahl in der Mitte. */
    private void drawNav(Canvas c) {
        if (page > 0) drawArrowButton(c, navButton(-1, rect), -1);
        if (page < pageCount - 1) drawArrowButton(c, navButton(1, rect), 1);
        text.setColor(Color.BLACK);
        text.setTextSize(30);
        int top = getHeight() - STATUS_H - NAV_H;
        c.drawText(L10n.t("page", page + 1, pageCount), getWidth() / 2f, top + NAV_H / 2f + 10, text);
    }

    private void drawArrowButton(Canvas c, Rect r, int dir) {
        border.setColor(Color.BLACK);
        border.setStrokeWidth(4);
        c.drawRect(r, border);
        float cx = r.exactCenterX();
        float cy = r.exactCenterY();
        float w = 26;
        float h = 30;
        arrowPath.reset();
        arrowPath.moveTo(cx + dir * w, cy);
        arrowPath.lineTo(cx - dir * w, cy - h);
        arrowPath.lineTo(cx - dir * w, cy + h);
        arrowPath.close();
        c.drawPath(arrowPath, arrow);
    }

    private Rect navButton(int dir, Rect out) {
        int top = getHeight() - STATUS_H - NAV_H + GAP / 2;
        int bottom = getHeight() - STATUS_H - GAP / 2;
        if (dir < 0) out.set(GAP, top, GAP + NAV_BUTTON_W, bottom);
        else out.set(getWidth() - GAP - NAV_BUTTON_W, top, getWidth() - GAP, bottom);
        return out;
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
        drawFitted(c, label, r, r.top + r.height() * 0.35f, Math.min(38, r.height() * 0.2f));
        drawFitted(c, formatValue(s), r, r.top + r.height() * 0.75f, Math.min(72, r.height() * 0.36f));
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
        if ("unavailable".equals(v) || "unknown".equals(v)) return L10n.t("unavailable");
        if ("on".equals(v)) return L10n.t("on");
        if ("off".equals(v)) return L10n.t("off");
        if ("open".equals(v)) return L10n.t("open");
        if ("closed".equals(v)) return L10n.t("closed");
        return s.unit != null && s.unit.length() > 0 ? v + " " + s.unit : v;
    }

    /** Rechteck der Kachel mit globalem Index i (muss auf der aktuellen Seite liegen). */
    private Rect tileRect(int i, Rect out) {
        int local = i - page * pageSize;
        int areaTop = HEADER_H;
        int areaBottom = getHeight() - STATUS_H - (paged() ? NAV_H : 0);
        int w = (getWidth() - GAP * (columns + 1)) / columns;
        int h = (areaBottom - areaTop - GAP * (rowsPerPage + 1)) / rowsPerPage;
        int left = GAP + (local % columns) * (w + GAP);
        int top = areaTop + GAP + (local / columns) * (h + GAP);
        out.set(left, top, left + w, top + h);
        return out;
    }

    /** Trefferflaeche um GAP/2 groesser als die gezeichnete Kachel (IR-Touch ist ungenau). */
    private int hitTile(int x, int y) {
        int first = page * pageSize;
        int last = Math.min(tiles.length, first + pageSize);
        for (int i = first; i < last; i++) {
            tileRect(i, rect).inset(-GAP / 2, -GAP / 2);
            if (rect.contains(x, y)) return i;
        }
        return -1;
    }

    /** -1 = zurueck, 1 = vor, 0 = keine (sichtbare) Pfeiltaste getroffen. */
    private int hitNav(int x, int y) {
        if (!paged()) return 0;
        if (page > 0 && navButton(-1, rect).contains(x, y)) return -1;
        if (page < pageCount - 1 && navButton(1, rect).contains(x, y)) return 1;
        return 0;
    }

    private void changePage(int dir) {
        int next = Math.max(0, Math.min(pageCount - 1, page + dir));
        if (next == page) return;
        page = next;
        if (listener != null) listener.onPageChanged(page);
        // Ganze Seite neu: ohne vollen Refresh bliebe die alte Seite als Geisterbild sichtbar.
        flash();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getAction() & MotionEvent.ACTION_MASK;
        int x = (int) e.getX();
        int y = (int) e.getY();
        int hit = hitTile(x, y);
        int nav = hitNav(x, y);
        if (action == MotionEvent.ACTION_DOWN) {
            downIndex = hit;
            downNav = nav;
            // Statuszeile inkl. Abstand darueber (~8 mm), damit der IR-Touch sie sicher trifft.
            if (y >= getHeight() - STATUS_H - GAP) postDelayed(exitGesture, EXIT_HOLD_MS);
        } else if (action == MotionEvent.ACTION_UP) {
            removeCallbacks(exitGesture);
            if (downNav != 0 && nav == downNav) {
                changePage(nav);
            } else if (hit >= 0 && hit == downIndex && listener != null) {
                listener.onTileTapped(hit);
            }
            downIndex = -1;
            downNav = 0;
        } else if (action == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(exitGesture);
            downIndex = -1;
            downNav = 0;
        }
        return true;
    }
}
