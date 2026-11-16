    package com.msp1974.vacompanion.broadcasts

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import com.msp1974.vacompanion.odysseus.power.MasterKeepAlivePolicy
import com.msp1974.vacompanion.odysseus.power.WifiNeed
import com.msp1974.vacompanion.odysseus.power.WifiPerformance

/** Message types carried by [VacaUdpBroadcast]. Add new ones here. */
object VacaBroadcastType {
    // Election: the master answers wake word detections and says so every few seconds.
    const val MASTER_CLAIM = "master_claim"
    const val MASTER_HEARTBEAT = "master_heartbeat"

    // Odysseus fork: a device that stopped hearing the master asks before claiming; only the master
    // answers (unicast). A master that shuts down says so, and the others elect at once.
    const val MASTER_QUERY = "master_query"
    const val MASTER_RESIGN = "master_resign"

    // Sent by a device when its listener comes up; the master answers with an immediate heartbeat.
    const val DEVICE_HELLO = "device_hello"

    // Sent by every device every few seconds, and when it hears a hello, so the others know who is
    // around and which wake word each listens for (see PeerPresence).
    // Odysseus fork: sent to the master only (every 30 s, on a wake word change), and to a device
    // that says hello or claims; the master's keep-alive carries its own wake word.
    const val PRESENCE = "presence"

    // Wake word arbitration: devices report detections to the master, which announces the winner.
    const val WAKE_DETECTION = "wake_detection"
    const val WAKE_DECISION = "wake_decision"

    // Sent by the device running a pipeline to the others in its duck group (area or custom zone).
    const val DUCK = "duck"
    const val PIPELINE_COMPLETED = "pipeline_completed" // unduck
}

/** A validated VACA broadcast received from another device. */
data class VacaBroadcastMessage(
    val type: String,
    val senderId: String,
    val data: JsonObject,
    val sourceAddress: InetAddress,
) {
    /**
     * True if this device should react to the message: it is in the sender's duck group (see
     * [APPConfig.duckGroupKey]). A blank group never matches, so unconfigured devices stay out.
     * Shared by every player that ducks for other devices' assistant activity.
     */
    fun isRelevantTo(ownDuckGroup: String): Boolean =
        duckGroup.isNotEmpty() && duckGroup == ownDuckGroup

    /** Duck group the sender belongs to, blank if none (added to every outgoing message). */
    val duckGroup: String
        get() = data[VacaUdpBroadcast.DATA_DUCK_GROUP]?.jsonPrimitive?.contentOrNull.orEmpty()
}

/**
 * Sends and listens for VACA-to-VACA UDP broadcasts on the local network, so devices in the same
 * room/home can react to each other (e.g. duck music when another device hears a wake word).
 *
 * Wire format is one JSON object per datagram:
 * `{"magic":"VACA","v":1,"type":"...","sender":"<device uuid>","data":{...}}`
 * A datagram is only accepted if it parses, carries [MAGIC] and a supported version, and does not
 * come from this device. Anything else on the port is silently dropped.
 *
 * Wake word arbitration is run by one elected master (see [arbitrateWakeWord]). It is by wake word,
 * not by area: every device that hears a wake word reports it - with its calibrated mic level and
 * detection score - to the master, wherever the device is, and after [DECISION_WINDOW_MS] the master
 * picks one for that wake word by the rules in [WakeWordArbiter] and announces it. Only devices that
 * actually heard it compete. (Ducking is separate and stays within a duck group, the devices' area or
 * custom zone.) Only that device starts a pipeline, and it
 * tells its duck group to duck now and unduck when the pipeline finishes. A report that arrives
 * after the decision is told it lost at once. The master heartbeats; when the heartbeats stop the
 * remaining devices elect a new one.
 *
 * Devices announce themselves ([VacaBroadcastType.PRESENCE]) so none waits for arbitration it
 * doesn't need: one with no other device on the network that listens for the same wake word just
 * answers, and the master decides as soon as every device that could have heard has reported
 * instead of waiting out [DECISION_WINDOW_MS].
 *
 * To add a new broadcast: add a constant to [VacaBroadcastType], call [send] where it should be
 * emitted, and register a handler with [addHandler] where it should be acted on.
 *
 * Bridges to the app's event bus (see [onEventTriggered]) so senders such as the Satellite don't
 * need a reference to this class:
 *  - in:  `audioPipelineCompleted` events are broadcast to the network (unduck)
 *  - out: `remoteWakeWordDetected` (duck) / `remotePipelineCompleted` (unduck) events are fired
 *         when the matching broadcast is received
 */
class VacaUdpBroadcast(
    private val context: Context,
    deviceManager: DeviceManager,
) : EventListener {

    private val config = deviceManager.config
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val handlers = ConcurrentHashMap<String, CopyOnWriteArrayList<(VacaBroadcastMessage) -> Unit>>()

    @Volatile private var receiveSocket: DatagramSocket? = null
    @Volatile private var sendSocket: DatagramSocket? = null

    companion object {
        const val PORT = 10950
        const val MAGIC = "VACA"
        const val VERSION = 1
        const val DATA_DUCK_GROUP = "duck_group"
        private const val MAX_PACKET_BYTES = 1024
        private const val RESTART_DELAY_MS = 5_000L

        const val DATA_SCORE = "score"
        const val DATA_LEVEL = "level"
        const val DATA_WAKE_WORD = "wake_word"
        const val DATA_RESPONDING = "responding"

        // How often a device that isn't master tells the master it is here. Peers are dropped after
        // PeerPresence.PEER_TIMEOUT_MS. Odysseus fork: 30 s, unicast to the master (was 10 s, broadcast).
        private const val PRESENCE_INTERVAL_MS = MasterKeepAlivePolicy.PRESENCE_INTERVAL_MS

        // A hello is answered after a random delay up to this, so a room of devices doesn't all reply at once.
        private const val PRESENCE_REPLY_JITTER_MS = 500
        const val DATA_ID = "id"
        const val DATA_WINNER = "winner"
        const val DATA_IDS = "ids"

        // How long the master gathers detections of one wake word before choosing. Audio is kept
        // while it waits, so it costs no speech, only the wait for the pipeline to start - which is
        // why it is short. A device that reports after it has closed is simply told it lost.
        private const val DECISION_WINDOW_MS = 150L

        // How long a device waits for the master's decision before assuming it is gone and going ahead.
        private const val DECISION_TIMEOUT_MS = 1_500L

        // Odysseus fork: keep-alive, failover and election timing are in MasterKeepAlivePolicy
        // (odysseus/power/): heartbeat every 5 s (was 2 s), master presumed gone after 12.5 s and a
        // query (was 6 s), 2.5 s for a master to answer a query or the start-up hello.
        private const val ELECTION_JITTER_MS = MasterKeepAlivePolicy.ELECTION_JITTER_MS

        // Set while running, so the satellite can arbitrate without holding a reference.
        @Volatile var instance: VacaUdpBroadcast? = null
            private set

        // Event bus names - local triggers that cause a broadcast to be sent.
        const val EVENT_PIPELINE_COMPLETED = "audioPipelineCompleted"

        // Event bus names - fired when the matching broadcast is received from another device.
        const val EVENT_REMOTE_WAKE_WORD_DETECTED = "remoteWakeWordDetected"
        const val EVENT_REMOTE_PIPELINE_COMPLETED = "remotePipelineCompleted"
    }

    @Volatile private var started = false

    fun start() {
        // BackgroundTaskController.start() can run again on a server restart.
        if (started) return
        started = true
        instance = this
        config.eventBroadcaster.addListener(this)

        addHandler(VacaBroadcastType.MASTER_CLAIM) { msg ->
            val wasMaster = isMaster
            onMasterSeen(msg.senderId)
            // Odysseus fork: a claim from a higher id while this device is master: tell the claimer
            // at once (it may have missed our keep-alives), so two masters never last.
            if (wasMaster && isMaster) sendHeartbeat(to = msg.sourceAddress)
            // Odysseus fork: a new master knows only what it has heard; tell it who is here.
            if (masterId == msg.senderId) replyPresence(msg.sourceAddress)
        }
        addHandler(VacaBroadcastType.MASTER_HEARTBEAT) { msg -> onMasterSeen(msg.senderId) }
        // A device just came up and doesn't know the master: tell it straight away.
        addHandler(VacaBroadcastType.DEVICE_HELLO) { msg ->
            if (isMaster) sendHeartbeat(to = msg.sourceAddress) // Odysseus fork: unicast
            // ...and tell it who else is here, so it needn't wait to hear from each of us.
            replyPresence(msg.sourceAddress)
        }
        // Odysseus fork: master_query and master_resign (see VacaBroadcastType).
        addHandler(VacaBroadcastType.MASTER_QUERY) { msg -> if (isMaster) sendHeartbeat(to = msg.sourceAddress) }
        addHandler(VacaBroadcastType.MASTER_RESIGN) { msg ->
            if (msg.senderId == masterId) {
                Timber.i("VacaUdpBroadcast: master ${msg.senderId} has stopped, electing")
                masterId = null
                lastMasterSeen = 0L
                // It said it has gone, so there is nobody to ask: claim (after the jitter) straight away.
                masterQueryAt = SystemClock.elapsedRealtime() - keepAlive.queryWaitMs
                loopPoke.trySend(Unit)
            }
        }
        addHandler(VacaBroadcastType.WAKE_DETECTION) { msg ->
            val id = msg.data[DATA_ID]?.jsonPrimitive?.contentOrNull ?: return@addHandler
            val score = msg.data[DATA_SCORE]?.jsonPrimitive?.floatOrNull ?: 0f
            val level = msg.data[DATA_LEVEL]?.jsonPrimitive?.floatOrNull
            val wakeWord = msg.data[DATA_WAKE_WORD]?.jsonPrimitive?.contentOrNull
            val responding = msg.data[DATA_RESPONDING]?.jsonPrimitive?.booleanOrNull ?: false
            if (isMaster) {
                collectDetection(key(wakeWord), WakeWordArbiter.Contender(id, msg.senderId, score, level, wakeWord, responding))
            }
        }
        addHandler(VacaBroadcastType.WAKE_DECISION) { msg ->
            val winner = msg.data[DATA_WINNER]?.jsonPrimitive?.contentOrNull.orEmpty()
            val ids = msg.data[DATA_IDS]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            completeAwaiting(winner, ids)
        }
        addHandler(VacaBroadcastType.DUCK) { msg ->
            // pipeline_completed is always passed on: unducking something that never ducked does nothing.
            if (config.groupDuckingEnabled) {
                config.eventBroadcaster.notifyEvent(Event(EVENT_REMOTE_WAKE_WORD_DETECTED, "", msg))
            }
        }
        addHandler(VacaBroadcastType.PIPELINE_COMPLETED) { msg ->
            config.eventBroadcaster.notifyEvent(Event(EVENT_REMOTE_PIPELINE_COMPLETED, "", msg))
        }

        scope.launch { masterLoop() }
        scope.launch { presenceLoop() }
        scope.launch { listenLoop() }
    }

    private val arbiter = WakeWordArbiter()
    private val presence = PeerPresence()

    @Volatile private var masterId: String? = null
        set(value) { field = value; updateWifiNeed() } // Odysseus fork: the master keeps Wi-Fi awake (odysseus/power/)
    @Volatile private var lastMasterSeen = 0L
    private val isMaster get() = masterId == config.uuid

    // Odysseus fork: unicast where only one device needs a message (odysseus/power/MasterKeepAlivePolicy.kt).
    private val keepAlive = MasterKeepAlivePolicy()
    // Where each device was last heard from, so reports, decisions and replies can go to it alone.
    private val peerAddresses = ConcurrentHashMap<String, InetAddress>()
    private val masterAddress get() = masterId?.let { peerAddresses[it] }
    // When this device last asked whether there is a master (its hello at start-up counts).
    @Volatile private var masterQueryAt: Long? = null
    // Wakes the election loop early: a resign, a forced election.
    private val loopPoke = Channel<Unit>(Channel.CONFLATED)
    // Master: detection ids already collected (each report comes unicast and as a broadcast copy).
    private val collectedIds = LinkedHashMap<String, Long>()

    /** Odysseus fork: the master holds Wi-Fi out of power save while other devices could report to it. */
    private fun updateWifiNeed() {
        val need = started && MasterKeepAlivePolicy.masterNeedsLock(isMaster, presence.competitors(null, config.uuid).size)
        if (need == masterNeedsWifi) return
        masterNeedsWifi = need
        WifiPerformance.set(WifiNeed.ARBITRATION_MASTER, need)
    }
    @Volatile private var masterNeedsWifi = false

    // Master only: detections gathered per wake word (see [key]) while a decision window is open.
    private val pending = HashMap<String, MutableList<WakeWordArbiter.Contender>>()

    /** What arbitration is grouped by: the wake word, case-insensitively. Blank if there isn't one. */
    private fun key(wakeWord: String?) = wakeWord?.trim()?.lowercase().orEmpty()

    // This device's reported detections waiting for the master's decision.
    private val awaiting = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val detectionCounter = AtomicLong()

    /**
     * Reports a wake word detection with its [score] and returns whether this device should start
     * the audio pipeline (Home Assistant only accepts one per wake word).
     *
     * The master collects every report of this wake word, from any area, for [DECISION_WINDOW_MS]
     * and chooses by [WakeWordArbiter]'s rules: the loudest calibrated [levelDb] (the speaker being
     * nearest to it) when it is clearly the loudest, otherwise the device that last answered, then
     * the highest [score], then the lowest device id. Only devices that heard it report, so only
     * they compete, and a device that is responding is not asked about at all unless it heard it too
     * (see [reportInterruption]), when it always wins.
     * The winner tells its duck group (area or custom zone) to duck, and later unducks via
     * `audioPipelineCompleted`. Losers never start a pipeline, so they send nothing.
     *
     * Skipped ([contest] false, or no wake word) the device just ducks and wins. The same happens
     * if no other device listens for this wake word, if no master is known yet, or it doesn't
     * answer, so a dead master never silences a device.
     */
    suspend fun arbitrateWakeWord(score: Float, levelDb: Float? = null, contest: Boolean = true): Boolean {
        val wakeWordKey = key(config.wakeWord)
        if (!contest || !config.wakeWordArbitrationEnabled || wakeWordKey.isEmpty() || masterId == null) {
            sendDuck()
            return true
        }

        // No other device listens for this wake word, so nobody else could have heard it and there
        // is nothing to decide: no round trip to the master and no window to wait out.
        // Odysseus fork: only the master's list counts. The others no longer hear every device's
        // presence (it goes to the master alone), and may sleep through a broadcast; the master,
        // awake, answers a lone device at once (everyone has reported), one unicast round trip.
        if (isMaster && presence.competitors(config.wakeWord, config.uuid).isEmpty()) {
            Timber.d("VacaUdpBroadcast: no other device listens for ${config.wakeWord}, answering without arbitration")
            sendDuck()
            return true
        }

        val me = config.uuid
        val id = "$me-${detectionCounter.incrementAndGet()}"
        val decision = CompletableDeferred<String>()
        awaiting[id] = decision
        report(id, score, levelDb, responding = false)
        val winner = withTimeoutOrNull(DECISION_TIMEOUT_MS.milliseconds) { decision.await() }
        awaiting.remove(id)

        if (winner == null) {
            Timber.w("VacaUdpBroadcast: no decision from master $masterId, starting anyway")
            if (!isMaster) {
                lastMasterSeen = 0L // forces a new election
                masterQueryAt = null; loopPoke.trySend(Unit) // Odysseus fork: ask now, claim if nobody answers
            }
            sendDuck()
            return true
        }
        val won = winner == id
        Timber.i("VacaUdpBroadcast: wake word arbitration ${if (won) "won" else "lost"} (score=$score, level=$levelDb)")
        if (won) sendDuck()
        return won
    }

    private fun sendDuck() {
        if (config.groupDuckingEnabled) send(VacaBroadcastType.DUCK)
    }

    /** Sends this device's report of the wake word to the master, or hands it over if this is the master. */
    private fun report(id: String, score: Float, levelDb: Float?, responding: Boolean) {
        if (isMaster) {
            collectDetection(
                key(config.wakeWord),
                WakeWordArbiter.Contender(id, config.uuid, score, levelDb, config.wakeWord, responding)
            )
        } else {
            val data = buildJsonObject {
                put(DATA_ID, JsonPrimitive(id))
                put(DATA_SCORE, JsonPrimitive(score))
                put(DATA_WAKE_WORD, JsonPrimitive(config.wakeWord))
                if (levelDb != null) put(DATA_LEVEL, JsonPrimitive(levelDb))
                if (responding) put(DATA_RESPONDING, JsonPrimitive(true))
            }
            // Odysseus fork: straight to the master, which is awake, so the access point doesn't
            // hold it until a beacon; and as a broadcast too, in case this device's idea of the
            // master is out of date. The master counts it once.
            masterAddress?.let { send(VacaBroadcastType.WAKE_DETECTION, data, to = it) }
            send(VacaBroadcastType.WAKE_DETECTION, data)
        }
    }

    /**
     * The wake word was heard while this device is responding, so it interrupts that response
     * whatever is decided. This tells the master, so the decision for this wake word goes to this
     * device - it always wins - and the other devices that heard it do nothing. It doesn't wait for
     * an answer. Only devices that heard the wake word are told; a responding device that didn't hear
     * it isn't asked to stop.
     */
    fun reportInterruption(score: Float, levelDb: Float?) {
        if (!config.wakeWordArbitrationEnabled || key(config.wakeWord).isEmpty() || masterId == null) return
        // Nobody else listens for this wake word, so there is nobody to tell.
        if (isMaster && presence.competitors(config.wakeWord, config.uuid).isEmpty()) return // Odysseus fork: see arbitrateWakeWord
        report("${config.uuid}-${detectionCounter.incrementAndGet()}", score, levelDb, responding = true)
    }

    private fun completeAwaiting(winner: String, ids: List<String>) {
        ids.forEach { awaiting[it]?.complete(winner) }
    }

    /**
     * Master: adds a detection to its wake word's window, opening one (and a decision timer) if needed.
     * One that arrives just after the window closed is told it lost, not made to wait and then go ahead.
     */
    private fun collectDetection(group: String, detection: WakeWordArbiter.Contender) {
        if (group.isEmpty()) return
        if (!firstCopy(detection.id)) return // Odysseus fork: each report arrives unicast and broadcast
        var lateWinner: String? = null
        var opensWindow = false
        var everyoneReported = false
        synchronized(pending) {
            val list = pending[group]
            if (list != null) {
                list.add(detection)
            } else {
                lateWinner = arbiter.lateVerdict(group)
                if (lateWinner == null) {
                    pending[group] = mutableListOf(detection)
                    opensWindow = true
                }
            }
            pending[group]?.let { everyoneReported = hasEveryoneReported(it) }
        }
        lateWinner?.let { winner ->
            Timber.i("VacaUdpBroadcast: ${detection.sender} reported after the decision, telling it it lost")
            sendDecision(winner, listOf(detection))
            completeAwaiting(winner, listOf(detection.id)) // our own broadcasts don't loop back to us
            return
        }
        if (opensWindow) scope.launch {
            delay(DECISION_WINDOW_MS.milliseconds)
            decide(group)
        }
        // No point waiting for devices that have nothing to report, or for others once a device that
        // is responding has reported, as it wins whatever they say. The window's timer then finds
        // nothing left to decide (a new window can't open for 2 s after a decision).
        if (everyoneReported || detection.responding) decide(group)
    }

    /** True once every device that could have heard this wake word has reported. */
    private fun hasEveryoneReported(reports: List<WakeWordArbiter.Contender>): Boolean {
        val wakeWord = reports.firstNotNullOfOrNull { it.wakeWord }
        val selfCompetes = PeerPresence.competes(config.wakeWord, wakeWord)
        val expected = presence.expectedReporters(wakeWord, config.uuid, selfCompetes)
        return reports.map { it.sender }.toSet().containsAll(expected)
    }

    /** Master: picks the detection that answers for wake word [groupKey] and announces it. */
    private fun decide(groupKey: String) {
        val detections = synchronized(pending) { pending.remove(groupKey) } ?: return
        val decision = arbiter.decide(groupKey, detections) ?: return
        val best = decision.winner
        val winner = decision.winnerId
        val reports = detections.joinToString { d ->
            "${d.sender.take(8)} ${d.levelDb?.let { "%.1f".format(it) } ?: "?"} dB, score ${"%.2f".format(d.score)}" +
                if (d.responding) ", responding" else ""
        }
        Timber.i("VacaUdpBroadcast: decided winner ${best.sender.take(8)}${if (best.responding) " (interrupting its response)" else ""} from ${detections.size} detection(s): $reports")

        val ids = detections.map { it.id }
        sendDecision(winner, detections)
        completeAwaiting(winner, ids) // our own broadcasts don't loop back to us
    }

    /**
     * Odysseus fork: the decision goes to each device that reported, directly (it holds Wi-Fi awake
     * from its wake word, so it gets it at once), and as a broadcast too, as before.
     */
    private fun sendDecision(winner: String, detections: List<WakeWordArbiter.Contender>) {
        val data = buildJsonObject {
            put(DATA_WINNER, JsonPrimitive(winner))
            put(DATA_IDS, buildJsonArray { detections.forEach { add(JsonPrimitive(it.id)) } })
        }
        detections.map { it.sender }.distinct().filter { it != config.uuid }
            .mapNotNull { peerAddresses[it] }
            .forEach { send(VacaBroadcastType.WAKE_DECISION, data, to = it) }
        send(VacaBroadcastType.WAKE_DECISION, data)
    }

    /** Odysseus fork: true the first time detection [id] is seen (ids are remembered for 10 s). */
    private fun firstCopy(id: String): Boolean = synchronized(collectedIds) {
        val now = SystemClock.elapsedRealtime()
        collectedIds.entries.removeIf { now - it.value > 10_000L }
        collectedIds.put(id, now) == null
    }

    /** A claim or heartbeat arrived. The lowest id wins if two devices both think they are master. */
    private fun onMasterSeen(sender: String) {
        val current = masterId
        // Odysseus fork: or the master this device knows has gone quiet (it is asking about it).
        val currentGone = current != config.uuid &&
            SystemClock.elapsedRealtime() - lastMasterSeen >= keepAlive.masterTimeoutMs
        if (current == null || current == sender || sender < current || currentGone) {
            if (current == config.uuid && sender != current) Timber.i("VacaUdpBroadcast: $sender is master, standing down")
            masterId = sender
            lastMasterSeen = SystemClock.elapsedRealtime()
        }
    }

    // Odysseus fork: event-driven (was a 1 s loop): it sleeps until the policy's next step is due,
    // or until poked. Timing: odysseus/power/MasterKeepAlivePolicy.kt.
    private suspend fun masterLoop() {
        // Starting up counts as asking: the hello sent when the listener comes up is answered by an
        // existing master. Nobody answering within the query wait, this device claims.
        lastMasterSeen = 0L
        masterQueryAt = SystemClock.elapsedRealtime()
        var lastHeartbeat = 0L
        while (scope.isActive) {
            val now = SystemClock.elapsedRealtime()
            updateWifiNeed() // peers time out without a message
            when (val step = keepAlive.next(now, isMaster, lastHeartbeat, lastMasterSeen, masterQueryAt)) {
                MasterKeepAlivePolicy.Step.Heartbeat -> {
                    sendHeartbeat()
                    lastHeartbeat = now
                }
                MasterKeepAlivePolicy.Step.QueryMaster -> {
                    Timber.i("VacaUdpBroadcast: nothing from master $masterId for a while, asking")
                    masterQueryAt = now
                    send(VacaBroadcastType.MASTER_QUERY)
                }
                MasterKeepAlivePolicy.Step.Claim -> {
                    val seen = lastMasterSeen
                    masterId = null
                    masterQueryAt = null
                    delay((0..ELECTION_JITTER_MS).random().milliseconds)
                    if (masterId == null && lastMasterSeen == seen) {
                        Timber.i("VacaUdpBroadcast: no master heard, claiming")
                        masterId = config.uuid
                        lastMasterSeen = SystemClock.elapsedRealtime()
                        send(VacaBroadcastType.MASTER_CLAIM, wakeWordData())
                        lastHeartbeat = lastMasterSeen
                    }
                }
                is MasterKeepAlivePolicy.Step.Wait ->
                    withTimeoutOrNull(step.ms.milliseconds) { loopPoke.receive() }
            }
        }
    }

    /** Odysseus fork: the master's keep-alive, which also says its wake word (its presence). */
    private fun sendHeartbeat(to: InetAddress? = null) = send(VacaBroadcastType.MASTER_HEARTBEAT, wakeWordData(), to)

    private fun wakeWordData() = buildJsonObject { put(DATA_WAKE_WORD, JsonPrimitive(config.wakeWord)) }

    fun shutdown() {
        instance = null
        config.eventBroadcaster.removeListener(this)
        handlers.clear()
        receiveSocket?.close()
        // Odysseus fork: a master that stops says so, so the others elect at once instead of
        // waiting out the keep-alive. Sent on its own thread: the scope is going and this may be
        // the main thread.
        val resign = if (isMaster) payload(VacaBroadcastType.MASTER_RESIGN, JsonObject(emptyMap())) else null
        masterNeedsWifi = false
        WifiPerformance.set(WifiNeed.ARBITRATION_MASTER, false)
        val socket = sendSocket
        if (resign != null && socket != null) {
            Thread {
                transmit(socket, resign, null)
                socket.close()
            }.start()
        } else {
            socket?.close()
        }
        scope.cancel()
    }

    /** Registers [handler] for [type]. Handlers run on the listener thread; keep them quick. */
    fun addHandler(type: String, handler: (VacaBroadcastMessage) -> Unit) {
        handlers.getOrPut(type) { CopyOnWriteArrayList() }.add(handler)
    }

    /**
     * Broadcasts a message of [type] (with optional [data]) to the local network.
     * Odysseus fork: or sends it to one device, [to], when only that one needs it. The format is the
     * same; a device in power save gets a unicast frame when it next wakes, where it can sleep
     * through a broadcast.
     */
    fun send(type: String, data: JsonObject = JsonObject(emptyMap()), to: InetAddress? = null) {
        scope.launch {
            try {
                val payload = payload(type, data)
                val socket = sendSocket ?: DatagramSocket().apply { broadcast = true }.also { sendSocket = it }
                transmit(socket, payload, to)
                if (type != VacaBroadcastType.MASTER_HEARTBEAT) Timber.d("VacaUdpBroadcast: sent $type${to?.let { " to ${it.hostAddress}" } ?: ""}")
            } catch (e: Exception) {
                Timber.e("VacaUdpBroadcast: send failed: ${e.message}")
            }
        }
    }

    private fun payload(type: String, data: JsonObject): ByteArray = buildJsonObject {
        put("magic", JsonPrimitive(MAGIC))
        put("v", JsonPrimitive(VERSION))
        put("type", JsonPrimitive(type))
        put("sender", JsonPrimitive(config.uuid))
        put("data", JsonObject(data + (DATA_DUCK_GROUP to JsonPrimitive(config.duckGroupKey))))
    }.toString().toByteArray()

    private fun transmit(socket: DatagramSocket, payload: ByteArray, to: InetAddress?) {
        // Subnet broadcast is delivered on networks that drop the limited broadcast address.
        // Only one target is used: sending to both makes every receiver see each message twice.
        val target = to ?: subnetBroadcastAddress() ?: InetAddress.getByName("255.255.255.255")
        try {
            socket.send(DatagramPacket(payload, payload.size, target, PORT))
        } catch (e: Exception) {
            Timber.d("VacaUdpBroadcast: send to $target failed: ${e.message}")
        }
    }

    private suspend fun listenLoop() {
        // Restarts after any socket failure (e.g. network change) until shutdown().
        while (scope.isActive) {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(PORT))
                }
                receiveSocket = socket
                Timber.i("VacaUdpBroadcast: listening on UDP $PORT")
                send(VacaBroadcastType.DEVICE_HELLO)
                sendPresence()
                val buffer = ByteArray(MAX_PACKET_BYTES)
                while (scope.isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    handlePacket(packet)
                }
            } catch (e: Exception) {
                if (!scope.isActive) return
                Timber.w("VacaUdpBroadcast: listener error: ${e.message}")
            } finally {
                receiveSocket?.close()
                receiveSocket = null
            }
            delay(RESTART_DELAY_MS.milliseconds)
        }
    }

    private fun handlePacket(packet: DatagramPacket) {
        val message = parse(packet) ?: return
        if (message.senderId == config.uuid) return
        // Any message shows the sender is around; presence and detections also say its wake word.
        presence.seen(message.senderId, message.data[DATA_WAKE_WORD]?.jsonPrimitive?.contentOrNull)
        // Odysseus fork: where to reach it directly; anything from the master shows it is alive;
        // a first device around makes the master keep Wi-Fi awake.
        peerAddresses[message.senderId] = message.sourceAddress
        if (message.senderId == masterId) lastMasterSeen = SystemClock.elapsedRealtime()
        if (isMaster) updateWifiNeed()
        if (message.type != VacaBroadcastType.MASTER_HEARTBEAT) Timber.d("VacaUdpBroadcast: received ${message.type} from ${message.senderId}")
        handlers[message.type]?.forEach {
            try {
                it(message)
            } catch (e: Exception) {
                Timber.e("VacaUdpBroadcast: handler for ${message.type} failed: ${e.message}")
            }
        }
    }

    /** Returns the message only if this is a well-formed VACA broadcast, otherwise null. */
    private fun parse(packet: DatagramPacket): VacaBroadcastMessage? {
        return try {
            val json = Json.parseToJsonElement(String(packet.data, 0, packet.length)).jsonObject
            if (json["magic"]?.jsonPrimitive?.contentOrNull != MAGIC) return null
            if (json["v"]?.jsonPrimitive?.intOrNull != VERSION) return null
            val type = json["type"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return null
            val sender = json["sender"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return null
            VacaBroadcastMessage(type, sender, json["data"] as? JsonObject ?: JsonObject(emptyMap()), packet.address)
        } catch (e: Exception) {
            null
        }
    }

    private fun subnetBroadcastAddress(): InetAddress? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return null
            val caps = cm.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) return null
            cm.getLinkProperties(network)?.linkAddresses
                ?.firstOrNull { it.address is Inet4Address }
                ?.let { addr ->
                    val prefix = addr.prefixLength
                    val ip = addr.address.address
                    val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
                    val bytes = ByteArray(4) { i ->
                        (ip[i].toInt() or (mask.inv() ushr (24 - 8 * i))).toByte()
                    }
                    InetAddress.getByAddress(bytes)
                }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Tells the other devices this one is here, and which wake word it listens for.
     * Odysseus fork: only the master uses it, so it goes to the master alone when there is one
     * ([to] overrides). The master's own presence is its keep-alive.
     */
    private fun sendPresence(to: InetAddress? = masterAddress) {
        if (to == null && isMaster) return
        send(VacaBroadcastType.PRESENCE, wakeWordData(), to)
    }

    /** Odysseus fork: presence in answer to a hello or a claim, to the asker, after a random delay. */
    private fun replyPresence(to: InetAddress) {
        scope.launch {
            delay((0..PRESENCE_REPLY_JITTER_MS).random().milliseconds)
            send(VacaBroadcastType.PRESENCE, wakeWordData(), to)
        }
    }

    private suspend fun presenceLoop() {
        while (scope.isActive) {
            delay(PRESENCE_INTERVAL_MS.milliseconds)
            if (!isMaster) sendPresence()
        }
    }

    override fun onEventTriggered(event: Event) {
        when (event.eventName) {
            // Others decide whether we compete with them by our wake word, so say when it changes.
            "wakeWord" -> sendPresence()
            EVENT_PIPELINE_COMPLETED -> {
                send(VacaBroadcastType.PIPELINE_COMPLETED)
            }
        }
    }
}
