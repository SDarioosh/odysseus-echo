package com.msp1974.vacompanion.odysseus.managed

// Odysseus fork: applies the settings Odysseus manages to the device's own stores, and gives them
// back when Odysseus lets go. Pure (no Android), unit-tested (docs/odysseus/managed-settings.md).
//
// - A key the hub sets is "managed": its value is written into the setting's store (HomeSettings,
//   KioskSettings, RecorderSettings, …) through a [ManagedBinding].
// - The moment a key becomes managed, the store's value then is kept as the device's own. When
//   the hub drops the key (absent or null), that own value is written back and forgotten.
// - A key the hub sets before its store is registered (a setting built later, e.g. follow-up
//   listening) is kept and applied when the store registers.
// - Everything is persisted ([ManagedStore]), so it all holds offline and across restarts.

/** One device setting Odysseus may manage: how to read and write it in its own store. */
class ManagedBinding(val key: String, val read: () -> Any, val write: (Any) -> Unit)

/** The engine's persisted state (SharedPreferences on the device, in memory in tests). */
interface ManagedStore {
    /** The version of the last answer applied, or null before the first. */
    var version: Long?
    /** The managed values (key → value) of that answer. */
    var values: Map<String, Any>
    /** The device's own values of the managed keys, kept to be given back. */
    var own: Map<String, Any>
}

/** What changed between two answers. */
data class ManagedDiff(val set: Set<String>, val changed: Set<String>, val released: Set<String>) {
    val isEmpty: Boolean get() = set.isEmpty() && changed.isEmpty() && released.isEmpty()

    override fun toString(): String = buildList {
        if (set.isNotEmpty()) add("now set: ${set.sorted().joinToString()}")
        if (changed.isNotEmpty()) add("changed: ${changed.sorted().joinToString()}")
        if (released.isNotEmpty()) add("back to the device: ${released.sorted().joinToString()}")
    }.joinToString("; ").ifEmpty { "no change" }

    companion object {
        fun of(old: Map<String, Any>, new: Map<String, Any>) = ManagedDiff(
            set = new.keys - old.keys,
            changed = new.keys.filter { it in old && old[it] != new[it] }.toSet(),
            released = old.keys - new.keys,
        )
    }
}

class ManagedEngine(private val store: ManagedStore, private val log: (String) -> Unit = {}) {
    private val bindings = LinkedHashMap<String, ManagedBinding>()

    /** Keys Odysseus sets now. */
    val managedKeys: Set<String> get() = store.values.keys

    val version: Long? get() = store.version

    fun isManaged(key: String): Boolean = key in store.values

    /** The value Odysseus set for [key], or null when the device decides. */
    fun managedValue(key: String): Any? = store.values[key]

    /**
     * Registers a setting's store. Applies the managed value at once (or gives back the own value
     * of a key released while the store wasn't registered). Registering a key again replaces it.
     */
    fun register(binding: ManagedBinding) {
        if (binding.key !in ManagedKeys.all) log("Managed settings: registered '${binding.key}', which Odysseus never sets")
        bindings[binding.key] = binding
        reconcile(binding.key)
    }

    /** Applies the stored state again (at start; a store may have been reset). */
    fun reapply() {
        (store.values.keys + store.own.keys).forEach { reconcile(it) }
    }

    /** Takes a new answer from the hub: persists it and applies what changed. */
    fun accept(reply: ManagedSettingsReply): ManagedDiff {
        reply.ignored.forEach { log("Managed settings: ignored $it") }
        val old = store.values
        val diff = ManagedDiff.of(old, reply.values)
        store.version = reply.version
        store.values = reply.values
        (old.keys + reply.values.keys + store.own.keys).forEach { reconcile(it) }
        return diff
    }

    /**
     * The device was unlinked from Odysseus: every key goes back to the device (its own value is
     * written back), as if the hub had dropped them all. A new link starts from its first answer.
     */
    fun releaseAll(): ManagedDiff {
        val old = store.values
        val diff = ManagedDiff.of(old, emptyMap())
        store.version = null
        store.values = emptyMap()
        (old.keys + store.own.keys).forEach { reconcile(it) }
        return diff
    }

    private fun reconcile(key: String) {
        val binding = bindings[key]
        val managed = store.values[key]
        if (managed != null) {
            if (binding == null) return // applied once its store registers
            val current = runCatching { binding.read() }.getOrNull()
            if (key !in store.own && current != null) store.own = store.own + (key to current)
            if (current != managed) write(binding, managed)
        } else {
            val own = store.own[key] ?: return
            if (binding == null) return // given back once its store registers
            if (runCatching { binding.read() }.getOrNull() != own) write(binding, own)
            store.own = store.own - key
        }
    }

    private fun write(binding: ManagedBinding, value: Any) {
        runCatching { binding.write(value) }
            .onFailure { log("Managed settings: could not set '${binding.key}': ${it.message ?: it.javaClass.simpleName}") }
    }
}
