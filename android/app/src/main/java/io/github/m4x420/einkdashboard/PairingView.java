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
        c.drawText("E-Ink Dashboard", x, y, text);
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(30);
        c.drawText("einrichten", x, y += 48, text);

        c.drawLine(60, y += 50, getWidth() - 60, y, line);

        text.setTextSize(28);
        c.drawText("Home Assistant meldet das Geraet", x, y += 70, text);
        c.drawText("automatisch als neu gefunden. Sonst:", x, y += 42, text);
        c.drawText("Einstellungen > Geraete & Dienste >", x, y += 42, text);
        c.drawText("Integration hinzufuegen > \"E-Ink Dashboard\"", x, y += 42, text);

        text.setTextSize(30);
        c.drawText("IP-Adresse", x, y += 100, text);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextSize(64);
        c.drawText(ip, x, y += 80, text);

        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(30);
        c.drawText("Kopplungscode", x, y += 100, text);
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
