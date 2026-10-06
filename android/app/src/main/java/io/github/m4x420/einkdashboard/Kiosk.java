package io.github.m4x420.einkdashboard;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Kiosk-Modus per Root: alle anderen Startbildschirme (z.B. die Tolino-Startseite) per
 * "pm disable" abschalten bzw. wieder einschalten. Nur einzelne Activities, keine ganzen Apps.
 */
final class Kiosk {
    private static final String TAG = "eink-dashboard";
    /**
     * Einrichtungsassistenten melden sich auch als Startbildschirm, schalten sich nach der
     * Ersteinrichtung aber selbst ab. Wieder einschalten wuerde den Assistenten zurueckholen.
     */
    private static final List<String> SETUP_WIZARDS = Arrays.asList(
            "com.android.provision", "com.google.android.setupwizard");

    /** Andere Startbildschirme, auch bereits deaktivierte. */
    static List<ComponentName> otherLaunchers(Context context) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> found = context.getPackageManager()
                .queryIntentActivities(home, PackageManager.GET_DISABLED_COMPONENTS);
        List<ComponentName> result = new ArrayList<>();
        for (ResolveInfo info : found) {
            ActivityInfo a = info.activityInfo;
            if (!a.packageName.equals(context.getPackageName()) && !SETUP_WIZARDS.contains(a.packageName)) {
                result.add(new ComponentName(a.packageName, a.name));
            }
        }
        return result;
    }

    /** true, wenn alle anderen Startbildschirme abgeschaltet sind. */
    static boolean isActive(Context context, List<ComponentName> others) {
        PackageManager pm = context.getPackageManager();
        for (ComponentName c : others) {
            if (pm.getComponentEnabledSetting(c) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) return false;
        }
        return true;
    }

    /** Blockiert (Superuser-Abfrage, pm startet langsam): nur aus einem Hintergrund-Thread. */
    static boolean setOthersEnabled(Context context, List<ComponentName> others, boolean enabled) {
        for (ComponentName c : others) {
            su((enabled ? "pm enable " : "pm disable ") + c.flattenToShortString());
        }
        return isActive(context, others) != enabled;
    }

    private static void su(String command) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            int rc = p.waitFor();
            Log.i(TAG, "su -c " + command + " -> " + rc);
        } catch (Exception e) {
            Log.w(TAG, "su fehlgeschlagen: " + command, e);
        }
    }

    private Kiosk() {
    }
}
