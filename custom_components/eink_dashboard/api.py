"""HTTP-Client fuer den Kopplungsserver der App auf dem E-Reader."""

from __future__ import annotations

from typing import Any

import aiohttp

from .const import DEVICE_PORT

# Das Koppeln dauert: die App prueft den Token gegen HA, bei falschem Code wartet sie 2 s.
_TIMEOUT = aiohttp.ClientTimeout(total=20)


class EinkDeviceError(Exception):
    """Basisfehler."""


class CannotConnect(EinkDeviceError):
    """Geraet nicht erreichbar."""


class InvalidCode(EinkDeviceError):
    """Kopplungscode falsch."""


class AlreadyPaired(EinkDeviceError):
    """Geraet ist schon mit einem HA gekoppelt."""


class PairingFailed(EinkDeviceError):
    """Geraet hat die Kopplung abgelehnt, z.B. weil es HA nicht erreicht."""


class EinkDeviceClient:
    """Spricht mit http://<geraet>:8124/api/..."""

    def __init__(self, session: aiohttp.ClientSession, host: str, port: int = DEVICE_PORT) -> None:
        self._session = session
        self._base = f"http://{host}:{port}"

    async def info(self) -> dict[str, Any]:
        status, data = await self._request("GET", "/api/info")
        if status != 200 or "device_id" not in data:
            raise CannotConnect(f"Unerwartete Antwort: HTTP {status}")
        return data

    async def pair(self, code: str, token: str, ha_port: int) -> dict[str, Any]:
        """Uebergibt Token und HA-Port. Die HA-Adresse ermittelt das Geraet selbst aus der Anfrage."""
        status, data = await self._request(
            "POST", "/api/pair", json={"code": code, "token": token, "port": ha_port}
        )
        if status == 200:
            return data
        error = data.get("error")
        if error == "invalid_code":
            raise InvalidCode
        if error == "already_paired":
            raise AlreadyPaired
        raise PairingFailed(data.get("detail") or error or f"HTTP {status}")

    async def unpair(self, token: str) -> None:
        status, data = await self._request(
            "POST", "/api/unpair", headers={"Authorization": f"Bearer {token}"}
        )
        if status != 200:
            raise EinkDeviceError(data.get("error") or f"HTTP {status}")

    async def _request(self, method: str, path: str, **kwargs: Any) -> tuple[int, dict[str, Any]]:
        try:
            async with self._session.request(
                method, self._base + path, timeout=_TIMEOUT, **kwargs
            ) as resp:
                try:
                    data = await resp.json(content_type=None)
                except ValueError:
                    data = {}
                return resp.status, data if isinstance(data, dict) else {}
        except (aiohttp.ClientError, TimeoutError) as err:
            raise CannotConnect(str(err)) from err
