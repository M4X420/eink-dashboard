"""Akku und WLAN-Signal des Geraets."""

from __future__ import annotations

from homeassistant.components.sensor import SensorDeviceClass, SensorEntity, SensorStateClass
from homeassistant.const import PERCENTAGE, SIGNAL_STRENGTH_DECIBELS_MILLIWATT, EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from .entity import EinkEntity
from .models import EinkConfigEntry


async def async_setup_entry(
    hass: HomeAssistant, entry: EinkConfigEntry, async_add_entities: AddConfigEntryEntitiesCallback
) -> None:
    async_add_entities([BatterySensor(entry, "battery"), SignalSensor(entry, "rssi")])


class BatterySensor(EinkEntity, SensorEntity):
    _attr_device_class = SensorDeviceClass.BATTERY
    _attr_native_unit_of_measurement = PERCENTAGE
    _attr_state_class = SensorStateClass.MEASUREMENT

    @property
    def available(self) -> bool:
        return self._data.connected and self._data.battery is not None

    @property
    def native_value(self) -> int | None:
        return self._data.battery


class SignalSensor(EinkEntity, SensorEntity):
    _attr_device_class = SensorDeviceClass.SIGNAL_STRENGTH
    _attr_native_unit_of_measurement = SIGNAL_STRENGTH_DECIBELS_MILLIWATT
    _attr_state_class = SensorStateClass.MEASUREMENT
    _attr_entity_category = EntityCategory.DIAGNOSTIC

    @property
    def available(self) -> bool:
        return self._data.connected and self._data.rssi is not None

    @property
    def native_value(self) -> int | None:
        return self._data.rssi
