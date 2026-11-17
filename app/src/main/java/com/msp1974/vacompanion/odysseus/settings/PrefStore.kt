package com.msp1974.vacompanion.odysseus.settings

// Odysseus fork: one SharedPreferences file of fork settings (HomeSettings, KioskSettings, …),
// each field Compose state that is loaded at init and persisted on set:
//
//   object KioskSettings {
//       internal val store = PrefStore("odysseus_kiosk")          // declared first
//       fun init(context: Context) = store.init(context)
//       var autoReload by store.bool("kiosk_auto_reload", true, managed = "kiosk.auto_reload")
//   }
//
// A field with `managed = "<C6 key>"` is also a setting Odysseus may manage: its declaration gives
// ManagedKeys its type and range and ManagedBindings how to read and write it
// (docs/odysseus/managed-settings.md). The hub's value is written through the same setter the
// settings screen uses, so `onSet` hooks run for both.

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.msp1974.vacompanion.odysseus.managed.ManagedKey
import com.msp1974.vacompanion.odysseus.managed.ManagedSetting
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Seconds, 0 = never, up to a day (the idle and return timeouts). */
val SECONDS_OR_NEVER = 0..86_400

class PrefStore(val name: String) {
    /** The file once [init] ran; null before. For a store's bespoke fields (LockSettings' PIN). */
    var prefs: SharedPreferences? = null
        private set

    private val fields = mutableListOf<Pref<*>>()
    private val managedFields = mutableListOf<ManagedSetting>()

    /** The fields Odysseus may manage, in declaration order. */
    val managed: List<ManagedSetting> get() = managedFields

    /** Loads the stored values, then runs [onLoad] (bespoke fields). Safe to call more than once. */
    fun init(context: Context, onLoad: (SharedPreferences) -> Unit = {}) {
        if (prefs != null) return
        attach(context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE), onLoad)
    }

    /** As [init], with the file given (unit tests use an in-memory one). */
    fun attach(p: SharedPreferences, onLoad: (SharedPreferences) -> Unit = {}) {
        if (prefs != null) return
        prefs = p
        fields.forEach { it.load(p) }
        onLoad(p)
    }

    /** One field: Compose state, [normalize]d on load and on set, persisted on set, then [onSet]. */
    inner class Pref<T>(
        val key: String,
        default: T,
        private val read: (SharedPreferences) -> T,
        private val write: (SharedPreferences.Editor, T) -> Unit,
        private val normalize: (T) -> T,
        private val onSet: (T) -> Unit,
    ) : ReadWriteProperty<Any?, T> {
        var value: T by mutableStateOf(default)
            private set

        internal fun load(p: SharedPreferences) {
            value = normalize(read(p))
        }

        fun set(v: T) {
            value = normalize(v)
            prefs?.edit { write(this, value) }
            onSet(value)
        }

        override fun getValue(thisRef: Any?, property: KProperty<*>): T = value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = set(value)
    }

    fun bool(key: String, default: Boolean, managed: String? = null, onSet: (Boolean) -> Unit = {}): Pref<Boolean> =
        add(
            Pref(key, default, { it.getBoolean(key, default) }, { e, v -> e.putBoolean(key, v) }, { it }, onSet),
            managed?.let { ManagedKey.Bool(it) }, { it }, { it as Boolean },
        )

    /** Kept within [range], which is also the range Odysseus may set when [managed]. */
    fun int(key: String, default: Int, range: IntRange, managed: String? = null): Pref<Int> =
        add(
            Pref(key, default, { it.getInt(key, default) }, { e, v -> e.putInt(key, v) }, { it.coerceIn(range) }, {}),
            managed?.let { ManagedKey.IntRange(it, range.first, range.last) }, { it }, { it as Int },
        )

    fun float(key: String, default: Float, range: ClosedFloatingPointRange<Float>): Pref<Float> =
        add(Pref(key, default, { it.getFloat(key, default) }, { e, v -> e.putFloat(key, v) }, { it.coerceIn(range) }, {}))

    /** Trimmed unless [normalize] says otherwise. */
    fun string(key: String, default: String = "", normalize: (String) -> String = String::trim): Pref<String> =
        add(Pref(key, default, { it.getString(key, default) ?: default }, { e, v -> e.putString(key, v) }, normalize, {}))

    /**
     * A value stored as a string ([encode] / [decode]: an enum's key, a JSON style). When
     * [managed], Odysseus sets it as one of [choices] (encoded strings).
     */
    fun <T> codec(
        key: String,
        default: T,
        decode: (String?) -> T,
        encode: (T) -> String,
        normalize: (T) -> T = { it },
        managed: String? = null,
        choices: Set<String> = emptySet(),
        onSet: (T) -> Unit = {},
    ): Pref<T> =
        add(
            Pref(key, default, { decode(it.getString(key, null)) }, { e, v -> e.putString(key, encode(v)) }, normalize, onSet),
            managed?.let { ManagedKey.Choice(it, choices) }, { encode(it) }, { decode(it as String) },
        )

    private fun <T> add(
        pref: Pref<T>,
        spec: ManagedKey? = null,
        toManaged: (T) -> Any = { it as Any },
        fromManaged: (Any) -> T = { error("not managed") },
    ): Pref<T> {
        fields += pref
        prefs?.let { pref.load(it) }
        if (spec != null) managedFields += ManagedSetting(spec, { toManaged(pref.value) }) { pref.set(fromManaged(it)) }
        return pref
    }
}
