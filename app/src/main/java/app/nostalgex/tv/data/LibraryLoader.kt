package app.nostalgex.tv.data

import app.nostalgex.engine.model.MediaItem
import app.nostalgex.engine.server.Http
import app.nostalgex.engine.server.MediaBrowserLibrary
import app.nostalgex.engine.server.PlexLibrary
import app.nostalgex.engine.server.PlexResolve
import app.nostalgex.engine.server.PlexServer
import app.nostalgex.engine.server.ServerException
import app.nostalgex.engine.server.Session

/** Reads the signed-in library from every server, in order. */
class LibraryLoader(private val http: Http) {

    class Result(val items: List<MediaItem>, val session: Session, val failedServers: List<String>)

    /**
     * `onProgress(detail, itemsSoFar)`. A Plex server whose saved address stops answering is
     * found again from its other addresses (local, remote, relay), and the new one is kept.
     */
    fun scan(session: Session, onProgress: (String, Int) -> Unit, shouldStop: () -> Boolean): Result {
        return when (session) {
            is Session.MediaBrowser -> {
                val items = MediaBrowserLibrary(http, session.session).scan(onProgress, shouldStop)
                Result(items, session, emptyList())
            }
            is Session.Plex -> {
                val all = ArrayList<MediaItem>()
                val servers = session.servers.toMutableList()
                val failed = ArrayList<String>()
                val multi = servers.size > 1
                for (i in servers.indices) {
                    if (shouldStop()) break
                    var server = servers[i]
                    val prefix = if (multi) "${server.name} · " else ""
                    val base = all.size
                    fun scanOne() = PlexLibrary(http, server, i).scan({ d, n -> onProgress(prefix + d, base + n) }, shouldStop)
                    try {
                        all.addAll(scanOne())
                    } catch (e: ServerException) {
                        if (e.status == 401 || e.status == 403) throw e
                        val again = retryElsewhere(server)
                        if (again == null) { failed.add(server.name); continue }
                        server = again
                        servers[i] = again
                        try { all.addAll(scanOne()) } catch (e2: Exception) { failed.add(server.name) }
                    } catch (e: Exception) {
                        val again = retryElsewhere(server)
                        if (again == null) { failed.add(server.name); continue }
                        server = again
                        servers[i] = again
                        try { all.addAll(scanOne()) } catch (e2: Exception) { failed.add(server.name) }
                    }
                }
                if (failed.size == servers.size) throw ServerException("Couldn't reach ${failed.joinToString(", ")}")
                Result(all, session.copy(servers = servers), failed)
            }
        }
    }

    private fun retryElsewhere(server: app.nostalgex.engine.server.SavedPlexServer): app.nostalgex.engine.server.SavedPlexServer? {
        if (server.connections.isEmpty()) return null
        val url = PlexResolve.bestConnection(
            http,
            PlexServer(server.name, server.machineIdentifier, server.token, true, true, server.connections),
        ) ?: return null
        return if (url == server.url) null else server.copy(url = url)
    }

    companion object {
        /** Whether two loads read the same library, for keeping the guide as it is. */
        fun signature(items: List<MediaItem>): String {
            var h = 1L
            for (it in items) h = h * 31 + it.hashCode()
            return "${items.size}:$h"
        }
    }
}
