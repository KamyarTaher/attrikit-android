package dev.attrkit.android

import android.content.Context
import android.content.SharedPreferences
import dev.attrkit.core.KeyValueStore
import java.io.IOException

/**
 * The core's persistence on a private SharedPreferences file.
 *
 * Every [write] is one editor and one `commit()`. The core relies on a whole map landing as a
 * single transaction (a queue transition and its retry marker, a consent change and its cleanup
 * record), and `apply()` would hand the host a half-written state if the process died before the
 * asynchronous flush. `commit()` blocks on disk, which is why the facade only ever calls the
 * core from its own worker thread.
 */
internal class SharedPreferencesKeyValueStore(
    private val preferences: SharedPreferences,
) : KeyValueStore {

    override fun get(key: String): String? =
        try {
            preferences.getString(key, null)
        } catch (_: ClassCastException) {
            // A value of another type under our key is not ours to interpret; absence is the
            // answer the core already handles.
            null
        }

    override fun write(changes: Map<String, String?>) {
        if (changes.isEmpty()) return
        val editor = preferences.edit()
        for ((key, value) in changes) {
            if (value == null) editor.remove(key) else editor.putString(key, value)
        }
        // A refused commit must surface: the core would otherwise carry on as if a queue
        // transition or a consent withdrawal had been recorded.
        if (!editor.commit()) throw IOException("AttriKit could not persist its state")
    }

    companion object {
        const val FILE_NAME = "dev.attrkit.sdk"

        fun create(context: Context): SharedPreferencesKeyValueStore =
            SharedPreferencesKeyValueStore(
                context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE),
            )
    }
}
