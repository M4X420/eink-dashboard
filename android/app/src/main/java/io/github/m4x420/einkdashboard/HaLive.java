package io.github.m4x420.einkdashboard;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HA-WebSocket-Protokoll:
 * auth -> eink_dashboard/subscribe (Layout von der HA-Integration, kommt bei jeder Aenderung neu)
 *      -> subscribe_entities fuer genau die Entities im Layout (komprimiertes Diff-Format).
 * Viel sparsamer als subscribe_events/state_changed, das JEDE Aenderung im ganzen HA schicken wuerde.
 * Nach jedem Reconnect kommen Layout und voller Zustand ("a") automatisch neu -> kein separater Resync.
 *
 * start()/stop() und alle Callbacks: Main-Thread. onText/onIdle: Reader-Thread.
 */
final class HaLive implements WsClient.Listener {

    interface Callback {
        void onLiveState(EntityState s);

        /** Angemeldet und Layout abonniert: guter Moment fuer eine Statusmeldung. */
        void onConnected();

        /** Befehl aus HA, z.B. "full_refresh". */
        void onCommand(String command);

        /** Neues Layout. error != null (z.B. "no_dashboard") -> tiles ist leer. */
        void onLayout(Tile[] tiles, int columns, String error);

        /** key: L10n-Schluessel, z.B. "status_live"; args fuer die Platzhalter. */
        void onLiveStatus(String key, Object... args);

        /** HA hat den Token abgelehnt (z.B. Integration in HA geloescht): Kopplung ist hinfaellig. */
        void onAuthInvalid();
    }

    private static final String TAG = "eink-dashboard";
    private static final long MIN_BACKOFF_MS = 2000;
    private static final long MAX_BACKOFF_MS = 60000;

    private final String host;
    private final int port;
    private final String token;
    private final Callback callback;
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** Sendet Statusmeldungen, damit der Main-Thread nie auf den Socket wartet. */
    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    /** Nachrichten-IDs; Reader- und Sender-Thread vergeben sie gemeinsam. */
    private final AtomicInteger ids = new AtomicInteger(1);
    /** Angemeldet und bereit fuer eigene Nachrichten. */
    private volatile boolean ready;

    // Main-Thread
    private WsClient ws;
    private boolean running;
    private boolean waitingForReconnect;
    private long backoffMs = MIN_BACKOFF_MS;

    // Reader-Thread (je Verbindung genau einer)
    private final Map<String, EntityState> current = new HashMap<>();
    private int layoutSubscriptionId;
    private int entitySubscriptionId;

    HaLive(String host, int port, String token, Callback callback) {
        this.host = host;
        this.port = port;
        this.token = token;
        this.callback = callback;
    }

    void start() {
        if (running) return;
        running = true;
        connect();
    }

    void stop() {
        running = false;
        waitingForReconnect = false;
        ui.removeCallbacks(reconnect);
        if (ws != null) ws.close();
        ws = null;
    }

    /** Statusmeldung an die Integration (eink_dashboard/report). Main-Thread. */
    void report(JSONObject payload) {
        WsClient client = ws;
        if (client == null || !ready) return;
        sender.execute(() -> {
            try {
                payload.put("id", ids.getAndIncrement()).put("type", "eink_dashboard/report");
                client.sendText(payload.toString());
            } catch (Exception e) {
                Log.w(TAG, "Statusmeldung fehlgeschlagen", e);
            }
        });
    }

    private void connect() {
        ready = false;
        status("status_connecting");
        ws = new WsClient(host, port, "/api/websocket", this);
        ws.connect();
    }

    private final Runnable reconnect = () -> {
        waitingForReconnect = false;
        if (running) connect();
    };

    /** Netz ist (wieder) da: eine laufende Backoff-Pause abbrechen und sofort verbinden. */
    void networkAvailable() {
        if (!running || !waitingForReconnect) return;
        ui.removeCallbacks(reconnect);
        backoffMs = MIN_BACKOFF_MS;
        reconnect.run();
    }

    @Override
    public void onText(WsClient src, String text) {
        try {
            JSONObject m = new JSONObject(text);
            String type = m.optString("type");
            if ("auth_required".equals(type)) {
                ids.set(1);
                src.sendText(new JSONObject().put("type", "auth").put("access_token", token).toString());
            } else if ("auth_ok".equals(type)) {
                entitySubscriptionId = 0;
                layoutSubscriptionId = ids.getAndIncrement();
                src.sendText(new JSONObject()
                        .put("id", layoutSubscriptionId)
                        .put("type", "eink_dashboard/subscribe")
                        .toString());
                ready = true;
                ui.post(() -> {
                    backoffMs = MIN_BACKOFF_MS;
                    callback.onConnected();
                });
                status("status_live");
            } else if ("auth_invalid".equals(type)) {
                // NICHT endlos neu versuchen: HA bannt sonst ggf. die IP (ip_ban_enabled).
                ui.post(() -> {
                    stop();
                    callback.onAuthInvalid();
                });
            } else if ("event".equals(type)) {
                int id = m.optInt("id");
                if (id == entitySubscriptionId) handleEvent(m.getJSONObject("event"));
                else if (id == layoutSubscriptionId) handleLayoutEvent(src, m.getJSONObject("event"));
            } else if ("result".equals(type) && !m.optBoolean("success", true)) {
                JSONObject error = m.optJSONObject("error");
                String code = error != null ? error.optString("code") : "";
                if (m.optInt("id") == layoutSubscriptionId) {
                    // unknown_command: Integration fehlt; not_paired: Integration kennt diesen Benutzer nicht
                    emitLayout(new Tile[0], 2, "unknown_command".equals(code) ? "integration_missing" : code);
                } else {
                    Log.w(TAG, "HA-Fehler: " + error);
                }
            }
        } catch (Exception e) {
            status("status_protocol_error", String.valueOf(e.getMessage()));
        }
    }

    /** Ueber die Layout-Subscription kommen Layouts und Befehle. */
    private void handleLayoutEvent(WsClient src, JSONObject event) throws Exception {
        if (event.has("command")) {
            String command = event.getString("command");
            ui.post(() -> callback.onCommand(command));
        } else {
            handleLayout(src, event);
        }
    }

    /** {"columns", "tiles": [...], "error"?} -> Kacheln an die UI, Entity-Abo austauschen. */
    private void handleLayout(WsClient src, JSONObject layout) throws Exception {
        JSONArray arr = layout.optJSONArray("tiles");
        List<Tile> tiles = new ArrayList<>();
        List<String> entityIds = new ArrayList<>();
        for (int i = 0; arr != null && i < arr.length(); i++) {
            Tile t = Tile.fromJson(arr.getJSONObject(i));
            tiles.add(t);
            if (!entityIds.contains(t.entityId)) entityIds.add(t.entityId);
        }

        if (entitySubscriptionId != 0) {
            src.sendText(new JSONObject()
                    .put("id", ids.getAndIncrement())
                    .put("type", "unsubscribe_events")
                    .put("subscription", entitySubscriptionId)
                    .toString());
            entitySubscriptionId = 0;
        }
        current.clear();

        // Erst das Layout an die UI, dann kommen die States dazu (Handler haelt die Reihenfolge ein).
        String error = layout.has("error") && !layout.isNull("error") ? layout.getString("error") : null;
        emitLayout(tiles.toArray(new Tile[tiles.size()]), layout.optInt("columns", 2), error);

        // Leere entity_ids hiesse fuer HA "ALLE Entities" -> dann gar nicht abonnieren.
        if (entityIds.isEmpty()) return;
        entitySubscriptionId = ids.getAndIncrement();
        src.sendText(new JSONObject()
                .put("id", entitySubscriptionId)
                .put("type", "subscribe_entities")
                .put("entity_ids", new JSONArray(entityIds))
                .toString());
    }

    private void emitLayout(Tile[] tiles, int columns, String error) {
        ui.post(() -> callback.onLayout(tiles, columns, error));
    }

    /** Format: {"a": {id: {s, a, ...}}, "c": {id: {"+": {s, a}, "-": {a: [...]}}}, "r": [id]} */
    private void handleEvent(JSONObject ev) throws JSONException {
        JSONObject added = ev.optJSONObject("a");
        if (added != null) {
            for (Iterator<String> it = added.keys(); it.hasNext(); ) {
                String id = it.next();
                JSONObject c = added.getJSONObject(id);
                EntityState s = new EntityState(id);
                s.state = c.optString("s", null);
                s.applyAttributes(c.optJSONObject("a"));
                current.put(id, s);
                emit(s);
            }
        }
        JSONObject changed = ev.optJSONObject("c");
        if (changed != null) {
            for (Iterator<String> it = changed.keys(); it.hasNext(); ) {
                String id = it.next();
                JSONObject plus = changed.getJSONObject(id).optJSONObject("+");
                if (plus == null) continue;
                EntityState old = current.get(id);
                EntityState s = old != null ? old.copy() : new EntityState(id);
                if (plus.has("s")) s.state = plus.getString("s");
                s.applyAttributes(plus.optJSONObject("a"));
                current.put(id, s);
                emit(s);
            }
        }
        JSONArray removed = ev.optJSONArray("r");
        if (removed != null) {
            for (int i = 0; i < removed.length(); i++) {
                EntityState s = new EntityState(removed.getString(i));
                s.state = "unavailable";
                current.remove(s.entityId);
                emit(s);
            }
        }
    }

    @Override
    public void onIdle(WsClient src) {
        try {
            src.sendText(new JSONObject().put("id", ids.getAndIncrement()).put("type", "ping").toString());
        } catch (Exception ignored) {
            // Der naechste Timeout beendet die Verbindung ohnehin.
        }
    }

    @Override
    public void onClosed(WsClient src, Exception cause) {
        if (src == ws) ready = false;
        ui.post(() -> {
            if (!running || src != ws) return; // absichtlich geschlossen oder veraltete Verbindung
            long delay = backoffMs;
            backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
            String why = cause != null ? cause.getMessage() : "getrennt";
            callback.onLiveStatus("status_offline", why, (int) (delay / 1000));
            waitingForReconnect = true;
            ui.postDelayed(reconnect, delay);
        });
    }

    private void emit(EntityState s) {
        ui.post(() -> callback.onLiveState(s));
    }

    private void status(String key, Object... args) {
        ui.post(() -> callback.onLiveStatus(key, args));
    }
}
