"""Laufzeitdaten eines gekoppelten Geraets."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field

from homeassistant.config_entries import ConfigEntry


@dataclass
class EinkRuntimeData:
    """Was das Geraet zuletzt gemeldet hat, plus Rueckkanal zu verbundenen Geraeten."""

    connected: bool = False
    battery: int | None = None
    charging: bool | None = None
    rssi: int | None = None
    ip: str | None = None
    # Je offener Layout-Subscription ein Handler, der einen Befehl an das Geraet schickt.
    command_handlers: set[Callable[[str], None]] = field(default_factory=set)

    def send_command(self, command: str) -> bool:
        """False, wenn gerade kein Geraet verbunden ist."""
        for handler in list(self.command_handlers):
            handler(command)
        return bool(self.command_handlers)


EinkConfigEntry = ConfigEntry[EinkRuntimeData]
