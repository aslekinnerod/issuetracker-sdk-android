package io.issuetracker.sdk.internal

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class Breadcrumb(
    val timestamp: Long, // epoch ms
    val action: String,
    val metadata: Map<String, String>?,
)

/**
 * Ring buffer of the 5 most recent user actions. Persisted on each add
 * so breadcrumbs survive a crash and can be attached to the
 * auto-generated crash report on the next launch.
 *
 * Persistence lives under filesDir/issuetracker so the system won't
 * reap it between sessions — breadcrumbs written just before a crash
 * MUST still be there tomorrow.
 */
internal object BreadcrumbStore {
    private const val MAX_ENTRIES = 5
    private const val ACTION_MAX_LEN = 80
    private const val META_KEY_MAX_LEN = 64
    private const val META_VALUE_MAX_LEN = 256
    private const val MAX_META_PAIRS = 5

    private var file: File? = null
    private val entries = ArrayDeque<Breadcrumb>()
    private val lock = Any()

    fun install(context: Context) {
        synchronized(lock) {
            if (file != null) return
            val dir = File(context.applicationContext.filesDir, "issuetracker").apply { mkdirs() }
            file = File(dir, "breadcrumbs.json")
            entries.clear()
            entries.addAll(load())
        }
    }

    fun record(action: String, metadata: Map<String, String>?) {
        val trimmed = action.trim()
        if (trimmed.isEmpty()) return
        val capped = trimmed.take(ACTION_MAX_LEN)
        val cappedMeta = metadata?.entries?.take(MAX_META_PAIRS)
            ?.associate { it.key.take(META_KEY_MAX_LEN) to it.value.take(META_VALUE_MAX_LEN) }
            ?.takeIf { it.isNotEmpty() }
        val crumb = Breadcrumb(System.currentTimeMillis(), capped, cappedMeta)
        synchronized(lock) {
            entries.addLast(crumb)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
            persist()
        }
    }

    fun snapshot(): List<Breadcrumb> = synchronized(lock) { entries.toList() }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            file?.delete()
        }
    }

    private fun load(): List<Breadcrumb> {
        val f = file ?: return emptyList()
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val meta = o.optJSONObject("metadata")?.let { mo ->
                    val keys = mo.keys()
                    val map = HashMap<String, String>()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        map[k] = mo.optString(k)
                    }
                    map
                }
                Breadcrumb(
                    timestamp = o.optLong("timestamp"),
                    action = o.optString("action"),
                    metadata = meta?.takeIf { it.isNotEmpty() },
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persist() {
        val f = file ?: return
        val arr = JSONArray()
        for (e in entries) {
            val o = JSONObject()
            o.put("timestamp", e.timestamp)
            o.put("action", e.action)
            e.metadata?.let { meta ->
                val mo = JSONObject()
                meta.forEach { (k, v) -> mo.put(k, v) }
                o.put("metadata", mo)
            }
            arr.put(o)
        }
        try {
            f.writeText(arr.toString())
        } catch (_: Exception) {
            // Persisting breadcrumbs is best-effort; the next add will retry.
        }
    }
}
