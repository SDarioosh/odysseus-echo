package com.msp1974.vacompanion.odysseus

// Odysseus fork: the client_id the Echo's Home Assistant tokens belong to. Tokens from the phone
// setup (EchoSetup.kt) are issued to http://<echo-host>:10801/, and Home Assistant only refreshes
// a token with the client_id it was issued to. Blank means VACA's own (http(s)://vaca.homeassistant),
// which is also what a login on the Echo's own screen sets it back to. Hooks:
// device/authentication/AuthenticationManager.kt.

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager

object HaClientId {
    private const val KEY = "odysseus_ha_client_id"

    fun get(context: Context): String? =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext).getString(KEY, null)?.takeIf { it.isNotBlank() }

    fun set(context: Context, clientId: String?) {
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext).edit {
            if (clientId.isNullOrBlank()) remove(KEY) else putString(KEY, clientId)
        }
    }
}
