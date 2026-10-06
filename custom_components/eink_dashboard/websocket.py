"""WebSocket-Befehle fuer das Geraet: Layout abonnieren (inkl. Befehls-Rueckkanal) und Status melden."""

from __future__ import annotations

from typing import Any

import voluptuous as vol

from homeassistant.components import websocket_api
from homeassistant.const import CONF_HOST
from homeassistant.core import Event, HomeAssistant, callback
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers.dispatcher import async_dispatcher_connect, async_dispatcher_send

from .const import (
    COMMAND_RELOAD_LAYOUT,
    CONF_DASHBOARD,
    CONF_DEVICE_ID,
    CONF_USER_ID,
    DOMAIN,
    signal_options_updated,
    signal_state_updated,
)
from .layout import async_build_layout
from .models import EinkConfigEntry, EinkRuntimeData

EVENT_LOVELACE_UPDATED = "lovelace_updated"


@callback
def async_register(hass: HomeAssistant) -> None:
    websocket_api.async_register_command(hass, ws_subscribe)
    websocket_api.async_register_command(hass, ws_report)


@callback
def _entry_for_connection(
    hass: HomeAssistant, connection: websocket_api.ActiveConnection
) -> EinkConfigEntry | None:
    """Welches Geraet fragt, ergibt sich aus dem HA-Benutzer, der beim Koppeln angelegt wurde."""
    for entry in hass.config_entries.async_entries(DOMAIN):
        if entry.data.get(CONF_USER_ID) == connection.user.id:
            return entry
    return None


@callback
def _runtime(entry: EinkConfigEntry) -> EinkRuntimeData | None:
    return getattr(entry, "runtime_data", None)


@websocket_api.websocket_command({vol.Required("type"): f"{DOMAIN}/subscribe"})
@websocket_api.async_response
async def ws_subscribe(
    hass: HomeAssistant, connection: websocket_api.ActiveConnection, msg: dict[str, Any]
) -> None:
    """Schickt sofort das Layout und erneut, wenn Dashboard-Auswahl oder Dashboard sich aendern.

    Ueber dieselbe Subscription gehen Befehle an das Geraet: {"command": "..."}.
    Solange sie offen ist, gilt das Geraet als verbunden.
    """
    entry = _entry_for_connection(hass, connection)
    if entry is None:
        connection.send_error(msg["id"], "not_paired", "No E-Ink Dashboard entry for this user")
        return
    runtime = _runtime(entry)
    if runtime is None:
        connection.send_error(msg["id"], "not_loaded", "E-Ink Dashboard entry is not loaded")
        return

    msg_id = msg["id"]

    async def async_send_layout() -> None:
        layout = await async_build_layout(hass, entry)
        connection.send_message(websocket_api.event_message(msg_id, layout))

    @callback
    def schedule_send(*_: Any) -> None:
        hass.async_create_task(async_send_layout())

    @callback
    def on_lovelace_updated(event: Event) -> None:
        if event.data.get("url_path") == entry.options.get(CONF_DASHBOARD):
            schedule_send()

    @callback
    def handle_command(command: str) -> None:
        if command == COMMAND_RELOAD_LAYOUT:
            schedule_send()
        else:
            connection.send_message(websocket_api.event_message(msg_id, {"command": command}))

    unsubs = [
        async_dispatcher_connect(hass, signal_options_updated(entry.entry_id), schedule_send),
        hass.bus.async_listen(EVENT_LOVELACE_UPDATED, on_lovelace_updated),
    ]
    runtime.command_handlers.add(handle_command)
    runtime.connected = True
    async_dispatcher_send(hass, signal_state_updated(entry.entry_id))

    @callback
    def unsubscribe() -> None:
        # Wird auch aufgerufen, wenn die WebSocket-Verbindung abbricht.
        for unsub in unsubs:
            unsub()
        runtime.command_handlers.discard(handle_command)
        runtime.connected = bool(runtime.command_handlers)
        async_dispatcher_send(hass, signal_state_updated(entry.entry_id))

    connection.subscriptions[msg_id] = unsubscribe
    connection.send_result(msg_id)
    await async_send_layout()


@websocket_api.websocket_command(
    {
        vol.Required("type"): f"{DOMAIN}/report",
        vol.Optional("battery"): vol.All(int, vol.Range(min=0, max=100)),
        vol.Optional("charging"): bool,
        vol.Optional("rssi"): int,
        vol.Optional("ip"): str,
        vol.Optional("app_version"): str,
    }
)
@callback
def ws_report(
    hass: HomeAssistant, connection: websocket_api.ActiveConnection, msg: dict[str, Any]
) -> None:
    """Statusmeldung des Geraets: Akku, Laden, WLAN-Signal, IP, App-Version."""
    entry = _entry_for_connection(hass, connection)
    runtime = _runtime(entry) if entry else None
    if entry is None or runtime is None:
        connection.send_error(msg["id"], "not_paired", "No loaded E-Ink Dashboard entry for this user")
        return

    runtime.battery = msg.get("battery", runtime.battery)
    runtime.charging = msg.get("charging", runtime.charging)
    runtime.rssi = msg.get("rssi", runtime.rssi)
    runtime.ip = msg.get("ip", runtime.ip)

    # Neue IP (DHCP): merken, sonst klappt das Abkoppeln beim Loeschen nicht mehr.
    if runtime.ip and runtime.ip != entry.data.get(CONF_HOST):
        hass.config_entries.async_update_entry(entry, data={**entry.data, CONF_HOST: runtime.ip})

    if app_version := msg.get("app_version"):
        registry = dr.async_get(hass)
        device = registry.async_get_device(identifiers={(DOMAIN, entry.data[CONF_DEVICE_ID])})
        if device and device.sw_version != app_version:
            registry.async_update_device(device.id, sw_version=app_version)

    async_dispatcher_send(hass, signal_state_updated(entry.entry_id))
    connection.send_result(msg["id"])
