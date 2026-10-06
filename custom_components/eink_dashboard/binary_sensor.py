"""Verbindungsstatus und Ladezustand des Geraets."""

from __future__ import annotations

from homeassistant.components.binary_sensor import BinarySensorDeviceClass, BinarySensorEntity
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from .entity import EinkEntity
from .models import EinkConfigEntry


async def async_setup_entry(
    hass: HomeAssistant, entry: EinkConfigEntry, async_add_entities: AddConfigEntryEntitiesCallback
) -> None:
    async_add_entities([ConnectedSensor(entry, "connected"), ChargingSensor(entry, "charging")])


class ConnectedSensor(EinkEntity, BinarySensorEntity):
    """An, solange das Geraet per WebSocket verbunden ist."""

    _attr_device_class = BinarySensorDeviceClass.CONNECTIVITY
    _attr_entity_category = EntityCategory.DIAGNOSTIC

    @property
    def is_on(self) -> bool:
        return self._data.connected


class ChargingSensor(EinkEntity, BinarySensorEntity):
    _attr_device_class = BinarySensorDeviceClass.BATTERY_CHARGING

    @property
    def available(self) -> bool:
        return self._data.connected and self._data.charging is not None

    @property
    def is_on(self) -> bool | None:
        return self._data.charging
