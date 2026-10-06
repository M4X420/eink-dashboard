package io.github.m4x420.einkdashboard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Zwei Modi: ungekoppelt -> PairingView, gekoppelt -> DashboardView mit HA-Verbindung.
 * Die Kacheln kommen per WebSocket aus dem in HA gewaehlten Dashboard (siehe HaLive).
 * Netzwerk laeuft auf eigenen Threads (nicht AsyncTask: dessen Thread-Verhalten
 * unterscheidet sich zwischen 2.3 und 3.0+). Alles UI-seitige nur auf dem Main-Thread.
 */
public class DashboardActivity extends Activity
        implements DashboardView.Listener, HaLive.Callback, PairingServer.Listener {

    private static final String TAG = "eink-dashboard";
    private static final long PENDING_TIMEOUT_MS = 10000;
    private static final long REPORT_INTERVAL_MS = 5 * 60 * 1000;
    private static final String MENU_KIOSK_ON = "Als Startbildschirm einrichten (Root)";
    private static final String MENU_KIOSK_OFF = "Original-Startbildschirm wiederherstellen";
    private static final String MENU_UNPAIR = "Kopplung aufheben";
    private static final String MENU_SETTINGS = "Android-Einstellungen";
    private static final String MENU_CANCEL = "Abbrechen";

    private final Handler ui = new Handler();
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private DeviceConfig config;
    private PairingServer pairingServer;
    private MdnsResponder mdns;
    private WifiManager wifi;
    private WifiManager.WifiLock wifiLock;
    private String diag = "";
    private String appVersion = "";
    private boolean resumed;
    private int batteryPercent = -1;
    private boolean charging;

    // Nur im gekoppelten Modus gesetzt
    private HaClient rest;
    private HaLive live;
    private DashboardView view;
    private Tile[] tiles = new Tile[0];
    /** Letzter bekannter Zustand je Entity, damit ein neues Layout nicht leer startet. */
    private final Map<String, EntityState> states = new HashMap<>();
    private String connStatus = "";
    private int redrawsSinceFlash;

    // Nur im ungekoppelten Modus gesetzt
    private PairingView pairingView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL, TAG);
        wifiLock.setReferenceCounted(false);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        diag = "API " + Build.VERSION.SDK_INT + " / " + dm.widthPixels + "x" + dm.heightPixels
                + " / " + dm.densityDpi + "dpi / " + (Eink.available() ? "EPD" : "Flash");
        Log.i(TAG, diag + " / " + Build.MODEL + " / " + Build.VERSION.RELEASE);

        List<ComponentName> launchers = Kiosk.otherLaunchers(this);
        Log.i(TAG, "Andere Startbildschirme: " + launchers + ", Kiosk aktiv: " + Kiosk.isActive(this, launchers));
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        config = DeviceConfig.load(this);
        pairingServer = new PairingServer(Config.PAIRING_PORT, config, deviceInfo(dm), this);
        pairingServer.start();
        mdns = new MdnsResponder(wifi, config, Config.PAIRING_PORT, Build.MODEL, appVersion);
        mdns.start();
        showCurrentMode();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        wifiLock.acquire();
        registerReceiver(networkReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        // Nach dem Start einmal komplett neu: raeumt Geisterbilder der vorherigen Anzeige weg.
        ui.postDelayed(fullRefresh, 2000);
        ui.postDelayed(reportTask, REPORT_INTERVAL_MS);
        startConnection();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(reportTask);
        stopConnection();
        unregisterReceiver(networkReceiver);
        if (wifiLock.isHeld()) wifiLock.release();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(batteryReceiver);
        pairingServer.stop();
        mdns.stop();
        net.shutdownNow();
    }

    // ---- Modus: Kopplung / Dashboard ----

    @Override
    public void onPairingChanged() {
        Log.i(TAG, config.isPaired() ? "Gekoppelt mit " + config.baseUrl() : "Kopplung aufgehoben/Code neu");
        mdns.reannounce(); // TXT "paired" hat sich geaendert
        showCurrentMode();
    }

    private void showCurrentMode() {
        stopConnection();
        if (config.isPaired()) {
            pairingView = null;
            rest = new HaClient(config.baseUrl(), config.token());
            live = new HaLive(config.host(), config.port(), config.token(), this);
            states.clear();
            showDashboard(new Tile[0], 2, "Lade Dashboard ...");
            setConnStatus("Start");
            if (resumed) startConnection();
        } else {
            rest = null;
            live = null;
            view = null;
            pairingView = new PairingView(this, this::onExitGesture);
            setContentView(pairingView);
            updatePairingView();
        }
        ui.postDelayed(fullRefresh, 1000);
    }

    private void startConnection() {
        if (live != null) live.start();
    }

    private void stopConnection() {
        if (live != null) live.stop();
    }

    private void showDashboard(Tile[] newTiles, int columns, String message) {
        tiles = newTiles;
        view = new DashboardView(this, newTiles, columns, message);
        view.setListener(this);
        for (int i = 0; i < newTiles.length; i++) {
            EntityState s = states.get(newTiles[i].entityId);
            if (s != null) view.setState(i, s);
        }
        view.setStatus(diag + "   |   " + connStatus);
        setContentView(view);
    }

    private void updatePairingView() {
        if (pairingView == null) return;
        String ip = wifiIp();
        pairingView.setInfo(ip != null ? ip : "Kein WLAN", config.pairingCode(), diag);
    }

    private String wifiIp() {
        int ip = wifi.getConnectionInfo().getIpAddress();
        if (ip == 0) return null;
        return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >>> 24) & 0xFF);
    }

    private JSONObject deviceInfo(DisplayMetrics dm) {
        JSONObject o = new JSONObject();
        try {
            o.put("model", Build.MODEL)
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("android", Build.VERSION.RELEASE)
                    .put("api", Build.VERSION.SDK_INT)
                    .put("width", dm.widthPixels)
                    .put("height", dm.heightPixels)
                    .put("app_version", appVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (JSONException | android.content.pm.PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Geraeteinfo unvollstaendig", e);
        }
        return o;
    }

    private final Runnable fullRefresh = () -> {
        if (view != null) {
            view.flash();
        } else if (pairingView != null && !Eink.fullRefresh(pairingView)) {
            pairingView.invalidate();
        }
    };

    /** Netz wieder da -> sofort neu verbinden statt die Backoff-Pause (bis 60 s) abzuwarten. */
    private final BroadcastReceiver networkReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false)) {
                updatePairingView();
                return;
            }
            Log.i(TAG, "Netzwerk verfuegbar");
            if (live != null) live.networkAvailable();
            mdns.reannounce(); // Multicast-Gruppe nach WLAN-Wechsel neu beitreten
            updatePairingView(); // IP kann sich geaendert haben
        }
    };

    // ---- Statusmeldungen an HA ----

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int percent = level >= 0 && scale > 0 ? level * 100 / scale : -1;
            boolean plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            if (percent == batteryPercent && plugged == charging) return;
            batteryPercent = percent;
            charging = plugged;
            sendReport();
        }
    };

    private final Runnable reportTask = new Runnable() {
        @Override
        public void run() {
            sendReport(); // u.a. fuer das WLAN-Signal, das sich ohne Ereignis aendert
            ui.postDelayed(this, REPORT_INTERVAL_MS);
        }
    };

    private void sendReport() {
        if (live == null) return;
        JSONObject p = new JSONObject();
        try {
            if (batteryPercent >= 0) p.put("battery", batteryPercent).put("charging", charging);
            p.put("rssi", wifi.getConnectionInfo().getRssi()).put("app_version", appVersion);
            String ip = wifiIp();
            if (ip != null) p.put("ip", ip);
        } catch (JSONException e) {
            return;
        }
        live.report(p);
    }

    @Override
    public void onConnected() {
        sendReport();
    }

    @Override
    public void onCommand(String command) {
        Log.i(TAG, "Befehl aus HA: " + command);
        if ("full_refresh".equals(command)) {
            ui.removeCallbacks(fullRefresh);
            ui.post(fullRefresh);
        }
    }

    // ---- Kiosk ----

    /** Zurueck-Taste wirkungslos: als Launcher gibt es nichts "dahinter". */
    @Override
    public void onBackPressed() {
    }

    @Override
    public void onExitGesture() {
        Log.i(TAG, "Notausgang");
        List<ComponentName> launchers = Kiosk.otherLaunchers(this);
        List<String> list = new ArrayList<>();
        if (config.isPaired()) list.add(MENU_UNPAIR);
        if (!launchers.isEmpty()) list.add(Kiosk.isActive(this, launchers) ? MENU_KIOSK_OFF : MENU_KIOSK_ON);
        list.add(MENU_SETTINGS);
        list.add(MENU_CANCEL);
        String[] items = list.toArray(new String[list.size()]);
        new AlertDialog.Builder(this)
                .setTitle("E-Ink Dashboard")
                .setItems(items, (dialog, which) -> {
                    String choice = items[which];
                    if (MENU_UNPAIR.equals(choice)) confirmUnpair();
                    else if (MENU_KIOSK_ON.equals(choice)) setKiosk(launchers, true);
                    else if (MENU_KIOSK_OFF.equals(choice)) setKiosk(launchers, false);
                    else if (MENU_SETTINGS.equals(choice)) startActivity(new Intent(Settings.ACTION_SETTINGS));
                })
                .show();
    }

    /** Laeuft im Hintergrund: su fragt ggf. auf dem Display nach, pm braucht einige Sekunden. */
    private void setKiosk(List<ComponentName> launchers, boolean on) {
        Toast.makeText(this, "Bitte warten, ggf. Superuser-Anfrage bestaetigen ...", Toast.LENGTH_LONG).show();
        new Thread(() -> {
            boolean ok = Kiosk.setOthersEnabled(this, launchers, !on);
            String text = !ok ? "Fehlgeschlagen - ist das Geraet gerootet?"
                    : on ? "Kiosk aktiv: diese App ist jetzt der Startbildschirm."
                    : "Original-Startbildschirm wiederhergestellt.";
            ui.post(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show());
        }, "kiosk").start();
    }

    private void confirmUnpair() {
        new AlertDialog.Builder(this)
                .setMessage("Verbindung zu Home Assistant wirklich trennen? Danach muss das Geraet neu gekoppelt werden.")
                .setPositiveButton("Trennen", (dialog, which) -> {
                    config.unpair();
                    onPairingChanged();
                })
                .setNegativeButton(MENU_CANCEL, null)
                .show();
    }

    // ---- Tap -> Service-Call ----

    @Override
    public void onTileTapped(int index) {
        if (index >= tiles.length) return;
        Tile t = tiles[index];
        DashboardView v = view;
        HaClient client = rest;
        if (v == null || client == null || t.service == null || v.isPending(index)) return;

        v.setPending(index, true);
        v.invalidateTile(index); // sofortiges Feedback, nur die eine Kachel, ohne Debounce
        ui.postDelayed(() -> {
            if (v.isPending(index)) {
                v.setPending(index, false);
                v.invalidateTile(index);
            }
        }, PENDING_TIMEOUT_MS);

        net.execute(() -> {
            try {
                // Was sich spaeter aendert, kommt ohnehin per WebSocket-Event.
                List<EntityState> changed = client.callService(t.domain, t.service, t.data);
                ui.post(() -> {
                    for (EntityState s : changed) applyState(s);
                });
            } catch (Exception e) {
                Log.w(TAG, "Service-Call fehlgeschlagen", e);
                ui.post(() -> {
                    v.setPending(index, false);
                    v.invalidateTile(index);
                    setConnStatus("Fehler: " + e.getMessage());
                });
            }
        });
    }

    // ---- Live-Updates per WebSocket ----

    @Override
    public void onLiveState(EntityState s) {
        applyState(s);
    }

    @Override
    public void onLayout(Tile[] newTiles, int columns, String error) {
        Log.i(TAG, "Layout: " + newTiles.length + " Kacheln, " + columns + " Spalten"
                + (error != null ? ", Fehler " + error : ""));
        showDashboard(newTiles, columns, layoutMessage(error));
        redrawsSinceFlash = 0;
        ui.removeCallbacks(fullRefresh);
        ui.postDelayed(fullRefresh, 800); // neuer Bildaufbau -> einmal sauber
    }

    private static String layoutMessage(String error) {
        if (error == null) return "Das Dashboard ist leer.";
        switch (error) {
            case "no_dashboard":
                return "Kein Dashboard ausgewählt.\n\nIn Home Assistant:\nGeräte & Dienste > E-Ink Dashboard\n> Konfigurieren";
            case "dashboard_not_found":
                return "Das gewählte Dashboard\nwurde nicht gefunden.";
            case "unsupported_dashboard":
                return "Das Dashboard wird automatisch\nerzeugt. Bitte ein eigenes\nDashboard mit Karten anlegen.";
            case "no_tiles":
                return "Das Dashboard enthält keine\nKarten mit Entities.";
            case "integration_missing":
                return "Die Integration E-Ink Dashboard\nfehlt in Home Assistant.";
            case "not_paired":
                return "Home Assistant kennt dieses Gerät\nnicht mehr. Bitte neu koppeln.";
            default:
                return "Fehler beim Laden des Dashboards:\n" + error;
        }
    }

    @Override
    public void onLiveStatus(String text) {
        setConnStatus(text);
    }

    @Override
    public void onAuthInvalid() {
        Log.i(TAG, "Token von HA abgelehnt, hebe Kopplung auf");
        config.unpair();
        onPairingChanged();
    }

    // ---- Rendering ----

    private void applyState(EntityState s) {
        states.put(s.entityId, s);
        if (view == null) return;
        boolean changed = false;
        for (int i = 0; i < tiles.length; i++) {
            if (tiles[i].entityId.equals(s.entityId)) changed |= view.setState(i, s);
        }
        if (changed) scheduleRedraw();
    }

    private final Runnable redraw = () -> {
        if (view == null) return;
        if (++redrawsSinceFlash >= Config.FLASH_EVERY) {
            redrawsSinceFlash = 0;
            view.flash();
        } else {
            view.invalidate();
        }
    };

    private void scheduleRedraw() {
        ui.removeCallbacks(redraw);
        ui.postDelayed(redraw, Config.REDRAW_DEBOUNCE_MS);
    }

    private void setConnStatus(String s) {
        connStatus = s;
        if (view != null && view.setStatus(diag + "   |   " + s)) {
            Log.i(TAG, "Status: " + s);
            view.invalidateStatus();
        }
    }
}
