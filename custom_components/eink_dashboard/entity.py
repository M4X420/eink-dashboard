"""Gemeinsame Basis der Entities eines E-Ink-Geraets."""

from __future__ import annotations

from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.helpers.entity import Entity

from .const import CONF_DEVICE_ID, DOMAIN, signal_state_updated
from .models import EinkConfigEntry


class EinkEntity(Entity):
    """Aktualisiert sich, sobald das Geraet etwas meldet oder die Verbindung wechselt."""

    _attr_has_entity_name = True
    _attr_should_poll = False

    def __init__(self, entry: EinkConfigEntry, key: str) -> None:
        self._entry = entry
        self._data = entry.runtime_data
        device_id = entry.data[CONF_DEVICE_ID]
        self._attr_unique_id = f"{device_id}_{key}"
        self._attr_device_info = DeviceInfo(identifiers={(DOMAIN, device_id)})

    async def async_added_to_hass(self) -> None:
        self.async_on_remove(
            async_dispatcher_connect(
                self.hass, signal_state_updated(self._entry.entry_id), self.async_write_ha_state
            )
        )
