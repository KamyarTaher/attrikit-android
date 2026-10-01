package dev.attrkit.android

import android.content.Context
import android.content.SharedPreferences
import dev.attrkit.core.TcfPreferences

/**
 * Reads the IAB `IABTCF_*` keys where a consent management platform writes them: the app's default
 * SharedPreferences. Read per call, never cached, so a choice changed in the consent dialog applies
 * from the next event.
 */
internal class SharedPreferencesTcfPreferences(
    private val preferences: SharedPreferences,
) : TcfPreferences {

    override fun value(key: String): Any? {
        if (!preferences.contains(key)) return null
        // `all` would copy the whole default file for every key read. The standard types
        // gdprApplies as a Number and the rest as Strings, but some platforms write other shapes,
        // so probe by type and let the core decide what a malformed value means.
        return try {
            preferences.getString(key, null)
        } catch (_: ClassCastException) {
            try {
                preferences.getInt(key, 0)
            } catch (_: ClassCastException) {
                try {
                    preferences.getLong(key, 0L)
                } catch (_: ClassCastException) {
                    null
                }
            }
        }
    }

    companion object {
        // The file PreferenceManager.getDefaultSharedPreferences opens, by its documented name,
        // which spares the host an androidx.preference dependency it may not have.
        fun create(context: Context): SharedPreferencesTcfPreferences {
            val app = context.applicationContext
            return SharedPreferencesTcfPreferences(
                app.getSharedPreferences("${app.packageName}_preferences", Context.MODE_PRIVATE),
            )
        }
    }
}
