package app.nostalgex.tv.data

import android.content.Context
import app.nostalgex.engine.json.Json
import app.nostalgex.engine.json.asArr
import app.nostalgex.engine.json.asObj
import app.nostalgex.engine.json.int
import app.nostalgex.engine.json.str
import app.nostalgex.engine.model.MediaItem
import app.nostalgex.engine.schedule.Block
import app.nostalgex.engine.schedule.ManifestStore
import app.nostalgex.engine.schedule.StoredManifest
import app.nostalgex.engine.server.ItemCodec
import java.io.File
import java.time.LocalDate

/** The saved library, so a launch shows the guide at once and refreshes behind it. */
class LibraryStore(context: Context) {
    private val file = File(context.filesDir, "library.json")

    fun save(items: List<MediaItem>, signature: String) {
        val tmp = File(file.parentFile, "library.json.tmp")
        tmp.writeText(ItemCodec.encode(items, System.currentTimeMillis(), signature))
        tmp.renameTo(file)
    }

    fun load(): ItemCodec.Snapshot? = try {
        if (file.exists()) ItemCodec.decode(file.readText()) else null
    } catch (_: Exception) {
        null
    }

    fun clear() {
        file.delete()
    }
}

/**
 * Day manifests, kept in memory and written to one file, so yesterday's programmes are known
 * after a restart (unplayed-first depends on them), as the Apple TV's DailyManifestStore keeps
 * them. Days older than three are dropped on save.
 */
class FileManifestStore(context: Context) : ManifestStore {
    private val file = File(context.filesDir, "manifests.json")
    private val map = LinkedHashMap<String, StoredManifest>()
    private var loaded = false
    private var dirty = false

    @Synchronized private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            if (!file.exists()) return
            val o = Json.parse(file.readText()).asObj() ?: return
            for ((k, v) in o) {
                val m = v.asObj() ?: continue
                val blocks = m["b"].asArr()?.mapNotNull { b ->
                    val a = b.asArr() ?: return@mapNotNull null
                    Block(a[0] as String, (a[1] as Number).toLong(), (a[2] as Number).toLong())
                } ?: continue
                map[k] = StoredManifest(m.int("s") ?: 0, m.int("c") ?: 0, m.str("d") ?: "", m.str("f") ?: "", blocks)
            }
        } catch (_: Exception) {
            map.clear()
        }
    }

    @Synchronized override fun get(key: String): StoredManifest? {
        ensureLoaded()
        return map[key]
    }

    @Synchronized override fun put(key: String, manifest: StoredManifest) {
        ensureLoaded()
        map[key] = manifest
        dirty = true
    }

    @Synchronized override fun removePrefix(prefix: String) {
        ensureLoaded()
        if (map.keys.removeAll { it.startsWith(prefix) }) dirty = true
    }

    /** Writes the file if anything changed. Called from a background thread. */
    fun flush() {
        val snapshot: Map<String, StoredManifest>
        synchronized(this) {
            if (!dirty) return
            dirty = false
            val cutoff = LocalDate.now().minusDays(3).toString()
            map.entries.removeAll { it.value.dayKey < cutoff }
            snapshot = LinkedHashMap(map)
        }
        try {
            val out = LinkedHashMap<String, Any?>()
            for ((k, m) in snapshot) {
                out[k] = mapOf(
                    "s" to m.schemaVersion, "c" to m.channelId, "d" to m.dayKey, "f" to m.poolFingerprint,
                    "b" to m.blocks.map { listOf(it.ratingKey, it.startSec, it.endSec) },
                )
            }
            val tmp = File(file.parentFile, "manifests.json.tmp")
            tmp.writeText(Json.stringify(out))
            tmp.renameTo(file)
        } catch (_: Exception) {
            synchronized(this) { dirty = true }
        }
    }

    @Synchronized fun clearAll() {
        map.clear()
        loaded = true
        dirty = false
        file.delete()
    }
}
