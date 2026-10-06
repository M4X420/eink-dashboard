package io.github.m4x420.einkdashboard;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;

/**
 * Auf dem Geraet gespeicherte Kopplung (HA-Adresse + Token). Wird vom Pairing-Server-Thread
 * und vom Main-Thread benutzt, daher synchronized.
 */
final class DeviceConfig {
    private static final String PREFS = "eink_dashboard";
    private static final int DEFAULT_HA_PORT = 8123;

    private final SharedPreferences prefs;
    private String host;
    private int port;
    private String token;
    private String pairingCode;
    private String deviceId;

    private DeviceConfig(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    static DeviceConfig load(Context context) {
        DeviceConfig c = new DeviceConfig(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE));
        c.host = c.prefs.getString("host", null);
        c.port = c.prefs.getInt("port", DEFAULT_HA_PORT);
        c.token = c.prefs.getString("token", null);
        c.pairingCode = c.prefs.getString("pairing_code", null);
        c.deviceId = c.prefs.getString("device_id", null);
        if (c.deviceId == null) c.deviceId = UUID.randomUUID().toString().replace("-", "");
        if (c.pairingCode == null) c.pairingCode = newPairingCode();
        c.save();
        return c;
    }

    synchronized boolean isPaired() {
        return host != null && token != null;
    }

    synchronized String host() {
        return host;
    }

    synchronized int port() {
        return port;
    }

    synchronized String token() {
        return token;
    }

    synchronized String baseUrl() {
        return "http://" + host + ":" + port;
    }

    synchronized String pairingCode() {
        return pairingCode;
    }

    synchronized String deviceId() {
        return deviceId;
    }

    synchronized void pair(String host, int port, String token) {
        this.host = host;
        this.port = port;
        this.token = token;
        pairingCode = newPairingCode(); // alter Code ist nach der Kopplung wertlos
        save();
    }

    synchronized void unpair() {
        host = null;
        port = DEFAULT_HA_PORT;
        token = null;
        pairingCode = newPairingCode();
        save();
    }

    /** Neuer Code, z.B. nach zu vielen Fehlversuchen. */
    synchronized void rotatePairingCode() {
        pairingCode = newPairingCode();
        save();
    }

    private void save() {
        prefs.edit()
                .putString("host", host)
                .putInt("port", port)
                .putString("token", token)
                .putString("pairing_code", pairingCode)
                .putString("device_id", deviceId)
                .commit();
    }

    private static String newPairingCode() {
        return String.format(Locale.US, "%06d", new SecureRandom().nextInt(1000000));
    }
}
