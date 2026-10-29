package app.nostalgex.tv

import android.app.Application
import app.nostalgex.engine.model.ChannelConfig
import app.nostalgex.engine.model.MembershipManifest
import app.nostalgex.engine.server.DeviceCaps
import app.nostalgex.engine.server.JavaHttp
import app.nostalgex.tv.data.Capabilities
import app.nostalgex.tv.data.FileManifestStore
import app.nostalgex.tv.data.LibraryStore
import app.nostalgex.tv.data.Prefs
import app.nostalgex.tv.i18n.I18n
import app.nostalgex.tv.i18n.Language

class NostalgexApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        I18n.language = Language.from(graph.prefs.language) ?: Language.system()
    }
}

/** The app's long-lived objects. Heavy ones load on first use, off the main thread. */
class AppGraph(val app: Application) {
    val prefs = Prefs(app)
    val http = JavaHttp()
    val libraryStore = LibraryStore(app)
    val manifestStore = FileManifestStore(app)

    /** channels.json, unchanged from upstream. */
    val config: ChannelConfig by lazy {
        ChannelConfig.parse(app.assets.open("channels.json").bufferedReader().use { it.readText() })
    }

    /** channels-memberships.json, unchanged from upstream. */
    val manifest: MembershipManifest by lazy {
        MembershipManifest.parse(app.assets.open("channels-memberships.json").bufferedReader().use { it.readText() })
    }

    val caps: DeviceCaps by lazy { Capabilities.detect(app) }
}
