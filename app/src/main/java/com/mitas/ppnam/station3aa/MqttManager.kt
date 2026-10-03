package com.mitas.ppnam.station3aa

import android.content.Context
import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttClientState
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.lifecycle.MqttClientDisconnectedContext
import com.hivemq.client.mqtt.lifecycle.MqttDisconnectSource
import com.hivemq.client.mqtt.lifecycle.MqttClientDisconnectedListener
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.text.Charsets

class MqttManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private var client: Mqtt3AsyncClient? = null
    private val isConnecting = AtomicBoolean(false)

    /**
     * Identifies the current connection attempt. Bumped whenever an attempt is abandoned
     * (forced connect, disconnect mid-connect) so the stale client's callbacks - completion and
     * the disconnected listener - are ignored instead of clobbering the new attempt's state.
     */
    private val generation = AtomicInteger(0)
    
    private val connectionListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val stationStatusListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val connectionStatusListeners = CopyOnWriteArrayList<(ConnectionStatus) -> Unit>()
    private val statusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val statusRunnable = Runnable {
        val status = resolveStatus()
        connectionStatusListeners.forEach { it(status) }
    }
    /** What the caller wants right now — distinguishes "mid auto-retry" from "user disconnected". */
    private val wantsConnection = AtomicBoolean(true)

    private val subscriptions = ConcurrentHashMap<String, CopyOnWriteArrayList<(Mqtt3Publish) -> Unit>>()

    var isStationOnline = true
        private set

    private val settingsRepository = SettingsRepository(appContext)

    /**
     * True once the broker refused this handheld's credential (CONNACK NOT_AUTHORIZED / bad
     * username or password). Retrying can't help, so reconnects stop until Settings are saved.
     */
    @Volatile
    var brokerRejectedCredential = false
        private set

    /** Consecutive failed attempts, for [ReconnectPolicy.delayMs]. Reset on success. */
    private var reconnectAttempt = 0
    private val reconnectRunnable = Runnable { if (!isConnected()) connect() }

    // HiveMQ calls this for a failed CONNECT as well as a dropped connection, so it is the ONE
    // place reconnects are scheduled — the connect callback must not schedule a second one.
    private fun disconnectedListenerFor(gen: Int) = MqttClientDisconnectedListener { context: MqttClientDisconnectedContext ->
        if (gen != generation.get()) return@MqttClientDisconnectedListener // abandoned attempt
        Log.w("MqttManager", "Disconnected from broker: ${context.cause.message}")
        isConnecting.set(false)
        notifyListeners(false)

        when {
            // Our own disconnect() — the caller decides whether to connect again.
            context.source == MqttDisconnectSource.USER -> Unit
            ReconnectPolicy.isCredentialRejection(context.cause) -> {
                Log.e("MqttManager", "Broker rejected this handheld's credential; not retrying until Settings change")
                brokerRejectedCredential = true
                notifyConnectionStatus()
            }
            wantsConnection.get() -> scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        val delay = ReconnectPolicy.delayMs(reconnectAttempt++)
        Log.i("MqttManager", "Reconnecting in ${delay}ms (attempt $reconnectAttempt)")
        statusHandler.removeCallbacks(reconnectRunnable)
        statusHandler.postDelayed(reconnectRunnable, delay)
    }

    companion object {
        @Volatile
        private var INSTANCE: MqttManager? = null

        fun getInstance(context: Context): MqttManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MqttManager(context).also { INSTANCE = it }
            }
        }
    }

    fun isConnected(): Boolean = client?.state == MqttClientState.CONNECTED

    /** True while a CONNECT is pending (not yet connected, not yet failed). */
    fun isConnectAttemptInFlight(): Boolean = isConnecting.get()

    fun addConnectionListener(listener: (Boolean) -> Unit) {
        connectionListeners.add(listener)
        listener(isConnected())
    }

    fun removeConnectionListener(listener: (Boolean) -> Unit) {
        connectionListeners.remove(listener)
    }

    fun addStationStatusListener(listener: (Boolean) -> Unit) {
        stationStatusListeners.add(listener)
        listener(isStationOnline)
    }

    fun removeStationStatusListener(listener: (Boolean) -> Unit) {
        stationStatusListeners.remove(listener)
    }

    /**
     * Resolves what to tell the operator about connectivity, in precedence order,
     * mirroring Station 2's resolveConnectionStatus (minus clock skew, which Station 3
     * has no way to measure).
     */
    private fun resolveStatus(): ConnectionStatus = when {
        brokerRejectedCredential -> ConnectionStatus.BROKER_REJECTED
        !wantsConnection.get() -> ConnectionStatus.OFFLINE
        isConnected() && !isStationOnline -> ConnectionStatus.STATION_OFFLINE
        isConnected() && isStationOnline -> ConnectionStatus.CONNECTED
        else -> ConnectionStatus.RECONNECTING
    }

    /** Debounced like Station 2's connectionStatusFlow, so one stale sample doesn't flash the pill. */
    private fun notifyConnectionStatus() {
        statusHandler.removeCallbacks(statusRunnable)
        statusHandler.postDelayed(statusRunnable, 1_500)
    }

    fun addConnectionStatusListener(listener: (ConnectionStatus) -> Unit) {
        connectionStatusListeners.add(listener)
        listener(resolveStatus())
    }

    fun removeConnectionStatusListener(listener: (ConnectionStatus) -> Unit) {
        connectionStatusListeners.remove(listener)
    }

    fun connect(force: Boolean = false) {
        if (!force && (isConnected() || isConnecting.get())) return
        // Any explicit connect (app start, Settings saved) is a fresh try with fresh settings.
        statusHandler.removeCallbacks(reconnectRunnable)
        brokerRejectedCredential = false

        if (force) {
            // Abandon whatever is pending (old host/credentials) so this attempt is the only one.
            abandonCurrentClient()
        }

        val settings = settingsRepository.brokerSettings()
        if (!settings.hasBrokerCredential) {
            // No shared default credential exists to fall back on (Schema 4.1). Until this
            // handheld is provisioned in Settings, stay deliberately offline rather than
            // hammering the broker with a credential we know is wrong.
            Log.w("MqttManager", "No broker credential provisioned; not connecting")
            wantsConnection.set(false)
            notifyConnectionStatus()
            return
        }

        wantsConnection.set(true)
        notifyConnectionStatus()
        isConnecting.set(true)
        val gen = generation.get()

        // Presence lives on this scanner's base node (retained, also the Last Will) — the
        // contract's presence QoS is 2. The device id is derived from this handheld's hardware
        // (DeviceIdentity), not configured.
        val presenceTopic = MqttTopics.devicePresence(DeviceIdentity.deviceId(appContext))

        var builder = MqttClient.builder()
            .useMqttVersion3()
            .identifier("ScannerApp_" + UUID.randomUUID().toString().take(8))
            .serverHost(settings.host)
            .serverPort(settings.port)
            .addDisconnectedListener(disconnectedListenerFor(gen))
        if (settings.useTls) builder = builder.sslWithDefaultConfig()
        if (settings.useWebSocket) builder = builder.webSocketWithDefaultConfig()
        client = builder.buildAsync()

        client?.connectWith()
            ?.simpleAuth()
                ?.username(settings.username)
                ?.password(settings.password.toByteArray())
                ?.applySimpleAuth()
            ?.keepAlive(15)
            ?.cleanSession(true)
            ?.willPublish()
                ?.topic(presenceTopic)
                ?.payload("offline".toByteArray())
                ?.qos(MqttQos.EXACTLY_ONCE)
                ?.retain(true)
                ?.applyWillPublish()
            ?.send()
            ?.whenComplete { _, throwable ->
                if (gen != generation.get()) return@whenComplete // abandoned: a newer attempt owns the state
                isConnecting.set(false)
                if (throwable == null) {
                    Log.i("MqttManager", "Connected")
                    reconnectAttempt = 0

                    // Explicitly publish online status to clear any stale LWT
                    publish(presenceTopic, "online", true, MqttQos.EXACTLY_ONCE)

                    // Exactly the contract's scanner subscriptions (desktop MQTT_CONTRACT.md §2):
                    // station presence, own presence (for self-heal) and ALL own responses.
                    // Narrow enough for per-device broker ACLs, unlike PPNAM/station_3/#.
                    val deviceId = DeviceIdentity.deviceId(appContext)
                    subscribeInternal(MqttTopics.stationPresence(), MqttQos.EXACTLY_ONCE)
                    subscribeInternal(MqttTopics.devicePresence(deviceId), MqttQos.EXACTLY_ONCE)
                    subscribeInternal(MqttTopics.deviceResponses(deviceId), MqttQos.AT_LEAST_ONCE)

                    notifyListeners(true)
                } else {
                    // Rescheduling happens in disconnectedListener, which HiveMQ also invokes
                    // for a failed CONNECT.
                    Log.e("MqttManager", "Connection failed", throwable)
                }
            }
    }

    /**
     * Re-publishes this scanner's retained `online` presence. Called every 30 s while the login
     * screen is idle (§3) so the desktop doesn't show a connected-but-unused scanner as stale.
     */
    fun refreshPresence() {
        if (!isConnected() || !wantsConnection.get()) return
        publish(MqttTopics.devicePresence(DeviceIdentity.deviceId(appContext)), "online", true, MqttQos.EXACTLY_ONCE)
    }

    /**
     * Drops the current client without ceremony and invalidates its callbacks. Used when a pending
     * or live connection must be replaced (forced connect) or cancelled (disconnect mid-connect).
     */
    private fun abandonCurrentClient() {
        generation.incrementAndGet()
        val old = client
        client = null
        isConnecting.set(false)
        try { old?.disconnect() } catch (e: Exception) { Log.w("MqttManager", "Abandoning client failed", e) }
    }

    private fun subscribeInternal(topicFilter: String, qos: MqttQos) {
        client?.subscribeWith()
            ?.topicFilter(topicFilter)
            ?.qos(qos)
            ?.callback { publish ->
                val topic = publish.topic.toString()
                
                // Internal filtering for Station Status — retained presence on the station base node
                if (topic == MqttTopics.stationPresence()) {
                    val payload = String(publish.payloadAsBytes, Charsets.UTF_8).lowercase()
                    val online = payload == "online"
                    if (online != isStationOnline) {
                        isStationOnline = online
                        notifyStationListeners(online)
                    }
                }

                // Self-heal: a quick restart lets the previous connection's Last Will land
                // after this connection's retained `online`, sticking presence at `offline`
                // while connected — republish `online` whenever our own node reads offline.
                val ownPresence = MqttTopics.devicePresence(DeviceIdentity.deviceId(appContext))
                if (PresenceSelfHeal.shouldRestoreOnline(
                        topic,
                        String(publish.payloadAsBytes, Charsets.UTF_8),
                        ownPresence,
                        isConnected(),
                        wantsConnection.get(),
                    )
                ) {
                    Log.w("MqttManager", "Own presence read offline while connected; republishing online")
                    publish(ownPresence, "online", true, MqttQos.EXACTLY_ONCE)
                }

                // Global dispatch to other subscribers
                subscriptions.forEach { (topicFilter, callbacks) ->
                    if (matches(topic, topicFilter)) {
                        callbacks.forEach { it(publish) }
                    }
                }
            }
            ?.send()
            ?.whenComplete { _, throwable ->
                if (throwable != null) {
                    Log.e("MqttManager", "Internal subscribe failed: $topicFilter", throwable)
                } else {
                    Log.i("MqttManager", "Internally subscribed to: $topicFilter")
                }
            }
    }

    private fun notifyListeners(connected: Boolean) {
        connectionListeners.forEach { it(connected) }
        notifyConnectionStatus()
    }

    private fun notifyStationListeners(online: Boolean) {
        stationStatusListeners.forEach { it(online) }
        notifyConnectionStatus()
    }

    private fun matches(topic: String, filter: String): Boolean {
        if (topic == filter) return true
        if (filter.contains("+")) {
            val topicParts = topic.split("/")
            val filterParts = filter.split("/")
            if (topicParts.size != filterParts.size) return false
            for (i in topicParts.indices) {
                if (filterParts[i] != "+" && filterParts[i] != topicParts[i]) return false
            }
            return true
        }
        if (filter.endsWith("/#")) {
            val prefix = filter.substring(0, filter.length - 2)
            return topic.startsWith(prefix)
        }
        return false
    }

    fun publish(topic: String, payload: String, retain: Boolean = false, qos: MqttQos = MqttQos.AT_LEAST_ONCE, onComplete: (Throwable?) -> Unit = {}) {
        if (!isConnected()) {
            Log.w("MqttManager", "Cannot publish, not connected: $topic")
            onComplete(Exception("Not connected"))
            return
        }
        client?.publishWith()
            ?.topic(topic)
            ?.payload(payload.toByteArray())
            ?.qos(qos)
            ?.retain(retain)
            ?.send()
            ?.whenComplete { _, throwable ->
                onComplete(throwable)
            }
    }

    /**
     * Subscribe to a specific topic. The MqttManager will filter messages from the global PPNAM/# subscription.
     */
    fun subscribe(topic: String, callback: (Mqtt3Publish) -> Unit) {
        val callbacks = subscriptions.getOrPut(topic) { CopyOnWriteArrayList() }
        callbacks.add(callback)
        Log.d("MqttManager", "Added subscriber for topic: $topic")
    }
    
    fun unsubscribe(topic: String, callback: ((Mqtt3Publish) -> Unit)? = null) {
        if (callback == null) {
            subscriptions.remove(topic)
            Log.d("MqttManager", "Removed all subscribers for topic: $topic")
        } else {
            subscriptions[topic]?.remove(callback)
            if (subscriptions[topic]?.isEmpty() == true) {
                subscriptions.remove(topic)
            }
            Log.d("MqttManager", "Removed specific subscriber for topic: $topic")
        }
    }

    /**
     * Per HANDSCANNER_MQTT_FLOW.md #9: station _result/_response topics are shared
     * across all scanners, so isolation is done via the payload's deviceId field.
     * Accept when deviceId is missing/not a "scanner_*" id/matches this scanner;
     * drop when it names a different scanner.
     */
    fun isRelevantToThisScanner(payload: JSONObject): Boolean {
        val deviceId = payload.optString("deviceId", "")
        if (deviceId.isEmpty() || !deviceId.startsWith("scanner_")) return true
        return deviceId == DeviceIdentity.deviceId(appContext)
    }

    fun disconnect(onComplete: () -> Unit = {}) {
        wantsConnection.set(false)
        // A stale "broker rejected" from the previous settings must not be replayed to status
        // listeners registered before the next connect() (Test & Apply would report an instant
        // false rejection).
        brokerRejectedCredential = false
        statusHandler.removeCallbacks(reconnectRunnable)
        notifyConnectionStatus()
        if (!isConnected()) {
            // A CONNECT still in flight (typically against an unreachable host, which netty only
            // gives up on after 10 s) must be abandoned here: otherwise the caller's next
            // connect() is swallowed by the isConnecting guard and, with wantsConnection now
            // false, the eventual failure schedules no retry - Test & Apply with corrected
            // settings would sit Offline forever (audit review focus 5).
            abandonCurrentClient()
            onComplete()
            return
        }
        notifyListeners(false)

        // Publish offline status and wait for it to complete before disconnecting
        publish(MqttTopics.devicePresence(DeviceIdentity.deviceId(appContext)), "offline", true, MqttQos.EXACTLY_ONCE) { throwable ->
            if (throwable != null) {
                Log.e("MqttManager", "Failed to publish offline status during disconnect", throwable)
            }
            val closing = client
            if (closing == null) {
                onComplete()
                return@publish
            }
            closing.disconnect().whenComplete { _, _ ->
                // A forced connect may already have installed a newer client; leave that alone.
                if (client === closing) client = null
                onComplete()
            }
        }
    }
}
