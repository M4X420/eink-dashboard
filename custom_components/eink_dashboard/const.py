"""Konstanten der E-Ink-Dashboard-Integration."""

DOMAIN = "eink_dashboard"

# Port des Kopplungsservers in der App auf dem E-Reader.
DEVICE_PORT = 8124

CONF_CODE = "code"
CONF_DEVICE_ID = "device_id"
CONF_TOKEN = "token"
CONF_USER_ID = "user_id"
CONF_MODEL = "model"
CONF_APP_VERSION = "app_version"
CONF_DASHBOARD = "dashboard"


def signal_options_updated(entry_id: str) -> str:
    """Dispatcher-Signal: Optionen (z.B. Dashboard-Auswahl) eines Eintrags haben sich geaendert."""
    return f"{DOMAIN}_options_updated_{entry_id}"


def signal_state_updated(entry_id: str) -> str:
    """Dispatcher-Signal: Geraet hat Status gemeldet oder Verbindung hat gewechselt."""
    return f"{DOMAIN}_state_updated_{entry_id}"


# Befehle an das Geraet (Buttons in HA). "reload_layout" erledigt HA selbst.
COMMAND_FULL_REFRESH = "full_refresh"
COMMAND_RELOAD_LAYOUT = "reload_layout"
