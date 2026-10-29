package app.nostalgex.tv.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.nostalgex.engine.model.Bundle
import app.nostalgex.engine.schedule.Season
import app.nostalgex.tv.tuner.Playback
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The app's languages. Each is named in its own language in Settings. */
enum class Language(val code: String, val label: String, val strings: Strings) {
    EN("en", "ENGLISH", En),
    FR("fr", "FRANÇAIS", Fr),
    ;

    fun next(): Language = entries[(ordinal + 1) % entries.size]

    companion object {
        fun from(code: String?): Language? = entries.firstOrNull { it.code == code }

        /** Before a choice is saved: French on a TV set to French, English otherwise. */
        fun system(): Language = if (Locale.getDefault().language == "fr") FR else EN
    }
}

/** The language on screen. Compose state, so every screen redraws when it changes. */
object I18n {
    var language by mutableStateOf(Language.EN)
}

/** The strings for the language on screen. */
val S: Strings get() = I18n.language.strings

/**
 * Everything the app writes on screen. Channel and package names are left as they are: they are
 * the lineup's own names, from upstream's channels.json.
 */
interface Strings {
    // ── Shared ──────────────────────────────────────────────────────────────
    val on: String
    val off: String
    val settings: String
    /** "CH 07 — NAME", in the info panel and the Now Playing panel. */
    fun channelTag(number: Int, name: String): String
    /** "CH 7 — Name", on a channel that has stopped. */
    fun channelShort(number: Int, name: String): String
    /** An episode's title in quotation marks. */
    fun quote(title: String): String
    val pressOkToTryAgain: String
    val menuToSignOut: String
    val keyboardHint: String

    // ── Guide ───────────────────────────────────────────────────────────────
    val upNext: String
    val channelColumn: String
    val noChannelsFound: String
    val noChannelsHint: String
    /** SWITCH IT ON, NOT THIS YEAR, DON'T ASK AGAIN. */
    val inviteChoices: List<String>
    fun inviteHeadline(bundle: Bundle, now: LocalDateTime): String

    // ── Full screen ─────────────────────────────────────────────────────────
    fun tuning(number: Int?): String
    val nextProgrammeSoon: String
    fun minutesLeft(minutes: Long, clock: String): String
    val subtitles: String
    val retroMode: String
    val directPlay: String
    val serverStream: String
    val serverStream1080: String
    val starting: String
    fun failure(f: Playback.Failure): String

    // ── Settings ────────────────────────────────────────────────────────────
    fun bundleDescription(b: Bundle): String
    fun channelsWithEnough(available: Int, total: Int): String
    fun spotlightLabel(bundle: Bundle, now: LocalDateTime): String
    val retroModeSub: String
    val subtitlesSub: String
    val language: String
    val languageSub: String
    val rescanLibrary: String
    val rescanSub: String
    val signOut: String
    fun channelsOnSummary(channels: Int, programmes: Int): String
    val settingsHint: String
    fun about(version: String): String

    // ── Loading ─────────────────────────────────────────────────────────────
    /** PREPARING, READING YOUR LIBRARY, BUILDING CHANNELS. */
    val loadSteps: List<String>
    /** The headline for each step. */
    val loadHeadlines: List<String>
    fun programmesSoFar(n: Int): String
    val stillWaiting: String
    val stopWaiting: String
    /** A server's scan progress ("Movies", "Shows · 120 eps"), in this language. */
    fun progressDetail(raw: String): String
    val savedLibrary: String
    fun connectingTo(name: String): String
    val buildingChannels: String

    // ── Errors and messages ─────────────────────────────────────────────────
    val signInRefused: String
    fun couldntReadLibrary(detail: String): String
    val stoppedWaiting: String
    val libraryEmpty: String
    val signInRefusedInSettings: String
    val nothingWillPlay: String
    val rereadingLibrary: String
    fun libraryRead(items: Int): String
    val couldntReachYourServer: String
    fun noChannel(number: Int?): String
    val pressBackAgain: String
    fun couldntReach(names: String): String

    // ── Connect screens ─────────────────────────────────────────────────────
    val connectYourLibrary: String
    val connectIntro: String
    val plexSub: String
    val jellyfinSub: String
    val embySub: String
    val goToPlexLink: String
    val scanToConnect: String
    val cancel: String
    val chooseLibraries: String
    val yourServer: String
    val sharedWithYou: String
    val continueLabel: String
    val oneMoment: String
    fun connectTo(kind: String): String
    fun canLookFor(kind: String): String
    val findMyServer: String
    val typeAnAddress: String
    val pickYourServer: String
    val searchAgain: String
    fun yourKindServer(kind: String): String
    fun typeAddressHint(kind: String): String
    val address: String
    val pressOkToType: String
    val connect: String
    val quickConnect: String
    val quickConnectHint: String
    val waitingForApproval: String
    val useAPassword: String
    val whosWatching: String
    val someoneElse: String
    fun signInTo(name: String): String
    val username: String
    val password: String
    val pressOkToTypeMaybeEmpty: String
    val signIn: String
    val connectFooter: String
    val pickAtLeastOneLibrary: String
    val gettingACode: String
    val couldNotReachPlex: String
    val waitingForAuthorization: String
    val findingYourServers: String
    val discoveryFailed: String
    val noPlexServers: String
    val checkingYourServer: String
    fun cantReachServers(names: String): String
    val searching: String
    fun noServerAnswered(kind: String): String
    val serverAddress: String
    val typeYourServerAddress: String
    val checkingTheAddress: String
    val nothingAnswered: String
    val signingIn: String
    val quickConnectDidntFinish: String
    val codeExpired: String
    val typeYourUsername: String
    val wrongNameOrPassword: String
    val couldntReachTheServer: String
    val signInFailed: String

    // ── Formats ─────────────────────────────────────────────────────────────
    fun clock(sec: Long): String
    fun minutes(min: Int): String
    fun bitrate(kbps: Int): String
}

object En : Strings {
    private val clockFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    override val on = "ON"
    override val off = "OFF"
    override val settings = "SETTINGS"
    override fun channelTag(number: Int, name: String) = "CH %02d — %s".format(number, name)
    override fun channelShort(number: Int, name: String) = "CH $number — $name"
    override fun quote(title: String) = "“$title”"
    override val pressOkToTryAgain = "PRESS OK TO TRY AGAIN"
    override val menuToSignOut = "MENU TO SIGN OUT"
    override val keyboardHint = "DONE ON THE KEYBOARD TO CONFIRM · BACK TO CANCEL"

    override val upNext = "UP NEXT"
    override val channelColumn = "CHANNEL"
    override val noChannelsFound = "NO CHANNELS FOUND"
    override val noChannelsHint = "Press OK to open Settings and switch on more packages."
    override val inviteChoices = listOf("SWITCH IT ON", "NOT THIS YEAR", "DON'T ASK AGAIN")
    override fun inviteHeadline(bundle: Bundle, now: LocalDateTime) = Season.inviteHeadline(bundle, now)

    override fun tuning(number: Int?) = "TUNING${number?.let { " CH $it" } ?: ""}"
    override val nextProgrammeSoon = "NEXT PROGRAMME IN A MOMENT"
    override fun minutesLeft(minutes: Long, clock: String) = "$minutes MIN LEFT · $clock"
    override val subtitles = "SUBTITLES"
    override val retroMode = "RETRO MODE"
    override val directPlay = "DIRECT PLAY"
    override val serverStream = "SERVER STREAM"
    override val serverStream1080 = "SERVER STREAM 1080p"
    override val starting = "STARTING"
    override fun failure(f: Playback.Failure) = when (f) {
        Playback.Failure.UNREACHABLE -> "Can't reach your server"
        Playback.Failure.REFUSED -> "Your server wouldn't start this one"
        Playback.Failure.SIGNED_OUT -> "Your server turned down the sign-in"
        Playback.Failure.SLOW -> "Your server couldn't keep up"
        Playback.Failure.NO_PICTURE -> "Your server took too long to start this one"
        Playback.Failure.UNDECODABLE -> "This TV can't play this file"
        Playback.Failure.UNKNOWN -> "This one won't play"
    }

    override fun bundleDescription(b: Bundle) = b.description
    override fun channelsWithEnough(available: Int, total: Int) = "$available of $total channels have enough to air"
    override fun spotlightLabel(bundle: Bundle, now: LocalDateTime) = Season.spotlightLabel(bundle, now)
    override val retroModeSub = "The picture cropped to 4:3 under scanlines, and snow between channels"
    override val subtitlesSub = "Show subtitles when a programme has them"
    override val language = "LANGUAGE"
    override val languageSub = "The language of the menus and the guide"
    override val rescanLibrary = "RESCAN LIBRARY"
    override val rescanSub = "Read every server again, behind the guide"
    override val signOut = "SIGN OUT"
    override fun channelsOnSummary(channels: Int, programmes: Int) = "$channels CHANNELS ON · $programmes PROGRAMMES"
    override val settingsHint = "OK switches · BACK saves and returns to the guide"
    override fun about(version: String) =
        "NOSTALGEX FOR ANDROID TV $version\nChannels, schedule and guide from Nostalgex by Chad Mueller (MIT), github.com/chadMueller/Nostalgex. Fonts: VT323, DM Mono (OFL)."

    override val loadSteps = listOf("PREPARING", "READING YOUR LIBRARY", "BUILDING CHANNELS")
    override val loadHeadlines = listOf("Getting ready", "Reading your library", "Building your channels")
    override fun programmesSoFar(n: Int) = "$n programmes so far"
    override val stillWaiting = "Still waiting on your server"
    override val stopWaiting = "STOP WAITING"
    override fun progressDetail(raw: String) = raw
    override val savedLibrary = "Your saved library"
    override fun connectingTo(name: String) = "Connecting to $name"
    override val buildingChannels = "Building channels"

    override val signInRefused = "Your server turned down the sign-in. Sign in again."
    override fun couldntReadLibrary(detail: String) = "Couldn't read your library. $detail".trim()
    override val stoppedWaiting = "Stopped waiting. Nothing had come back from your server."
    override val libraryEmpty = "Your library looks empty. Add some films or shows to your server, then try again."
    override val signInRefusedInSettings = "Your server turned down the sign-in. Sign in again in Settings."
    override val nothingWillPlay = "Nothing on this channel will play right now"
    override val rereadingLibrary = "Reading your library again…"
    override fun libraryRead(items: Int) = "Library read: $items items"
    override val couldntReachYourServer = "Couldn't reach your server"
    override fun noChannel(number: Int?) = "No channel $number"
    override val pressBackAgain = "Press BACK again to turn off the TV"
    override fun couldntReach(names: String) = "Couldn't reach $names"

    override val connectYourLibrary = "CONNECT YOUR LIBRARY"
    override val connectIntro = "Retro TV for your own movies and shows. Pick your server."
    override val plexSub = "Sign in with a code at plex.tv/link"
    override val jellyfinSub = "Quick Connect or your password"
    override val embySub = "Pick your user on the server"
    override val goToPlexLink = "GO TO plex.tv/link AND ENTER:"
    override val scanToConnect = "SCAN TO CONNECT"
    override val cancel = "CANCEL"
    override val chooseLibraries = "CHOOSE YOUR LIBRARIES"
    override val yourServer = "Your server"
    override val sharedWithYou = "Shared with you"
    override val continueLabel = "CONTINUE"
    override val oneMoment = "ONE MOMENT"
    override fun connectTo(kind: String) = "CONNECT TO ${kind.uppercase()}"
    override fun canLookFor(kind: String) = "Nostalgex can look for your $kind server on this network. Or type its address."
    override val findMyServer = "FIND MY SERVER"
    override val typeAnAddress = "TYPE AN ADDRESS"
    override val pickYourServer = "PICK YOUR SERVER"
    override val searchAgain = "SEARCH AGAIN"
    override fun yourKindServer(kind: String) = "YOUR ${kind.uppercase()} SERVER"
    override fun typeAddressHint(kind: String) = "Type the address of your $kind server, like 192.168.1.20 or https://media.example.com"
    override val address = "ADDRESS"
    override val pressOkToType = "Press OK to type"
    override val connect = "CONNECT"
    override val quickConnect = "QUICK CONNECT"
    override val quickConnectHint = "In Jellyfin on your phone or computer: your profile, Quick Connect, then enter:"
    override val waitingForApproval = "WAITING FOR APPROVAL"
    override val useAPassword = "USE A PASSWORD"
    override val whosWatching = "WHO'S WATCHING?"
    override val someoneElse = "SOMEONE ELSE"
    override fun signInTo(name: String) = "SIGN IN TO ${name.uppercase()}"
    override val username = "USERNAME"
    override val password = "PASSWORD"
    override val pressOkToTypeMaybeEmpty = "Press OK to type (may be empty)"
    override val signIn = "SIGN IN"
    override val connectFooter = "Free and open source. Based on Nostalgex by Chad Mueller (MIT). Never supplies any media of its own."
    override val pickAtLeastOneLibrary = "Pick at least one library."
    override val gettingACode = "GETTING A CODE"
    override val couldNotReachPlex = "COULD NOT REACH PLEX.TV. CHECK YOUR CONNECTION"
    override val waitingForAuthorization = "WAITING FOR AUTHORIZATION"
    override val findingYourServers = "FINDING YOUR SERVERS"
    override val discoveryFailed = "SERVER DISCOVERY FAILED. TRY AGAIN"
    override val noPlexServers = "NO PLEX SERVERS FOUND ON THIS ACCOUNT"
    override val checkingYourServer = "CHECKING YOUR SERVER"
    override fun cantReachServers(names: String) = "CAN'T REACH ${names.uppercase()}. IS IT ON?"
    override val searching = "SEARCHING..."
    override fun noServerAnswered(kind: String) = "NO ${kind.uppercase()} SERVER ANSWERED ON THIS NETWORK. TYPE ITS ADDRESS INSTEAD."
    override val serverAddress = "SERVER ADDRESS"
    override val typeYourServerAddress = "TYPE YOUR SERVER'S ADDRESS"
    override val checkingTheAddress = "CHECKING THE ADDRESS"
    override val nothingAnswered = "NOTHING ANSWERED AT THAT ADDRESS"
    override val signingIn = "SIGNING IN"
    override val quickConnectDidntFinish = "QUICK CONNECT DIDN'T FINISH. USE A PASSWORD INSTEAD."
    override val codeExpired = "THE CODE EXPIRED. TRY AGAIN, OR USE A PASSWORD."
    override val typeYourUsername = "TYPE YOUR USERNAME"
    override val wrongNameOrPassword = "THAT NAME OR PASSWORD ISN'T RIGHT"
    override val couldntReachTheServer = "COULDN'T REACH THE SERVER"
    override val signInFailed = "SIGN-IN FAILED"

    override fun clock(sec: Long): String = clockFormat.format(Instant.ofEpochSecond(sec).atZone(ZoneId.systemDefault()))
    override fun minutes(min: Int) = if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"
    override fun bitrate(kbps: Int) = String.format(Locale.US, "%.1f Mb/s", kbps / 1000.0)
}

/** Canadian French. A non-breaking space goes before a colon, none before a question mark. */
object Fr : Strings {
    private const val NB = ' '
    private val clockFormat = DateTimeFormatter.ofPattern("H 'h' mm", Locale.CANADA_FRENCH)
    private val MONTHS = listOf(
        "JANVIER", "FÉVRIER", "MARS", "AVRIL", "MAI", "JUIN",
        "JUILLET", "AOÛT", "SEPTEMBRE", "OCTOBRE", "NOVEMBRE", "DÉCEMBRE",
    )
    private val DESCRIPTIONS = mapOf(
        "nostalgex" to "Les années 80 et 90. Que du rétro, tout le temps",
        "kids" to "Disney, Nickelodeon, Pixar et plus encore",
        "truecrime" to "Affaires criminelles, suspense et mystère",
        "essentials" to "Les favoris de tous les jours et des émissions qui font du bien",
        "premium" to "Chaînes premium",
        "arthouse" to "Criterion, films étrangers, films de minuit et classiques cultes",
        "adventureland" to "Quêtes épiques, catastrophes et adrénaline",
        "sports" to "Films, séries et documentaires",
        "decades" to "Films et séries classés par décennie",
        "franchises" to "Marathons de sagas de films",
        "streamers" to "Chaînes par service de diffusion et par studio",
        "seasonal" to "De l'horreur toute l'année, à l'honneur chaque octobre",
        "tis-the-season" to "Chaînes de Noël, de l'Action de grâce et des Fêtes",
        "high-rotation" to "Vidéoclips par genre",
    )
    private val EPISODES = Regex("""(\d+) eps$""")

    override val on = "OUI"
    override val off = "NON"
    override val settings = "PARAMÈTRES"
    override fun channelTag(number: Int, name: String) = "CANAL %02d — %s".format(number, name)
    override fun channelShort(number: Int, name: String) = "CANAL $number — $name"
    override fun quote(title: String) = "«$NB$title$NB»"
    override val pressOkToTryAgain = "APPUYEZ SUR OK POUR RÉESSAYER"
    override val menuToSignOut = "MENU POUR VOUS DÉCONNECTER"
    override val keyboardHint = "TERMINÉ SUR LE CLAVIER POUR CONFIRMER · RETOUR POUR ANNULER"

    override val upNext = "À SUIVRE"
    override val channelColumn = "CANAL"
    override val noChannelsFound = "AUCUNE CHAÎNE TROUVÉE"
    override val noChannelsHint = "Appuyez sur OK pour ouvrir les paramètres et activer d'autres forfaits."
    override val inviteChoices = listOf("L'ACTIVER", "PAS CETTE ANNÉE", "NE PLUS DEMANDER")
    override fun inviteHeadline(bundle: Bundle, now: LocalDateTime) = when (bundle.id) {
        "seasonal" -> "C'EST OCTOBRE. PRÊTS À HURLER?"
        "tis-the-season" ->
            if (now.monthValue == 12) "C'EST DÉCEMBRE. LES CHAÎNES SE METTENT EN FÊTE."
            else "LES FÊTES SONT ARRIVÉES. LES CHAÎNES SE METTENT EN FÊTE."
        else -> "${bundle.name} EST DE SAISON."
    }

    override fun tuning(number: Int?) = "SYNTONISATION${number?.let { " CANAL $it" } ?: ""}"
    override val nextProgrammeSoon = "PROCHAINE ÉMISSION DANS UN INSTANT"
    override fun minutesLeft(minutes: Long, clock: String) = "$minutes MIN RESTANTES · $clock"
    override val subtitles = "SOUS-TITRES"
    override val retroMode = "MODE RÉTRO"
    override val directPlay = "LECTURE DIRECTE"
    override val serverStream = "FLUX DU SERVEUR"
    override val serverStream1080 = "FLUX DU SERVEUR 1080p"
    override val starting = "DÉMARRAGE"
    override fun failure(f: Playback.Failure) = when (f) {
        Playback.Failure.UNREACHABLE -> "Impossible de joindre votre serveur"
        Playback.Failure.REFUSED -> "Votre serveur n'a pas voulu lancer cette émission"
        Playback.Failure.SIGNED_OUT -> "Votre serveur a refusé la connexion"
        Playback.Failure.SLOW -> "Votre serveur n'arrivait pas à suivre"
        Playback.Failure.NO_PICTURE -> "Votre serveur a mis trop de temps à lancer cette émission"
        Playback.Failure.UNDECODABLE -> "Cette télé ne peut pas lire ce fichier"
        Playback.Failure.UNKNOWN -> "Impossible de lire cette émission"
    }

    override fun bundleDescription(b: Bundle) = DESCRIPTIONS[b.id] ?: b.description
    override fun channelsWithEnough(available: Int, total: Int) =
        if (available <= 1) "$available chaîne sur $total a de quoi diffuser"
        else "$available chaînes sur $total ont de quoi diffuser"

    /** Season.spotlightLabel, in French: "EN VEDETTE EN OCTOBRE", "EN VEDETTE DU 19 NOVEMBRE AU 31 DÉCEMBRE". */
    override fun spotlightLabel(bundle: Bundle, now: LocalDateTime): String {
        val months = bundle.activeMonths
        if (months.isNullOrEmpty()) return ""
        val start = Season.seasonStartDate(bundle, now.year)
        val last = months.last()
        if (start != null && last in 1..12) {
            val endYear = if (last < start.monthValue) start.year + 1 else start.year
            val lastDay = YearMonth.of(endYear, last).lengthOfMonth()
            val first = if (start.dayOfMonth == 1) "1ER" else start.dayOfMonth.toString()
            return "EN VEDETTE DU $first ${MONTHS[start.monthValue - 1]} AU $lastDay ${MONTHS[last - 1]}"
        }
        val names = months.mapNotNull { MONTHS.getOrNull(it - 1) }
        return if (names.isNotEmpty()) "EN VEDETTE EN ${names.joinToString(", ")}" else ""
    }

    override val retroModeSub = "L'image recadrée en 4:3 sous des lignes de balayage, et de la neige entre les chaînes"
    override val subtitlesSub = "Afficher les sous-titres quand une émission en a"
    override val language = "LANGUE"
    override val languageSub = "La langue des menus et du guide"
    override val rescanLibrary = "RELIRE LA BIBLIOTHÈQUE"
    override val rescanSub = "Relire chaque serveur, derrière le guide"
    override val signOut = "SE DÉCONNECTER"
    override fun channelsOnSummary(channels: Int, programmes: Int) =
        "$channels ${if (channels <= 1) "CHAÎNE ACTIVE" else "CHAÎNES ACTIVES"} · " +
            "$programmes ${if (programmes <= 1) "ÉMISSION" else "ÉMISSIONS"}"
    override val settingsHint = "OK active ou désactive · RETOUR enregistre et revient au guide"
    override fun about(version: String) =
        "NOSTALGEX POUR ANDROID TV $version\nChaînes, horaire et guide de Nostalgex par Chad Mueller (MIT), github.com/chadMueller/Nostalgex. Polices$NB: VT323, DM Mono (OFL)."

    override val loadSteps = listOf("PRÉPARATION", "LECTURE DE VOTRE BIBLIOTHÈQUE", "CRÉATION DES CHAÎNES")
    override val loadHeadlines = listOf("On se prépare", "Lecture de votre bibliothèque", "Création de vos chaînes")
    override fun programmesSoFar(n: Int) = if (n <= 1) "$n émission jusqu'ici" else "$n émissions jusqu'ici"
    override val stillWaiting = "Toujours en attente de votre serveur"
    override val stopWaiting = "ARRÊTER D'ATTENDRE"
    override fun progressDetail(raw: String) = raw.replace(EPISODES, "$1 ép.")
    override val savedLibrary = "Votre bibliothèque enregistrée"
    override fun connectingTo(name: String) = "Connexion à $name"
    override val buildingChannels = "Création des chaînes"

    override val signInRefused = "Votre serveur a refusé la connexion. Reconnectez-vous."
    override fun couldntReadLibrary(detail: String) = "Impossible de lire votre bibliothèque. $detail".trim()
    override val stoppedWaiting = "Attente arrêtée. Votre serveur n'avait encore rien renvoyé."
    override val libraryEmpty = "Votre bibliothèque semble vide. Ajoutez des films ou des séries à votre serveur, puis réessayez."
    override val signInRefusedInSettings = "Votre serveur a refusé la connexion. Reconnectez-vous dans les paramètres."
    override val nothingWillPlay = "Rien ne peut jouer sur cette chaîne pour l'instant"
    override val rereadingLibrary = "Relecture de votre bibliothèque…"
    override fun libraryRead(items: Int) = "Bibliothèque lue$NB: $items éléments"
    override val couldntReachYourServer = "Impossible de joindre votre serveur"
    override fun noChannel(number: Int?) = "Aucun canal $number"
    override val pressBackAgain = "Appuyez encore sur RETOUR pour éteindre la télé"
    override fun couldntReach(names: String) = "Impossible de joindre $names"

    override val connectYourLibrary = "CONNECTEZ VOTRE BIBLIOTHÈQUE"
    override val connectIntro = "La télé rétro pour vos propres films et séries. Choisissez votre serveur."
    override val plexSub = "Connectez-vous avec un code sur plex.tv/link"
    override val jellyfinSub = "Quick Connect ou votre mot de passe"
    override val embySub = "Choisissez votre utilisateur sur le serveur"
    override val goToPlexLink = "ALLEZ SUR plex.tv/link ET ENTREZ$NB:"
    override val scanToConnect = "SCANNEZ POUR VOUS CONNECTER"
    override val cancel = "ANNULER"
    override val chooseLibraries = "CHOISISSEZ VOS BIBLIOTHÈQUES"
    override val yourServer = "Votre serveur"
    override val sharedWithYou = "Partagé avec vous"
    override val continueLabel = "CONTINUER"
    override val oneMoment = "UN INSTANT"
    override fun connectTo(kind: String) = "CONNEXION À ${kind.uppercase()}"
    override fun canLookFor(kind: String) = "Nostalgex peut chercher votre serveur $kind sur ce réseau. Ou entrez son adresse."
    override val findMyServer = "TROUVER MON SERVEUR"
    override val typeAnAddress = "ENTRER UNE ADRESSE"
    override val pickYourServer = "CHOISISSEZ VOTRE SERVEUR"
    override val searchAgain = "CHERCHER DE NOUVEAU"
    override fun yourKindServer(kind: String) = "VOTRE SERVEUR ${kind.uppercase()}"
    override fun typeAddressHint(kind: String) = "Entrez l'adresse de votre serveur $kind, comme 192.168.1.20 ou https://media.example.com"
    override val address = "ADRESSE"
    override val pressOkToType = "Appuyez sur OK pour écrire"
    override val connect = "SE CONNECTER"
    override val quickConnect = "QUICK CONNECT"
    override val quickConnectHint = "Dans Jellyfin, sur votre téléphone ou ordinateur$NB: votre profil, Quick Connect, puis entrez$NB:"
    override val waitingForApproval = "EN ATTENTE D'APPROBATION"
    override val useAPassword = "PAR MOT DE PASSE"
    override val whosWatching = "QUI REGARDE?"
    override val someoneElse = "QUELQU'UN D'AUTRE"
    override fun signInTo(name: String) = "CONNEXION À ${name.uppercase()}"
    override val username = "NOM D'UTILISATEUR"
    override val password = "MOT DE PASSE"
    override val pressOkToTypeMaybeEmpty = "Appuyez sur OK pour écrire (peut être vide)"
    override val signIn = "SE CONNECTER"
    override val connectFooter = "Libre et gratuit. Basé sur Nostalgex de Chad Mueller (MIT). Ne fournit jamais aucun média."
    override val pickAtLeastOneLibrary = "Choisissez au moins une bibliothèque."
    override val gettingACode = "OBTENTION D'UN CODE"
    override val couldNotReachPlex = "IMPOSSIBLE DE JOINDRE PLEX.TV. VÉRIFIEZ VOTRE CONNEXION"
    override val waitingForAuthorization = "EN ATTENTE D'AUTORISATION"
    override val findingYourServers = "RECHERCHE DE VOS SERVEURS"
    override val discoveryFailed = "LA RECHERCHE DE SERVEURS A ÉCHOUÉ. RÉESSAYEZ"
    override val noPlexServers = "AUCUN SERVEUR PLEX TROUVÉ SUR CE COMPTE"
    override val checkingYourServer = "VÉRIFICATION DE VOTRE SERVEUR"
    override fun cantReachServers(names: String) = "IMPOSSIBLE DE JOINDRE ${names.uppercase()}. EST-IL ALLUMÉ?"
    override val searching = "RECHERCHE..."
    override fun noServerAnswered(kind: String) = "AUCUN SERVEUR ${kind.uppercase()} N'A RÉPONDU SUR CE RÉSEAU. ENTREZ PLUTÔT SON ADRESSE."
    override val serverAddress = "ADRESSE DU SERVEUR"
    override val typeYourServerAddress = "ENTREZ L'ADRESSE DE VOTRE SERVEUR"
    override val checkingTheAddress = "VÉRIFICATION DE L'ADRESSE"
    override val nothingAnswered = "RIEN N'A RÉPONDU À CETTE ADRESSE"
    override val signingIn = "CONNEXION EN COURS"
    override val quickConnectDidntFinish = "QUICK CONNECT N'A PAS ABOUTI. UTILISEZ PLUTÔT UN MOT DE PASSE."
    override val codeExpired = "LE CODE A EXPIRÉ. RÉESSAYEZ, OU UTILISEZ UN MOT DE PASSE."
    override val typeYourUsername = "ENTREZ VOTRE NOM D'UTILISATEUR"
    override val wrongNameOrPassword = "CE NOM OU CE MOT DE PASSE N'EST PAS LE BON"
    override val couldntReachTheServer = "IMPOSSIBLE DE JOINDRE LE SERVEUR"
    override val signInFailed = "LA CONNEXION A ÉCHOUÉ"

    override fun clock(sec: Long): String = clockFormat.format(Instant.ofEpochSecond(sec).atZone(ZoneId.systemDefault()))
    override fun minutes(min: Int) = if (min >= 60) "${min / 60} h %02d".format(min % 60) else "$min min"
    override fun bitrate(kbps: Int) = String.format(Locale.CANADA_FRENCH, "%.1f Mb/s", kbps / 1000.0)
}
