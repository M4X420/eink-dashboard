package io.github.m4x420.einkdashboard;

import org.json.JSONException;
import org.json.JSONObject;

/** Eine Kachel, wie sie die HA-Integration aus dem Dashboard ableitet. */
final class Tile {
    final String entityId;
    /** null -> friendly_name der Entity anzeigen */
    final String label;
    /** domain/service/data == null -> reine Anzeige-Kachel, Tap tut nichts. */
    final String domain;
    final String service;
    final JSONObject data;

    private Tile(String entityId, String label, String domain, String service, JSONObject data) {
        this.entityId = entityId;
        this.label = label;
        this.domain = domain;
        this.service = service;
        this.data = data;
    }

    /** {"entity_id", "name"?, "action"?: {"domain", "service", "data"}} */
    static Tile fromJson(JSONObject o) throws JSONException {
        String label = o.has("name") && !o.isNull("name") ? o.getString("name") : null;
        JSONObject action = o.optJSONObject("action");
        if (action == null) return new Tile(o.getString("entity_id"), label, null, null, null);
        JSONObject data = action.optJSONObject("data");
        return new Tile(o.getString("entity_id"), label, action.getString("domain"),
                action.getString("service"), data != null ? data : new JSONObject());
    }
}
