"""Uebersetzt ein Lovelace-Dashboard in eine schlichte Kachelliste fuer das E-Ink-Geraet.

Das Geraet bekommt nur noch: {"columns": n, "tiles": [{"entity_id", "name"?, "action"?}], "error"?}
action = {"domain", "service", "data"} oder fehlt (reine Anzeige).
"""

from __future__ import annotations

import logging
from typing import Any

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError

from .const import CONF_DASHBOARD

_LOGGER = logging.getLogger(__name__)

MAX_TILES = 12
DEFAULT_COLUMNS = 2
MAX_COLUMNS = 4

# Ohne tap_action: diese Domains werden beim Tippen umgeschaltet ...
TOGGLE_DOMAINS = {
    "automation", "cover", "fan", "group", "humidifier", "input_boolean", "light",
    "media_player", "siren", "switch", "valve",
}
# ... und diese ausgeloest.
TRIGGER_SERVICES = {
    "button": "press",
    "input_button": "press",
    "scene": "turn_on",
    "script": "turn_on",
}
STACK_CARDS = {"grid", "vertical-stack", "horizontal-stack"}


async def async_build_layout(hass: HomeAssistant, entry: ConfigEntry) -> dict[str, Any]:
    url_path = entry.options.get(CONF_DASHBOARD)
    if not url_path:
        return _error("no_dashboard")

    dashboard = _get_dashboard(hass, url_path)
    if dashboard is None:
        return _error("dashboard_not_found")
    try:
        config = await dashboard.async_load(False)
    except HomeAssistantError as err:
        _LOGGER.warning("Dashboard %s nicht ladbar: %s", url_path, err)
        return _error("dashboard_not_found")

    views = config.get("views") if isinstance(config, dict) else None
    if not views:
        # Automatisch erzeugte Dashboards ("strategy") haben keine festen Karten.
        return _error("unsupported_dashboard")

    view = views[0]
    cards = list(view.get("cards") or [])
    for section in view.get("sections") or []:
        cards.extend(section.get("cards") or [])

    columns = DEFAULT_COLUMNS
    if len(cards) == 1 and cards[0].get("type") == "grid":
        columns = cards[0].get("columns", DEFAULT_COLUMNS)

    tiles: list[dict[str, Any]] = []
    for card in cards:
        _collect(card, tiles)

    if not tiles:
        return _error("no_tiles")
    if len(tiles) > MAX_TILES:
        _LOGGER.info("Dashboard %s hat %d Kacheln, zeige nur %d", url_path, len(tiles), MAX_TILES)

    return {
        "columns": max(1, min(int(columns), MAX_COLUMNS)),
        "tiles": tiles[:MAX_TILES],
    }


def _collect(card: dict[str, Any], tiles: list[dict[str, Any]]) -> None:
    card_type = card.get("type")
    if card_type in STACK_CARDS:
        for child in card.get("cards") or []:
            _collect(child, tiles)
    elif card_type == "entities":
        for row in card.get("entities") or []:
            row = {"entity": row} if isinstance(row, str) else row
            if isinstance(row, dict) and row.get("entity"):
                tiles.append(_tile(row))
    elif card.get("entity"):
        tiles.append(_tile(card))


def _tile(card: dict[str, Any]) -> dict[str, Any]:
    entity_id = card["entity"]
    tile: dict[str, Any] = {"entity_id": entity_id}
    if isinstance(card.get("name"), str):
        tile["name"] = card["name"]
    action = _action(entity_id, card.get("tap_action"))
    if action:
        tile["action"] = action
    return tile


def _action(entity_id: str, tap_action: Any) -> dict[str, Any] | None:
    domain = entity_id.split(".", 1)[0]
    kind = tap_action.get("action") if isinstance(tap_action, dict) else None

    if kind in (None, "more-info", "toggle"):
        # Ein Infodialog geht auf dem E-Reader nicht, also wie "toggle" behandeln.
        if domain in TOGGLE_DOMAINS:
            return {"domain": domain, "service": "toggle", "data": {"entity_id": entity_id}}
        if domain in TRIGGER_SERVICES:
            return {"domain": domain, "service": TRIGGER_SERVICES[domain], "data": {"entity_id": entity_id}}
        return None

    if kind in ("perform-action", "call-service"):
        full = tap_action.get("perform_action") or tap_action.get("service") or ""
        if "." not in full:
            return None
        action_domain, service = full.split(".", 1)
        data = dict(tap_action.get("data") or tap_action.get("service_data") or {})
        data.update(tap_action.get("target") or {})
        return {"domain": action_domain, "service": service, "data": data}

    # navigate, url, assist, ...: auf dem E-Reader nicht sinnvoll -> reine Anzeige
    return None


def _get_dashboard(hass: HomeAssistant, url_path: str) -> Any:
    """Die Lovelace-Interna aendern sich zwischen HA-Versionen, daher defensiv."""
    data = hass.data.get("lovelace")
    dashboards = getattr(data, "dashboards", None)
    if dashboards is None and isinstance(data, dict):
        dashboards = data.get("dashboards")
    return (dashboards or {}).get(url_path)


def _error(code: str) -> dict[str, Any]:
    return {"columns": DEFAULT_COLUMNS, "tiles": [], "error": code}
