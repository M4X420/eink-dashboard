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
    /** Verbindungsstatus als L10n-Schluessel + Argumente, damit er beim Sprachwechsel mitwechselt. */
    private String connStatusKey = "status_start";
    private Object[] connStatusArgs = new Object[0];
    /** Fuer den Neuaufbau nach Sprachwechsel: letztes Layout. */
    private int columns = 2;
    private String layoutError;
    private boolean layoutLoaded;
    private int page;
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
        L10n.set(config.language());
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
            layoutLoaded = false;
            page = 0;
            showDashboard(new Tile[0], 2);
            setConnStatus("status_start");
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

    private void showDashboard(Tile[] newTiles, int newColumns) {
        tiles = newTiles;
        columns = newColumns;
        String message = layoutLoaded ? layoutMessage(layoutError) : L10n.t("layout_loading");
        view = new DashboardView(this, newTiles, newColumns, message, page);
        view.setListener(this);
        for (int i = 0; i < newTiles.length; i++) {
            EntityState s = states.get(newTiles[i].entityId);
            if (s != null) view.setState(i, s);
        }
        view.setBattery(batteryPercent, charging);
        view.setStatus(statusLine());
        setContentView(view);
    }

    /** Nach Sprachwechsel: alles mit den neuen Texten neu aufbauen, Verbindung bleibt bestehen. */
    private void rerender() {
        if (view != null) {
            page = view.getPage();
            showDashboard(tiles, columns);
        } else {
            updatePairingView();
        }
        ui.removeCallbacks(fullRefresh);
        ui.postDelayed(fullRefresh, 500);
    }

    private void updatePairingView() {
        if (pairingView == null) return;
        String ip = wifiIp();
        pairingView.setInfo(ip != null ? ip : L10n.t("pair_no_wifi"), config.pairingCode(), diag);
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
            if (view != null && view.setBattery(percent, plugged)) view.invalidateHeader();
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
        // Schluessel statt Texten, damit der Vergleich unabhaengig von der Sprache ist.
        List<String> keys = new ArrayList<>();
        if (config.isPaired()) keys.add("menu_unpair");
        if (!launchers.isEmpty()) keys.add(Kiosk.isActive(this, launchers) ? "menu_kiosk_off" : "menu_kiosk_on");
        keys.add("menu_language");
        keys.add("menu_settings");
        keys.add("menu_cancel");
        String[] items = new String[keys.size()];
        for (int i = 0; i < items.length; i++) items[i] = L10n.t(keys.get(i));
        new AlertDialog.Builder(this)
                .setTitle(L10n.t("menu_title"))
                .setItems(items, (dialog, which) -> {
                    String choice = keys.get(which);
                    if ("menu_unpair".equals(choice)) confirmUnpair();
                    else if ("menu_kiosk_on".equals(choice)) setKiosk(launchers, true);
                    else if ("menu_kiosk_off".equals(choice)) setKiosk(launchers, false);
                    else if ("menu_language".equals(choice)) switchLanguage();
                    else if ("menu_settings".equals(choice)) startActivity(new Intent(Settings.ACTION_SETTINGS));
                })
                .show();
    }

    private void switchLanguage() {
        String next = L10n.other();
        Log.i(TAG, "Sprache: " + next);
        config.setLanguage(next);
        L10n.set(next);
        rerender();
    }

    /** Laeuft im Hintergrund: su fragt ggf. auf dem Display nach, pm braucht einige Sekunden. */
    private void setKiosk(List<ComponentName> launchers, boolean on) {
        Toast.makeText(this, L10n.t("kiosk_wait"), Toast.LENGTH_LONG).show();
        new Thread(() -> {
            boolean ok = Kiosk.setOthersEnabled(this, launchers, !on);
            String text = L10n.t(!ok ? "kiosk_failed" : on ? "kiosk_on_ok" : "kiosk_off_ok");
            ui.post(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show());
        }, "kiosk").start();
    }

    private void confirmUnpair() {
        new AlertDialog.Builder(this)
                .setMessage(L10n.t("unpair_confirm"))
                .setPositiveButton(L10n.t("unpair_button"), (dialog, which) -> {
                    config.unpair();
                    onPairingChanged();
                })
                .setNegativeButton(L10n.t("menu_cancel"), null)
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
                    setConnStatus("status_error", String.valueOf(e.getMessage()));
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
        layoutLoaded = true;
        layoutError = error;
        if (view != null) page = view.getPage(); // gleiche Seite behalten, wenn das Dashboard bearbeitet wurde
        showDashboard(newTiles, columns);
        redrawsSinceFlash = 0;
        ui.removeCallbacks(fullRefresh);
        ui.postDelayed(fullRefresh, 800); // neuer Bildaufbau -> einmal sauber
    }

    private static String layoutMessage(String error) {
        if (error == null) return L10n.t("layout_empty");
        switch (error) {
            case "no_dashboard":
            case "dashboard_not_found":
            case "unsupported_dashboard":
            case "no_tiles":
            case "integration_missing":
            case "not_paired":
                return L10n.t("layout_" + error);
            default:
                return L10n.t("layout_error", error);
        }
    }

    @Override
    public void onPageChanged(int newPage) {
        page = newPage;
    }

    @Override
    public void onLiveStatus(String key, Object... args) {
        setConnStatus(key, args);
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

    private void setConnStatus(String key, Object... args) {
        connStatusKey = key;
        connStatusArgs = args;
        if (view != null && view.setStatus(statusLine())) {
            Log.i(TAG, "Status: " + L10n.t(key, args));
            view.invalidateStatus();
        }
    }

    private String statusLine() {
        return diag + "   |   " + L10n.t(connStatusKey, connStatusArgs);
    }
}
