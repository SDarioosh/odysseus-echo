package com.msp1974.vacompanion.odysseus.announce

// Odysseus fork: announcements and reminders from Odysseus on this device (docs/odysseus/announcements.md).
//
// Home Assistant speaks them (the hub asks HA to announce on the right room's satellites); this
// shows a card for the same message. Process-wide: started and stopped with the background task
// (service/BackgroundTask.kt), so it runs whether or not the activity is in front.
//   - Hello: POST <base>/satellite/hello at start, hourly, and when the name, area or link changes,
//     so the hub knows this device's id, name and area.
//   - Feed: GET <base>/announcements every 30 s (longer after failures), and at once when the
//     screen comes on. New ones become cards (AnnouncementCards) and wake the screen through the
//     "odysseusAnnouncement" event (MainActivity applies the screen mode, as for the Sendspin
//     pairing prompt).
//   - Settings managed by Odysseus (odysseus/managed/ManagedSettings.kt): started with this, asked
//     for again after each hello and when the feed has a "settings" item (never a card).
// Nothing is requested while the device isn't linked to Odysseus.

import android.content.Context
import androidx.core.content.edit
import com.msp1974.vacompanion.BuildConfig
import com.msp1974.vacompanion.odysseus.OdysseusLink
import com.msp1974.vacompanion.odysseus.managed.ManagedSettings
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException

object Announcements : EventListener {
    /** Event sent on config.eventBroadcaster when a new card arrives; MainActivity turns the screen on. */
    const val EVENT_NEW = "odysseusAnnouncement"

    private val _cards = MutableStateFlow(AnnouncementCards())
    val cards: StateFlow<AnnouncementCards> = _cards.asStateFlow()

    private var scope: CoroutineScope? = null
    private var config: APPConfig? = null
    private var feed: AnnouncementFeed? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    // Hello state (in memory: a restart says hello again).
    private var lastHelloMs: Long? = null
    private var lastHelloSaid: String? = null

    @Synchronized
    fun start(context: Context, config: APPConfig) {
        if (scope != null) return
        OdysseusLink.init(context)
        this.config = config
        feed = AnnouncementFeed(PrefsFeedStore(context.applicationContext))
        config.eventBroadcaster.addListener(this)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch { run() }
        ManagedSettings.start(context) { config.uuid }
        Timber.i("Announcements: started")
    }

    @Synchronized
    fun stop() {
        val s = scope ?: return
        config?.eventBroadcaster?.removeListener(this)
        s.cancel()
        scope = null
        ManagedSettings.stop()
        lastHelloMs = null
        lastHelloSaid = null
        Timber.i("Announcements: stopped")
    }

    /** Ask the feed now (the screen came on). */
    fun pollNow() { wake.trySend(Unit) }

    fun dismiss(id: String) = _cards.update { it.dismiss(id) }

    fun prune(nowMs: Long = System.currentTimeMillis()) = _cards.update { it.prune(nowMs) }

    override fun onEventTriggered(event: Event) {
        if (event.eventName == "screenOn" && event.newValue == true) pollNow()
    }

    private fun linkSignature() = "${OdysseusLink.baseUrl}|${OdysseusLink.hasToken}"

    private suspend fun run() {
        var failures = 0
        var lastOutcome: AnnouncementPolling.Outcome? = null
        while (scope?.isActive == true) {
            val outcome = try {
                once()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Announcements: ${e.message ?: e.javaClass.simpleName}")
                AnnouncementPolling.Outcome.FAILED
            }
            failures = if (outcome == AnnouncementPolling.Outcome.FAILED) failures + 1 else 0
            if (outcome != lastOutcome) Timber.i("Announcements: feed $outcome")
            lastOutcome = outcome
            waitFor(AnnouncementPolling.nextDelay(outcome, failures))
        }
    }

    /** Waits [ms], or less when asked to poll now or the link was changed in the settings. */
    private suspend fun waitFor(ms: Long) {
        val signature = linkSignature()
        val deadline = System.currentTimeMillis() + ms
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return
            if (withTimeoutOrNull(minOf(left, AnnouncementPolling.NOT_LINKED_MS)) { wake.receive() } != null) return
            if (linkSignature() != signature) return
        }
    }

    private suspend fun once(): AnnouncementPolling.Outcome {
        val config = config ?: return AnnouncementPolling.Outcome.NOT_LINKED
        val feed = feed ?: return AnnouncementPolling.Outcome.NOT_LINKED
        val deviceId = config.uuid
        if (!OdysseusLink.isConfigured || deviceId.isBlank()) {
            lastHelloMs = null
            return AnnouncementPolling.Outcome.NOT_LINKED
        }
        val now = System.currentTimeMillis()

        val form = HelloForm.of(deviceId, OdysseusLink.deviceName, config.area, BuildConfig.VERSION_CODE)
        val saying = "${OdysseusLink.baseUrl}|${form.fields()}"
        if (AnnouncementPolling.helloDue(lastHelloMs, lastHelloSaid, saying, now)) {
            sayHello(form, saying, now)?.let { return it }
        }

        val reply = OdysseusLink.hubRequest(AnnouncementApi.feedPath(deviceId, feed.sinceFor(now)))
        return when (reply.code) {
            200 -> {
                val news = feed.acceptNews(AnnouncementApi.parseFeed(reply.body), System.currentTimeMillis())
                if (news.settingsChanged) ManagedSettings.fetchNow("feed")
                if (news.cards.isNotEmpty()) show(news.cards)
                AnnouncementPolling.Outcome.OK
            }
            401, 403 -> AnnouncementPolling.Outcome.UNAUTHORIZED
            404, 405 -> AnnouncementPolling.Outcome.NOT_SUPPORTED
            else -> {
                Timber.w("Announcements: feed answered HTTP ${reply.code}")
                AnnouncementPolling.Outcome.FAILED
            }
        }
    }

    /** Returns an outcome to stop at (token refused), or null to go on to the feed. */
    private suspend fun sayHello(form: HelloForm, saying: String, now: Long): AnnouncementPolling.Outcome? {
        val body = AnnouncementApi.formBody(form.fields()).toByteArray()
        val reply = OdysseusLink.hubRequest(AnnouncementApi.HELLO_PATH, "application/x-www-form-urlencoded", body, readTimeoutMs = 4000)
        when (reply.code) {
            in 200..299 -> {
                if (!AnnouncementApi.parseHello(reply.body)) Timber.w("Announcements: hello not ok: ${reply.body.take(200)}")
                else Timber.i("Announcements: hello sent (${form.deviceName}, area '${form.area}', ${form.appVersionCode})")
            }
            401, 403 -> return AnnouncementPolling.Outcome.UNAUTHORIZED
            404, 405 -> Timber.i("Announcements: this Odysseus has no hello route (${reply.code})")
            else -> {
                // Try again on the next poll; the feed may still answer.
                Timber.w("Announcements: hello answered HTTP ${reply.code}")
                return null
            }
        }
        lastHelloMs = now
        lastHelloSaid = saying
        ManagedSettings.fetchNow("hello")
        return null
    }

    private fun show(fresh: List<Announcement>) {
        val now = System.currentTimeMillis()
        _cards.update { it.prune(now).add(fresh, now) }
        // Ids and kinds only: the text is the owner's.
        Timber.i("Announcements: new ${fresh.joinToString { "${it.id} (${it.kind})" }}")
        config?.eventBroadcaster?.notifyEvent(Event(EVENT_NEW, "", fresh.last().id))
    }
}

/** The feed state in SharedPreferences (`odysseus_announce`), so `since` and seen ids survive a restart. */
private class PrefsFeedStore(context: Context) : AnnouncementFeedStore {
    private val prefs = context.getSharedPreferences("odysseus_announce", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override var since: Long?
        get() = prefs.getLong("since", -1L).takeIf { it >= 0 }
        set(value) = prefs.edit { if (value == null) remove("since") else putLong("since", value) }

    override var seen: Map<String, Long>
        get() = runCatching { json.decodeFromString<Map<String, Long>>(prefs.getString("seen", "{}") ?: "{}") }
            .getOrDefault(emptyMap())
        set(value) = prefs.edit { putString("seen", json.encodeToString(value)) }
}
