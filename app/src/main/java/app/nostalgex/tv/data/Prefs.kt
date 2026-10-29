package app.nostalgex.tv.data

import android.content.Context
import app.nostalgex.engine.json.Json
import app.nostalgex.engine.json.asArr
import app.nostalgex.engine.json.asObj
import app.nostalgex.engine.json.bool
import app.nostalgex.engine.json.int
import app.nostalgex.engine.schedule.SeasonalAnswer
import app.nostalgex.engine.server.Session
import java.util.UUID

/** Settings and the sign-in, in SharedPreferences. Keys follow the web tuner's where it has one. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("nostalgex", Context.MODE_PRIVATE)

    /** One id per install: the Plex client identifier and the Jellyfin/Emby DeviceId. */
    val clientId: String
        get() {
            sp.getString("client_id", null)?.let { return it }
            val id = UUID.randomUUID().toString()
            sp.edit().putString("client_id", id).apply()
            return id
        }

    var session: Session?
        get() = Session.fromJson(sp.getString("session", null))
        set(v) {
            sp.edit().apply { if (v == null) remove("session") else putString("session", v.toJson()) }.apply()
        }

    /** The packages switched on. NOSTALGEX alone on a first visit, as upstream. */
    var enabledBundles: Set<String>
        get() = Json.parseOrNull(sp.getString("nostalgex_enabled_bundles", null)).asArr()
            ?.mapNotNull { it as? String }?.toSet() ?: setOf("nostalgex")
        set(v) {
            sp.edit().putString("nostalgex_enabled_bundles", Json.stringify(v.toList())).apply()
        }

    /** RETRO MODE: on by default, as on the Apple TV. */
    var retroMode: Boolean
        get() = sp.getBoolean("retro_mode", true)
        set(v) { sp.edit().putBoolean("retro_mode", v).apply() }

    /** The language chosen in Settings ("en" or "fr"), or null to follow the TV's language. */
    var language: String?
        get() = sp.getString("language", null)
        set(v) { sp.edit().apply { if (v == null) remove("language") else putString("language", v) }.apply() }

    /** The soundtrack to prefer ("fr" or "en"), or null for each programme's own default. */
    var audioLanguage: String?
        get() = sp.getString("audio_language", null)
        set(v) { sp.edit().apply { if (v == null) remove("audio_language") else putString("audio_language", v) }.apply() }

    /** The subtitles to prefer ("fr" or "en"), or null to match the app's language. */
    var subtitleLanguage: String?
        get() = sp.getString("subtitle_language", null)
        set(v) { sp.edit().apply { if (v == null) remove("subtitle_language") else putString("subtitle_language", v) }.apply() }

    /** Open straight onto the last channel, full screen, rather than on the guide. */
    var startOnLastChannel: Boolean
        get() = sp.getBoolean("start_on_last_channel", false)
        set(v) { sp.edit().putBoolean("start_on_last_channel", v).apply() }

    /** Subtitles shown (the CC toggle). */
    var captions: Boolean
        get() = sp.getBoolean("captions", false)
        set(v) { sp.edit().putBoolean("captions", v).apply() }

    /** Set once a first lineup has been built; until then every package's pools are built. */
    var completedInitialLoad: Boolean
        get() = sp.getBoolean("nostalgex_completed_initial_load", false)
        set(v) { sp.edit().putBoolean("nostalgex_completed_initial_load", v).apply() }

    var lastChannelId: Int?
        get() = sp.getInt("last_channel", -1).takeIf { it >= 0 }
        set(v) { sp.edit().putInt("last_channel", v ?: -1).apply() }

    fun seasonalAnswer(bundleId: String): SeasonalAnswer? {
        val o = Json.parseOrNull(sp.getString("nostalgex_seasonal_prompt_$bundleId", null)).asObj() ?: return null
        return SeasonalAnswer(never = o.bool("never") == true, notNowYear = o.int("notNowYear"))
    }

    fun setSeasonalAnswer(bundleId: String, answer: SeasonalAnswer?) {
        val key = "nostalgex_seasonal_prompt_$bundleId"
        sp.edit().apply {
            if (answer == null) remove(key)
            else putString(key, Json.stringify(if (answer.never) mapOf("never" to true) else mapOf("notNowYear" to answer.notNowYear)))
        }.apply()
    }

    /** Sign-out: everything about the server goes; the install's id and the look stay. */
    fun clearSession() {
        sp.edit().remove("session").remove("last_channel").remove("nostalgex_completed_initial_load").apply()
    }
}
