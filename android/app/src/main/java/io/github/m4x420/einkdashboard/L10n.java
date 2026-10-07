package io.github.m4x420.einkdashboard;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Alle sichtbaren Texte der App, Deutsch und Englisch. Bewusst keine Android-Ressourcen:
 * die Sprache soll sich zur Laufzeit umschalten lassen, und das ist auf Android 2.3 mit
 * Ressourcen (Configuration.locale) umstaendlich und unzuverlaessig.
 */
final class L10n {
    static final String DE = "de";
    static final String EN = "en";

    private static final Map<String, String> de = new HashMap<>();
    private static final Map<String, String> en = new HashMap<>();
    private static String lang = EN;

    static {
        // Kachelwerte
        put("on", "AN", "ON");
        put("off", "AUS", "OFF");
        put("open", "OFFEN", "OPEN");
        put("closed", "ZU", "CLOSED");
        put("unavailable", "n/v", "n/a");

        // Kopfzeile, Blaettern
        put("battery", "%d %%", "%d%%");
        put("battery_charging", "%d %% (lädt)", "%d%% (charging)");
        put("page", "%d / %d", "%d / %d");

        // Verbindungsstatus
        put("status_start", "Start", "Starting");
        put("status_connecting", "Verbinde ...", "Connecting ...");
        put("status_live", "Live", "Live");
        put("status_offline", "Offline (%s), neu in %d s", "Offline (%s), retry in %d s");
        put("status_protocol_error", "Protokollfehler: %s", "Protocol error: %s");
        put("status_error", "Fehler: %s", "Error: %s");

        // Meldungen statt Kacheln
        put("layout_loading", "Lade Dashboard ...", "Loading dashboard ...");
        put("layout_empty", "Das Dashboard ist leer.", "The dashboard is empty.");
        put("layout_no_dashboard",
                "Kein Dashboard ausgewählt.\n\nIn Home Assistant:\nGeräte & Dienste > E-Ink Dashboard\n> Konfigurieren",
                "No dashboard selected.\n\nIn Home Assistant:\nDevices & services > E-Ink Dashboard\n> Configure");
        put("layout_dashboard_not_found",
                "Das gewählte Dashboard\nwurde nicht gefunden.",
                "The selected dashboard\nwas not found.");
        put("layout_unsupported_dashboard",
                "Das Dashboard wird automatisch\nerzeugt. Bitte ein eigenes\nDashboard mit Karten anlegen.",
                "This dashboard is generated\nautomatically. Please create\nyour own dashboard with cards.");
        put("layout_no_tiles",
                "Das Dashboard enthält keine\nKarten mit Entities.",
                "The dashboard contains no\ncards with entities.");
        put("layout_integration_missing",
                "Die Integration E-Ink Dashboard\nfehlt in Home Assistant.",
                "The E-Ink Dashboard integration\nis missing in Home Assistant.");
        put("layout_not_paired",
                "Home Assistant kennt dieses Gerät\nnicht mehr. Bitte neu koppeln.",
                "Home Assistant no longer knows\nthis device. Please pair again.");
        put("layout_error", "Fehler beim Laden des Dashboards:\n%s", "Error loading the dashboard:\n%s");

        // Menue (5 s gedrueckt halten)
        put("menu_title", "E-Ink Dashboard", "E-Ink Dashboard");
        put("menu_unpair", "Kopplung aufheben", "Unpair");
        put("menu_kiosk_on", "Als Startbildschirm einrichten (Root)", "Set as home screen (root)");
        put("menu_kiosk_off", "Original-Startbildschirm wiederherstellen", "Restore original home screen");
        // Absichtlich in der jeweils ANDEREN Sprache: so findet man den Punkt auch,
        // wenn man die aktuelle Sprache nicht lesen kann.
        put("menu_language", "Language: English", "Sprache: Deutsch");
        put("menu_settings", "Android-Einstellungen", "Android settings");
        put("menu_cancel", "Abbrechen", "Cancel");
        put("unpair_confirm",
                "Verbindung zu Home Assistant wirklich trennen? Danach muss das Gerät neu gekoppelt werden.",
                "Really disconnect from Home Assistant? The device will have to be paired again.");
        put("unpair_button", "Trennen", "Unpair");
        put("kiosk_wait", "Bitte warten, ggf. Superuser-Anfrage bestätigen ...",
                "Please wait, confirm the Superuser prompt if asked ...");
        put("kiosk_failed", "Fehlgeschlagen - ist das Gerät gerootet?", "Failed - is the device rooted?");
        put("kiosk_on_ok", "Kiosk aktiv: diese App ist jetzt der Startbildschirm.",
                "Kiosk active: this app is now the home screen.");
        put("kiosk_off_ok", "Original-Startbildschirm wiederhergestellt.", "Original home screen restored.");

        // Einrichtungsbildschirm
        put("pair_title", "E-Ink Dashboard", "E-Ink Dashboard");
        put("pair_subtitle", "einrichten", "setup");
        put("pair_hint1", "Home Assistant meldet das Gerät", "Home Assistant reports the device");
        put("pair_hint2", "automatisch als neu gefunden. Sonst:", "as discovered automatically. Otherwise:");
        put("pair_hint3", "Einstellungen > Geräte & Dienste >", "Settings > Devices & services >");
        put("pair_hint4", "Integration hinzufügen > \"E-Ink Dashboard\"", "Add integration > \"E-Ink Dashboard\"");
        put("pair_ip", "IP-Adresse", "IP address");
        put("pair_code", "Kopplungscode", "Pairing code");
        put("pair_no_wifi", "Kein WLAN", "No Wi-Fi");
    }

    private static void put(String key, String german, String english) {
        de.put(key, german);
        en.put(key, english);
    }

    /** Sprache des Geraets, wenn noch keine gewaehlt wurde. */
    static String systemDefault() {
        return DE.equals(Locale.getDefault().getLanguage()) ? DE : EN;
    }

    static void set(String language) {
        lang = DE.equals(language) ? DE : EN;
    }

    static String get() {
        return lang;
    }

    static String other() {
        return DE.equals(lang) ? EN : DE;
    }

    static String t(String key, Object... args) {
        String text = (DE.equals(lang) ? de : en).get(key);
        if (text == null) return key;
        return args.length == 0 ? text : String.format(Locale.US, text, args);
    }

    private L10n() {
    }
}
