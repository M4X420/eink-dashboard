package io.github.m4x420.einkdashboard;

import android.util.Log;
import android.view.View;

import java.lang.reflect.Method;

/**
 * Freescale-E-Ink-Erweiterung im Tolino-Framework: View.invalidate(int mode) und View.EINK_*.
 * Das SDK kennt sie nicht, daher Reflection. Fehlt sie, liefert available() false.
 */
final class Eink {
    private static final String TAG = "eink-dashboard";

    private static Method invalidateWithMode;
    private static int fullMode;

    static {
        try {
            fullMode = constant("EINK_WAVEFORM_MODE_GC16") | constant("EINK_UPDATE_MODE_FULL");
            invalidateWithMode = View.class.getMethod("invalidate", int.class);
            Log.i(TAG, "E-Ink-API gefunden, Full-Refresh-Modus 0x" + Integer.toHexString(fullMode));
        } catch (Exception e) {
            invalidateWithMode = null;
            Log.i(TAG, "Keine E-Ink-API (" + e + "), nutze Schwarz-Flash");
        }
    }

    static boolean available() {
        return invalidateWithMode != null;
    }

    /** Ganze View mit GC16 neu zeichnen: Display blitzt einmal, Ghosting ist danach weg. */
    static boolean fullRefresh(View v) {
        if (invalidateWithMode == null) return false;
        try {
            invalidateWithMode.invoke(v, fullMode);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "E-Ink-Full-Refresh fehlgeschlagen", e);
            return false;
        }
    }

    private static int constant(String name) throws Exception {
        return View.class.getField(name).getInt(null);
    }

    private Eink() {
    }
}
