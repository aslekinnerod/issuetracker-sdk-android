package io.issuetracker.sdk.internal

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.UUID

/**
 * Persisted reporter identity shown on submitted issues. Stored in
 * sandboxed SharedPreferences — different host apps see different
 * identities even on the same device.
 *
 * The installId is a stable anonymous UUID, lazily generated on first
 * read. Lets the server group reports from the same install even when
 * the name is blank or duplicated across users.
 */
internal object ReporterIdentity {
    private const val PREFS_NAME = "io.issuetracker.sdk.identity"
    private const val NAME_KEY = "reporter_name"
    private const val INSTALL_ID_KEY = "install_id"
    private const val NAME_MAX_LEN = 80

    private var prefs: SharedPreferences? = null

    fun install(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    val name: String?
        get() = prefs?.getString(NAME_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        prefs?.edit()?.putString(NAME_KEY, trimmed.take(NAME_MAX_LEN))?.apply()
    }

    fun clearName() {
        prefs?.edit()?.remove(NAME_KEY)?.apply()
    }

    val installId: String
        get() {
            val p = prefs ?: return ""
            p.getString(INSTALL_ID_KEY, null)?.let { return it }
            val fresh = UUID.randomUUID().toString()
            p.edit().putString(INSTALL_ID_KEY, fresh).apply()
            return fresh
        }

    fun payload(): JSONObject {
        val out = JSONObject()
        out.put("installId", installId)
        name?.let { out.put("name", it) }
        return out
    }
}
