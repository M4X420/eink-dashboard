package io.github.m4x420.einkdashboard;

import org.json.JSONException;
import org.json.JSONObject;

/** Nur die Felder, die wir anzeigen. Bewusst kein komplettes Attribut-Objekt (Heap!). */
final class EntityState {
    final String entityId;
    String state;
    String name;
    String unit;

    EntityState(String entityId) {
        this.entityId = entityId;
    }

    /** Volles Format aus REST (/api/states/<id>, Antwort von /api/services/...). */
    static EntityState fromRest(JSONObject o) throws JSONException {
        EntityState s = new EntityState(o.getString("entity_id"));
        s.state = o.optString("state", null);
        s.applyAttributes(o.optJSONObject("attributes"));
        return s;
    }

    void applyAttributes(JSONObject attrs) {
        if (attrs == null) return;
        if (attrs.has("friendly_name")) name = attrs.optString("friendly_name");
        if (attrs.has("unit_of_measurement")) unit = attrs.optString("unit_of_measurement");
    }

    EntityState copy() {
        EntityState c = new EntityState(entityId);
        c.state = state;
        c.name = name;
        c.unit = unit;
        return c;
    }

    boolean sameAs(EntityState o) {
        return o != null && eq(state, o.state) && eq(name, o.name) && eq(unit, o.unit);
    }

    // java.util.Objects gibt es erst ab API 19.
    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
