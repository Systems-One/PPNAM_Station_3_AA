package com.mitas.ppnam.station3aa

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import androidx.activity.addCallback
import androidx.lifecycle.Lifecycle
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
        // The password field stays empty: the stored credential is never echoed back into the UI.
        // A blank field on save means "keep the provisioned password" (see save below).

        binding.btnUnlock.setOnClickListener { submitPin() }
        binding.etPin.onSubmit { submitPin() }
        binding.etBrokerPassword.onSubmit { binding.btnSaveSettings.performClick() }

        binding.btnSaveSettings.setOnClickListener {
            val host = binding.etBrokerHost.text.toString().trim()
            val port = BrokerSettings.parsePort(binding.etBrokerPort.text.toString())
            if (host.isBlank()) {
                binding.etBrokerHost.error = "Host required"
                return@setOnClickListener
            }
            if (port == null) {
                binding.etBrokerPort.error = "Invalid port (1–65535)"
                return@setOnClickListener
            }

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

            // 1. Properly disconnect from the OLD broker first
            MqttManager.getInstance(this).disconnect {
                runOnUiThread {
                    // 2. Save the new settings after the old presence is offline
                    if (!settingsRepository.save(newSettings)) {
                        binding.etBrokerPassword.error = "Could not store the password securely"
                        MqttManager.getInstance(this).connect()
                        return@runOnUiThread
                    }

                    // 3. Reconnect against the new broker
                    MqttManager.getInstance(this).connect()

                    // Restart app to apply changes
                    val intent = Intent(this, MainActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(intent)
                    finish()
                }
            }
        }

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
     * The Diagnostics card, mirroring Station 2's SettingsScreen: broker link and station
     * presence are separate failures with separate remedies, and the composite pill can only
     * name one of them at a time — so both get their own row here.
     */
    private fun updateDiagnostics(status: ConnectionStatus) {
        val green = getColor(R.color.success)
        val blue = getColor(R.color.primary_action)
        val red = getColor(R.color.danger)
        val muted = getColor(R.color.text_muted)

        when (status) {
            ConnectionStatus.CONNECTED, ConnectionStatus.STATION_OFFLINE ->
                binding.pillBroker.setAppearance(green, "Connected")
            ConnectionStatus.RECONNECTING ->
                binding.pillBroker.setAppearance(blue, "Reconnecting")
            ConnectionStatus.OFFLINE ->
                binding.pillBroker.setAppearance(red, "Disconnected")
            ConnectionStatus.BROKER_REJECTED ->
                binding.pillBroker.setAppearance(red, "Credential rejected")
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
            androidx.appcompat.app.AlertDialog.Builder(this, R.style.AppAlertDialogTheme)
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
        OperatorSessionHolder.removeListener(sessionListener)
        MqttManager.getInstance(this).removeConnectionStatusListener(connectionStatusListener)
    }
}
