"""Einrichtungsdialog: E-Reader per IP + Kopplungscode hinzufuegen, Dashboard waehlen."""

from __future__ import annotations

from datetime import timedelta
import logging
from typing import Any

import voluptuous as vol

from homeassistant.auth.const import GROUP_ID_USER
from homeassistant.auth.models import TOKEN_TYPE_LONG_LIVED_ACCESS_TOKEN
from homeassistant.config_entries import ConfigEntry, ConfigFlow, ConfigFlowResult, OptionsFlow
from homeassistant.const import CONF_HOST
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.service_info.zeroconf import ZeroconfServiceInfo
from homeassistant.helpers.selector import (
    SelectOptionDict,
    SelectSelector,
    SelectSelectorConfig,
    SelectSelectorMode,
)

from .api import AlreadyPaired, CannotConnect, EinkDeviceClient, InvalidCode, PairingFailed
from .const import (
    CONF_APP_VERSION,
    CONF_CODE,
    CONF_DASHBOARD,
    CONF_DEVICE_ID,
    CONF_MODEL,
    CONF_TOKEN,
    CONF_USER_ID,
    DOMAIN,
)

_LOGGER = logging.getLogger(__name__)

USER_SCHEMA = vol.Schema(
    {
        vol.Required(CONF_HOST): str,
        vol.Required(CONF_CODE): str,
    }
)
PAIR_SCHEMA = vol.Schema({vol.Required(CONF_CODE): str})


class EinkDashboardConfigFlow(ConfigFlow, domain=DOMAIN):
    """Kopplung: Geraeteinfo holen, eigenen HA-Benutzer + Token anlegen, an das Geraet uebergeben."""

    VERSION = 1

    def __init__(self) -> None:
        self._host: str | None = None

    async def async_step_user(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        """Manuell: IP-Adresse und Code eingeben."""
        errors: dict[str, str] = {}
        placeholders = {"detail": ""}
        if user_input is not None:
            host = user_input[CONF_HOST].strip()
            result = await self._async_try_pair(host, user_input[CONF_CODE], errors, placeholders)
            if result is not None:
                return result

        return self.async_show_form(
            step_id="user",
            data_schema=self.add_suggested_values_to_schema(USER_SCHEMA, user_input or {}),
            errors=errors,
            description_placeholders=placeholders,
        )

    async def async_step_zeroconf(self, discovery_info: ZeroconfServiceInfo) -> ConfigFlowResult:
        """Das Geraet hat sich per mDNS gemeldet (MdnsResponder in der App)."""
        props = discovery_info.properties
        device_id = props.get("device_id")
        if not device_id:
            return self.async_abort(reason="cannot_connect")
        host = str(discovery_info.ip_address)

        await self.async_set_unique_id(device_id)
        # Bekanntes Geraet mit neuer IP: still aktualisieren.
        self._abort_if_unique_id_configured(updates={CONF_HOST: host})
        if props.get("paired") == "1":
            # Gekoppelt, aber nicht mit diesem HA: nicht staendig als "neu gefunden" melden.
            return self.async_abort(reason="already_paired_elsewhere")

        self._host = host
        self.context["title_placeholders"] = {"name": f"E-Ink Dashboard ({host})"}
        return await self.async_step_pair()

    async def async_step_pair(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        """Nach automatischer Erkennung: nur noch den Code vom Display eingeben."""
        assert self._host is not None
        errors: dict[str, str] = {}
        placeholders = {"detail": "", "host": self._host}
        if user_input is not None:
            result = await self._async_try_pair(self._host, user_input[CONF_CODE], errors, placeholders)
            if result is not None:
                return result

        return self.async_show_form(
            step_id="pair",
            data_schema=PAIR_SCHEMA,
            errors=errors,
            description_placeholders=placeholders,
        )

    async def _async_try_pair(
        self, host: str, code: str, errors: dict[str, str], placeholders: dict[str, str]
    ) -> ConfigFlowResult | None:
        """Koppelt das Geraet. None -> Formular mit den eingetragenen Fehlern erneut zeigen."""
        code = code.replace(" ", "").strip()
        client = EinkDeviceClient(async_get_clientsession(self.hass), host)
        try:
            info = await client.info()
        except CannotConnect:
            errors["base"] = "cannot_connect"
            return None

        await self.async_set_unique_id(info["device_id"], raise_on_progress=False)
        self._abort_if_unique_id_configured(updates={CONF_HOST: host})

        api = self.hass.config.api
        if api is None or api.use_ssl:
            # Die alten E-Reader koennen kein modernes TLS.
            return self.async_abort(reason="ssl_not_supported")

        try:
            data = await self._async_pair(client, host, code, info, api.port)
        except InvalidCode:
            errors[CONF_CODE] = "invalid_code"
        except AlreadyPaired:
            errors["base"] = "already_paired"
        except CannotConnect:
            errors["base"] = "cannot_connect"
        except PairingFailed as err:
            errors["base"] = "pairing_failed"
            placeholders["detail"] = str(err)
        else:
            return self.async_create_entry(title=f"E-Ink Dashboard ({host})", data=data)
        return None

    async def _async_pair(
        self, client: EinkDeviceClient, host: str, code: str, info: dict[str, Any], ha_port: int
    ) -> dict[str, Any]:
        # Eigener Benutzer ohne Admin-Rechte, nur aus dem lokalen Netz nutzbar: wird der Token im
        # LAN mitgelesen, kann er nur Geraete schalten, aber nichts an HA selbst aendern.
        user = await self.hass.auth.async_create_user(
            f"E-Ink Dashboard ({host})", group_ids=[GROUP_ID_USER], local_only=True
        )
        try:
            refresh_token = await self.hass.auth.async_create_refresh_token(
                user,
                client_name="E-Ink Dashboard",
                token_type=TOKEN_TYPE_LONG_LIVED_ACCESS_TOKEN,
                access_token_expiration=timedelta(days=3650),
            )
            token = self.hass.auth.async_create_access_token(refresh_token)
            await client.pair(code, token, ha_port)
        except Exception:
            await self.hass.auth.async_remove_user(user)
            raise

        return {
            CONF_HOST: host,
            CONF_DEVICE_ID: info["device_id"],
            CONF_TOKEN: token,
            CONF_USER_ID: user.id,
            CONF_MODEL: info.get("model"),
            CONF_APP_VERSION: info.get("app_version"),
        }

    @staticmethod
    @callback
    def async_get_options_flow(config_entry: ConfigEntry) -> OptionsFlow:
        return EinkDashboardOptionsFlow()


class EinkDashboardOptionsFlow(OptionsFlow):
    """Welches Dashboard soll das Geraet anzeigen?"""

    async def async_step_init(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        if user_input is not None:
            return self.async_create_entry(data=user_input)

        schema = vol.Schema(
            {
                vol.Optional(CONF_DASHBOARD): SelectSelector(
                    SelectSelectorConfig(
                        options=_dashboard_options(self.hass),
                        mode=SelectSelectorMode.DROPDOWN,
                        custom_value=True,
                    )
                ),
            }
        )
        return self.async_show_form(
            step_id="init",
            data_schema=self.add_suggested_values_to_schema(schema, self.config_entry.options),
        )


def _dashboard_options(hass: HomeAssistant) -> list[SelectOptionDict]:
    """Vorhandene Dashboards. Die Lovelace-Interna aendern sich zwischen HA-Versionen, daher defensiv."""
    data = hass.data.get("lovelace")
    dashboards = getattr(data, "dashboards", None)
    if dashboards is None and isinstance(data, dict):
        dashboards = data.get("dashboards")

    options: list[SelectOptionDict] = []
    for url_path, dashboard in (dashboards or {}).items():
        if not url_path:
            continue  # Standard-Dashboard: zu voll fuer ein 6-Zoll-Display
        config = getattr(dashboard, "config", None) or {}
        title = config.get("title") or url_path
        options.append(SelectOptionDict(value=url_path, label=f"{title} ({url_path})"))
    if not options:
        _LOGGER.debug("Keine Dashboards gefunden, nur freie Eingabe moeglich")
    return options
