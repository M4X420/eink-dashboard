"""Befehle an das Geraet."""

from __future__ import annotations

from homeassistant.components.button import ButtonEntity
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from .const import COMMAND_FULL_REFRESH, COMMAND_RELOAD_LAYOUT
from .entity import EinkEntity
from .models import EinkConfigEntry


async def async_setup_entry(
    hass: HomeAssistant, entry: EinkConfigEntry, async_add_entities: AddConfigEntryEntitiesCallback
) -> None:
    async_add_entities(
        [
            CommandButton(entry, COMMAND_FULL_REFRESH, "mdi:refresh"),
            CommandButton(entry, COMMAND_RELOAD_LAYOUT, "mdi:view-dashboard-edit"),
        ]
    )


class CommandButton(EinkEntity, ButtonEntity):
    def __init__(self, entry: EinkConfigEntry, command: str, icon: str) -> None:
        super().__init__(entry, command)
        self._command = command
        self._attr_translation_key = command
        self._attr_icon = icon

    @property
    def available(self) -> bool:
        return self._data.connected

    async def async_press(self) -> None:
        if not self._data.send_command(self._command):
            raise HomeAssistantError("Das Gerät ist nicht verbunden.")
