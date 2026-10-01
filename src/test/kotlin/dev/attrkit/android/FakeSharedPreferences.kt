package dev.attrkit.android

import android.content.SharedPreferences

/**
 * An in-memory SharedPreferences with the two behaviours the adapters depend on: an editor stages
 * changes that nobody can read until commit() or apply(), and a typed getter throws
 * ClassCastException on a value of another type, as the platform's does.
 */
internal class FakeSharedPreferences(initial: Map<String, Any> = emptyMap()) : SharedPreferences {
    val values = linkedMapOf<String, Any>().apply { putAll(initial) }
    var commitCount = 0
    var applyCount = 0
    var commitResult = true

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)
    override fun getString(key: String, defValue: String?): String? = typed(key, defValue)
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String, defValue: Int): Int = typed(key, defValue)
    override fun getLong(key: String, defValue: Long): Long = typed(key, defValue)
    override fun getFloat(key: String, defValue: Float): Float = typed(key, defValue)
    override fun getBoolean(key: String, defValue: Boolean): Boolean = typed(key, defValue)
    override fun contains(key: String): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor()
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) = Unit

    @Suppress("UNCHECKED_CAST")
    private fun <T> typed(key: String, default: T): T {
        val value = values[key] ?: return default
        // A cast to the caller's type, as the platform does; the generic one would not throw.
        if (default is String? && value !is String) throw ClassCastException("$key is not a String")
        if (default is Int && value !is Int) throw ClassCastException("$key is not an Int")
        if (default is Long && value !is Long) throw ClassCastException("$key is not a Long")
        return value as T
    }

    private inner class FakeEditor : SharedPreferences.Editor {
        private val staged = linkedMapOf<String, Any?>()

        override fun putString(key: String, value: String?) = apply { staged[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { staged[key] = values }
        override fun putInt(key: String, value: Int) = apply { staged[key] = value }
        override fun putLong(key: String, value: Long) = apply { staged[key] = value }
        override fun putFloat(key: String, value: Float) = apply { staged[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { staged[key] = value }
        override fun remove(key: String) = apply { staged[key] = null }
        override fun clear() = apply { values.clear() }

        override fun commit(): Boolean {
            commitCount++
            if (!commitResult) return false
            flush()
            return true
        }

        override fun apply() {
            applyCount++
            flush()
        }

        private fun flush() {
            for ((key, value) in staged) if (value == null) values.remove(key) else values[key] = value
        }
    }
}
