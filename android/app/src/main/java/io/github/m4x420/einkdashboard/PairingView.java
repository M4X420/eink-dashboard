package io.github.m4x420.einkdashboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/** Einrichtungsbildschirm: IP-Adresse und Kopplungscode, gross und kontrastreich. */
final class PairingView extends View {

    private static final long EXIT_HOLD_MS = 5000;

    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint();
    private final Runnable onExitGesture;
    private String ip = "";
    private String code = "";
    private String footer = "";

    PairingView(Context context, Runnable onExitGesture) {
        super(context);
        this.onExitGesture = onExitGesture;
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(Color.BLACK);
        line.setColor(Color.BLACK);
        line.setStrokeWidth(3);
    }

    void setInfo(String ip, String code, String footer) {
        this.ip = ip;
        this.code = code.length() == 6 ? code.substring(0, 3) + " " + code.substring(3) : code;
        this.footer = footer;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(Color.WHITE);
        float x = getWidth() / 2f;
        float y = 120;

        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextSize(46);
        c.drawText(L10n.t("pair_title"), x, y, text);
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(30);
        c.drawText(L10n.t("pair_subtitle"), x, y += 48, text);

        c.drawLine(60, y += 50, getWidth() - 60, y, line);

        text.setTextSize(28);
        c.drawText(L10n.t("pair_hint1"), x, y += 70, text);
        c.drawText(L10n.t("pair_hint2"), x, y += 42, text);
        c.drawText(L10n.t("pair_hint3"), x, y += 42, text);
        c.drawText(L10n.t("pair_hint4"), x, y += 42, text);

        text.setTextSize(30);
        c.drawText(L10n.t("pair_ip"), x, y += 100, text);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextSize(64);
        c.drawText(ip, x, y += 80, text);

        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(30);
        c.drawText(L10n.t("pair_code"), x, y += 100, text);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextSize(96);
        c.drawText(code, x, y += 110, text);

        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(20);
        c.drawText(footer, x, getHeight() - 16, text);
    }

    /** Irgendwo 5 s gedrueckt halten: Notausgang (hier gibt es keine Kacheln, die man treffen koennte). */
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getAction() & MotionEvent.ACTION_MASK;
        if (action == MotionEvent.ACTION_DOWN) postDelayed(onExitGesture, EXIT_HOLD_MS);
        else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) removeCallbacks(onExitGesture);
        return true;
    }
}
