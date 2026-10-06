"""E-Ink Dashboard: alte E-Reader (z.B. tolino shine) als HA-Bedienfeld."""

from __future__ import annotations

import logging

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import CONF_HOST, Platform
from homeassistant.core import HomeAssistant
from homeassistant.helpers import config_validation as cv, device_registry as dr
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.dispatcher import async_dispatcher_send
from homeassistant.helpers.typing import ConfigType

from .api import EinkDeviceClient, EinkDeviceError
from . import websocket
from .const import (
    CONF_APP_VERSION,
    CONF_DEVICE_ID,
    CONF_MODEL,
    CONF_TOKEN,
    CONF_USER_ID,
    DOMAIN,
    signal_options_updated,
)
from .models import EinkConfigEntry, EinkRuntimeData

_LOGGER = logging.getLogger(__name__)

CONFIG_SCHEMA = cv.config_entry_only_config_schema(DOMAIN)
PLATFORMS = [Platform.BINARY_SENSOR, Platform.BUTTON, Platform.SENSOR]


async def async_setup(hass: HomeAssistant, config: ConfigType) -> bool:
    websocket.async_register(hass)
    return True


async def async_setup_entry(hass: HomeAssistant, entry: EinkConfigEntry) -> bool:
    entry.runtime_data = EinkRuntimeData()
    dr.async_get(hass).async_get_or_create(
        config_entry_id=entry.entry_id,
        identifiers={(DOMAIN, entry.data[CONF_DEVICE_ID])},
        name="E-Ink Dashboard",
        manufacturer="E-Ink Dashboard",
        model=entry.data.get(CONF_MODEL),
        sw_version=entry.data.get(CONF_APP_VERSION),
    )
    entry.async_on_unload(entry.add_update_listener(_async_options_updated))
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    return True


async def async_unload_entry(hass: HomeAssistant, entry: EinkConfigEntry) -> bool:
    return await hass.config_entries.async_unload_platforms(entry, PLATFORMS)


async def _async_options_updated(hass: HomeAssistant, entry: ConfigEntry) -> None:
    # Verbundene Geraete bekommen ueber ihr Abo (websocket.py) sofort das neue Layout.
    async_dispatcher_send(hass, signal_options_updated(entry.entry_id))


async def async_remove_entry(hass: HomeAssistant, entry: ConfigEntry) -> None:
    """Geraet abkoppeln und den beim Koppeln angelegten Benutzer samt Token loeschen."""
    client = EinkDeviceClient(async_get_clientsession(hass), entry.data[CONF_HOST])
    try:
        await client.unpair(entry.data[CONF_TOKEN])
    except EinkDeviceError as err:
        # Nicht schlimm: mit dem Benutzer verschwindet auch der Token, dann koppelt
        # sich das Geraet beim naechsten Verbindungsversuch selbst ab.
        _LOGGER.warning("Geraet %s nicht abgekoppelt: %s", entry.data[CONF_HOST], err)

    user = await hass.auth.async_get_user(entry.data[CONF_USER_ID])
    if user is not None:
        await hass.auth.async_remove_user(user)
