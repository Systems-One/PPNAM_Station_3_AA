package com.mitas.ppnam.station3aa

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import androidx.activity.addCallback
import androidx.lifecycle.Lifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mitas.ppnam.station3aa.databinding.ActivitySettingsBinding

class SettingsActivity : SessionActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val connectionStatusListener: (ConnectionStatus) -> Unit = { status ->
        runOnUiThread {
            binding.connectionPill.setStatus(status)
            updateDiagnostics(status)
        }
    }

    /**
     * Session ended elsewhere (inactivity, expiry, station rejection) while Settings is in front:
     * go to Login with the reason. Only a non-null -> null transition counts — Settings can be
     * opened from the login screen with no session at all.
     */
    private var hadSession = false
    private val sessionListener: (OperatorSession?) -> Unit = { session ->
        runOnUiThread {
            if (session == null && hadSession && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                goToLogin()
            }
            hadSession = session != null
        }
    }

    // Ported from Station 2's SettingsViewModel so both apps' supervisor lock behave identically.
    private val correctPin = "079545"
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var pinGate: PinGate

    private val ticker = Handler(Looper.getMainLooper())
    private val lockoutTick = Runnable { renderLockout() }
    private var lockoutShowing = false

    private var applyListener: ((ConnectionStatus) -> Unit)? = null
    private val applyTimeout = Runnable {
        finishApply(getString(R.string.apply_failed_timeout, applyHost, applyPort), error = true)
        // A hung disconnect must never strand the handheld offline.
        val mqtt = MqttManager.getInstance(this)
        if (!mqtt.isConnected() && !mqtt.isConnectAttemptInFlight()) mqtt.connect()
    }

    /** Set synchronously when Test & Apply starts, cleared by [finishApply]: blocks re-entry. */
    private var applying = false
    private var applyHost = ""
    private var applyPort = 0

    /** Settings is also reachable from Login; it only needs a session if it was opened with one. */
    override fun requiresSession(): Boolean = signedInAtCreate

    private companion object {
        /** 10 s like every other round trip, plus MqttManager's 1.5 s status debounce. */
        const val APPLY_TIMEOUT_MS = 11_500L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        forceLightStatusBarIcons()

        setupToolbar()
        MqttManager.getInstance(this).addConnectionStatusListener(connectionStatusListener)
        OperatorSessionHolder.addListener(sessionListener)

        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        binding.tvDeviceId.text = DeviceIdentity.deviceId(this)
        setupSessionSection()

        settingsRepository = SettingsRepository(this)
        val current = settingsRepository.brokerSettings()
        val (failedAttempts, lockedOutUntilMs) = settingsRepository.pinGateState()
        pinGate = PinGate(correctPin, failedAttempts, lockedOutUntilMs)
        renderLockout()

        binding.etBrokerHost.setText(current.host)
        binding.etBrokerPort.setText(current.port.toString())
        binding.swBrokerWebSocket.isChecked = current.useWebSocket
        binding.swBrokerTls.isChecked = current.useTls
        binding.etBrokerUsername.setText(current.username)
        binding.etAutoLogout.setText(settingsRepository.autoLogoutMinutes().toString())
        binding.etAutoLogout.onSubmit { binding.btnSaveSettings.performClick() }
        // The password field stays empty: the stored credential is never echoed back into the UI.
        // A blank field on save means "keep the provisioned password" (see save below).

        binding.btnUnlock.setOnClickListener { submitPin() }
        binding.etPin.onSubmit { submitPin() }
        binding.etBrokerPassword.onSubmit { binding.btnSaveSettings.performClick() }

        binding.btnSaveSettings.setOnClickListener { testAndApply() }

        binding.btnUnlock.applyPressScaleFeedback()
        binding.btnSaveSettings.applyPressScaleFeedback()
        binding.btnLogOut.applyPressScaleFeedback()

        onBackPressedDispatcher.addCallback(this) { finishBackward() }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    /**
     * Station 2's Test & Apply (audit static-06) with Station 1's validation: validate inline,
     * save, reconnect, and report the result here — the operator stays on Settings and keeps
     * the session instead of being relaunched to Home.
     */
    private fun testAndApply() {
        if (applying) return
        binding.tilBrokerHost.error = null
        binding.tilBrokerPort.error = null
        binding.tilBrokerPassword.error = null
        binding.tilAutoLogout.error = null

        val host = binding.etBrokerHost.text.toString().trim()
        val port = BrokerSettings.parsePort(binding.etBrokerPort.text.toString())
        val autoLogoutMinutes = AutoLogout.parseMinutes(binding.etAutoLogout.text.toString())
        var valid = true
        if (host.isBlank()) {
            binding.tilBrokerHost.error = getString(R.string.error_host_required)
            valid = false
        }
        if (port == null) {
            binding.tilBrokerPort.error = getString(R.string.error_port_invalid)
            valid = false
        }
        if (autoLogoutMinutes == null) {
            binding.tilAutoLogout.error = getString(R.string.error_auto_logout_minutes)
            valid = false
        }
        if (!valid || port == null || autoLogoutMinutes == null) return
        hideKeyboard()

        settingsRepository.saveAutoLogoutMinutes(autoLogoutMinutes)
        SessionGuard.applyTimeout()

        val typedPassword = binding.etBrokerPassword.text.toString()
        val newSettings = BrokerSettings(
            host = host,
            port = port,
            useWebSocket = binding.swBrokerWebSocket.isChecked,
            useTls = binding.swBrokerTls.isChecked,
            username = binding.etBrokerUsername.text.toString().trim(),
            // Blank field keeps the already-provisioned password: the repository only
            // writes a non-blank password to the Keystore.
            password = typedPassword.ifBlank { settingsRepository.brokerSettings().password },
        )

        // Everything below is asynchronous: mark the run as started first so a second press (or a
        // held Enter) cannot interleave another disconnect/connect.
        applying = true
        applyHost = host
        applyPort = port
        binding.btnSaveSettings.isEnabled = false
        showApplyStatus(getString(R.string.apply_testing), pending = true, error = false)

        // Save first: whatever happens to this screen next, the stored settings are the new ones
        // and the reconnect below uses them. (The retained "offline" presence still goes to the
        // OLD broker, because the disconnect publishes on the client that is already connected.)
        if (!settingsRepository.save(newSettings)) {
            binding.tilBrokerPassword.error = getString(R.string.error_password_store)
            finishApply(getString(R.string.apply_failed_store), error = true)
            return
        }
        binding.etBrokerPassword.setText("")

        val mqtt = MqttManager.getInstance(this)
        // The 11.5 s budget starts now, not when the disconnect callback fires.
        ticker.postDelayed(applyTimeout, APPLY_TIMEOUT_MS)
        mqtt.disconnect {
            runOnUiThread {
                if (isFinishing || isDestroyed || !applying) {
                    // The screen went away mid-apply, or the deadline already fired (its fallback
                    // may have connected; a late disconnect callback must not tear that down):
                    // still reconnect, or MQTT would stay off (disconnect() cleared
                    // wantsConnection) until the app is restarted.
                    applyListener?.let { mqtt.removeConnectionStatusListener(it) }
                    applyListener = null
                    mqtt.connect()
                    return@runOnUiThread
                }
                awaitConnectionResult(mqtt)
                // force: an attempt that was in flight against the old settings is abandoned,
                // otherwise connect()'s isConnecting guard would swallow this one.
                mqtt.connect(force = true)
            }
        }
    }

    private fun awaitConnectionResult(mqtt: MqttManager) {
        val listener: (ConnectionStatus) -> Unit = { status ->
            runOnUiThread {
                when (status) {
                    // STATION_OFFLINE still means the broker link is up; the station row says the rest.
                    ConnectionStatus.CONNECTED, ConnectionStatus.STATION_OFFLINE ->
                        finishApply(getString(R.string.apply_connected), error = false)
                    ConnectionStatus.BROKER_REJECTED ->
                        finishApply(getString(R.string.apply_failed_rejected), error = true)
                    ConnectionStatus.OFFLINE, ConnectionStatus.RECONNECTING -> Unit
                }
            }
        }
        applyListener = listener
        // addConnectionStatusListener replays the current status once (OFFLINE after the
        // disconnect, and BROKER_REJECTED can no longer be stale): ignored above.
        mqtt.addConnectionStatusListener(listener)
    }

    private fun finishApply(message: String, error: Boolean) {
        applying = false
        ticker.removeCallbacks(applyTimeout)
        applyListener?.let { MqttManager.getInstance(this).removeConnectionStatusListener(it) }
        applyListener = null
        if (!::binding.isInitialized || isFinishing || isDestroyed) return
        showApplyStatus(message, pending = false, error = error)
        binding.btnSaveSettings.isEnabled = true
    }

    private fun showApplyStatus(message: String, pending: Boolean, error: Boolean) {
        binding.layoutApplyStatus.visibility = View.VISIBLE
        binding.progressApply.visibility = if (pending) View.VISIBLE else View.GONE
        binding.tvApplyStatus.text = message
        // Pending = primary text, success = green, failure = danger red.
        val colour = when {
            error -> R.color.danger
            pending -> R.color.text_primary
            else -> R.color.success
        }
        binding.tvApplyStatus.setTextColor(getColor(colour))
        binding.btnSaveSettings.post { binding.btnSaveSettings.scrollIntoView() }
    }

    /**
     * The Diagnostics card, mirroring Station 2's SettingsScreen: broker link and station
     * presence are separate failures with separate remedies, and the composite pill can only
     * name one of them at a time — so both get their own row here.
     */
    private fun updateDiagnostics(status: ConnectionStatus) {
        val green = getColor(R.color.success)
        val blue = getColor(R.color.brand_tint)
        val red = getColor(R.color.danger)
        val muted = getColor(R.color.text_muted)

        when (status) {
            ConnectionStatus.CONNECTED, ConnectionStatus.STATION_OFFLINE ->
                binding.pillBroker.setAppearance(green, "Connected")
            ConnectionStatus.RECONNECTING ->
                binding.pillBroker.setAppearance(blue, "Reconnecting")
            ConnectionStatus.OFFLINE ->
                binding.pillBroker.setAppearance(red, "Offline")
            ConnectionStatus.BROKER_REJECTED ->
                binding.pillBroker.setAppearance(red, "Broker login rejected")
        }

        // With the broker down, the retained presence value is stale rather than false — saying
        // "offline" there would blame the station for the broker's fault.
        when (status) {
            ConnectionStatus.CONNECTED -> binding.pillStation.setAppearance(green, "Online")
            ConnectionStatus.STATION_OFFLINE -> binding.pillStation.setAppearance(blue, "Offline")
            else -> binding.pillStation.setAppearance(muted, "Unknown")
        }
    }

    /**
     * The Session card, mirroring Station 2's: the home screen's operator label is one route to
     * switching users, and Settings is the obvious second home for it.
     */
    private fun setupSessionSection() {
        val session = OperatorSessionHolder.session
        if (session == null) {
            binding.groupSession.visibility = View.GONE
            return
        }
        binding.groupSession.visibility = View.VISIBLE
        binding.tvSignedInAs.text =
            if (session.role.isNotBlank()) "${session.operatorName} · ${session.role}"
            else session.operatorName
        binding.btnLogOut.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.logout_dialog_title))
                .setMessage(getString(R.string.logout_dialog_message))
                .setPositiveButton(getString(R.string.btn_log_out)) { _, _ ->
                    // sessionListener navigates to Login once the session is cleared.
                    AuthClient(this).logout()
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }
    }

    private fun goToLogin() {
        if (isFinishing) return
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun submitPin() {
        // Hardware Enter / IME Done reach here even while the lockout has disabled Unlock.
        if (!binding.btnUnlock.isEnabled) return
        val outcome = pinGate.submit(binding.etPin.text?.toString().orEmpty(), System.currentTimeMillis())
        settingsRepository.savePinGateState(pinGate.failedAttempts, pinGate.lockedOutUntilMs)
        when (outcome) {
            PinGate.Outcome.Unlocked -> {
                binding.tilPin.error = null
                binding.etPin.setText("")
                hideKeyboard()
                binding.cardPinLock.visibility = View.GONE
                binding.groupSettingsFields.visibility = View.VISIBLE
            }
            PinGate.Outcome.Blank -> binding.tilPin.error = getString(R.string.pin_error_blank)
            is PinGate.Outcome.Incorrect -> {
                binding.etPin.setText("")
                binding.tilPin.error = resources.getQuantityString(
                    R.plurals.pin_error_incorrect, outcome.attemptsLeft, outcome.attemptsLeft,
                )
            }
            is PinGate.Outcome.LockedOut -> {
                binding.etPin.setText("")
                renderLockout()
            }
        }
    }

    /**
     * Shows the live countdown while locked out, disabling the field and Unlock, and re-enables
     * them the second the lockout ends. Reschedules itself every second while locked.
     */
    private fun renderLockout() {
        ticker.removeCallbacks(lockoutTick)
        val remainingMs = pinGate.remainingLockoutMs(System.currentTimeMillis())
        if (remainingMs > 0) {
            lockoutShowing = true
            binding.etPin.isEnabled = false
            binding.btnUnlock.isEnabled = false
            binding.tilPin.error = getString(R.string.pin_error_lockout, (remainingMs + 999) / 1_000)
            ticker.postDelayed(lockoutTick, 1_000)
        } else if (lockoutShowing) {
            lockoutShowing = false
            binding.etPin.isEnabled = true
            binding.btnUnlock.isEnabled = true
            binding.tilPin.error = null
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finishBackward()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        ticker.removeCallbacks(lockoutTick)
        ticker.removeCallbacks(applyTimeout)
        applyListener?.let { MqttManager.getInstance(this).removeConnectionStatusListener(it) }
        OperatorSessionHolder.removeListener(sessionListener)
        MqttManager.getInstance(this).removeConnectionStatusListener(connectionStatusListener)
    }
}
