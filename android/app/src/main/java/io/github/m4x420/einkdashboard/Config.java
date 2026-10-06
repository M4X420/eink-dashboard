package io.github.m4x420.einkdashboard;

final class Config {

    /**
     * HA-Adresse und Token werden beim Koppeln auf dem Geraet gespeichert (DeviceConfig),
     * die Kacheln kommen aus dem in HA gewaehlten Dashboard. Port des Pairing-Servers:
     */
    static final int PAIRING_PORT = 8124;

    /** Mehrere State-Aenderungen innerhalb dieses Fensters ergeben nur EINEN Redraw. */
    static final long REDRAW_DEBOUNCE_MS = 400;
    /** Jeder n-te Redraw ist ein E-Ink-Full-Refresh (GC16) gegen Ghosting. */
    static final int FLASH_EVERY = 10;
    static final long FLASH_MS = 250;

    private Config() {
    }
}
