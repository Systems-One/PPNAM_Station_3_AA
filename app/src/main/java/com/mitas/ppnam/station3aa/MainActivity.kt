package com.mitas.ppnam.station3aa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import com.mitas.ppnam.station3aa.databinding.ActivityMainBinding
import java.text.DecimalFormat

/**
 * Post-login home. Shows the Master Batch source-selection panel when the login's allowedTabs
 * includes `master_batch` (fail-closed: otherwise the empty-state card).
 *
 * Source selection (desktop MQTT_CONTRACT.md §7): the operator scans a pallet barcode or its
 * current RFID tag, or types it; the app sends `req/source_scan`, shows Pending until the
 * correlated `res/source_context` arrives (10 s timeout, then Retry), and displays the product,
 * available kg and the station's instruction. Pack weight, labels and printing stay on the
 * desktop — scanning never deducts stock.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var workflow: WorkflowClient

    private var connectionStatus = ConnectionStatus.OFFLINE
    /** Set by ACTION_NOT_ALLOWED — the station says this user can't scan; stop offering it. */
    private var scanForbidden = false
    private var lastScanValue: String? = null
    private val kgFormat = DecimalFormat("#,##0.###")

    private val connectionStatusListener: (ConnectionStatus) -> Unit = { status ->
        runOnUiThread {
            connectionStatus = status
            binding.connectionPill.setStatus(status)
            updateScanAvailability()
        }
    }

    private val stationStatusListener: (Boolean) -> Unit = { online ->
        runOnUiThread {
            if (online) {
                binding.layoutStationOffline.visibility = View.GONE
            } else {
                binding.layoutStationOffline.visibility = View.VISIBLE
                // Bring MainActivity to front and clear others
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
            }
        }
    }

    /**
     * Session cleared elsewhere (expiry, station rejected it). Only navigate from the foreground:
     * when Settings is on top it handles its own logout, and onResume catches anything else.
     */
    private val sessionListener: (OperatorSession?) -> Unit = { session ->
        if (session == null) runOnUiThread {
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) goToLogin()
        }
    }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action != ScannerApp.ACTION_BARCODE && action != ScannerApp.ACTION_RFID) return
            val data = intent.getStringExtra(ScannerApp.EXTRA_SCAN_DATA) ?: return
            // The Settings shortcut tag opens Settings app-wide; it is never a source.
            if (data.trim() == ScannerApp.SETTINGS_RFID) return
            runOnUiThread {
                if (!binding.scrollMasterBatch.isShown) return@runOnUiThread
                binding.etScanValue.setText(data.trim())
                submitScan(data)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // No session (fresh process, or logged out) — the home screen requires an operator.
        if (OperatorSessionHolder.session == null) {
            goToLogin()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        forceLightStatusBarIcons()
        // No enableEdgeToEdge() here: with decorFitsSystemWindows=false the manifest's
        // adjustResize is ignored and the IME inset was never applied, so "Select Source" sat
        // under the keyboard (audit S3-01). Letting the decor fit the system windows means the
        // window shrinks for the keyboard and scrollMasterBatch can scroll the button into view.

        workflow = WorkflowClient.getInstance(this)
        setupHome()
        setupMasterBatch()

        MqttManager.getInstance(this).addConnectionStatusListener(connectionStatusListener)
        MqttManager.getInstance(this).addStationStatusListener(stationStatusListener)
        OperatorSessionHolder.addListener(sessionListener)
    }

    private fun setupHome() {
        binding.btnSettings.setOnClickListener {
            startActivityForward(Intent(this, SettingsActivity::class.java))
        }

        // Operator control, mirroring Station 2's top bar: shows "name · role", tapping it asks
        // to log out.
        OperatorSessionHolder.session?.let { session ->
            binding.tvOperator.text =
                if (session.role.isNotBlank()) "${session.operatorName} · ${session.role}"
                else session.operatorName
        }
        binding.layoutOperator.setOnClickListener { showLogoutDialog() }
    }

    private fun setupMasterBatch() {
        val enabled = OperatorSessionHolder.session?.canShow(StationTab.MASTER_BATCH) == true
        binding.scrollMasterBatch.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.cardNoWorkflows.visibility = if (enabled) View.GONE else View.VISIBLE
        if (!enabled) return

        binding.btnSelectSource.applyPressScaleFeedback()
        binding.btnSelectSource.setOnClickListener { submitScan(binding.etScanValue.text?.toString()) }
        // The field sits above the keyboard but the button does not; when the window resizes
        // for the IME while the field has focus, bring the button into the visible area.
        binding.scrollMasterBatch.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val heightChanged = (bottom - top) != (oldBottom - oldTop)
            if (heightChanged && binding.etScanValue.hasFocus()) binding.btnSelectSource.post { revealSelectSource() }
        }
        binding.etScanValue.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) binding.btnSelectSource.postDelayed({ revealSelectSource() }, 300)
        }
        binding.etScanValue.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                submitScan(binding.etScanValue.text?.toString())
                true
            } else {
                false
            }
        }
        binding.btnRetryScan.setOnClickListener { lastScanValue?.let { submitScan(it) } }
        updateScanAvailability()
    }

    /** Asks the enclosing NestedScrollView to scroll until the whole button is on screen. */
    private fun revealSelectSource() {
        val button = binding.btnSelectSource
        if (button.width == 0) return
        button.requestRectangleOnScreen(Rect(0, 0, button.width, button.height), false)
    }

    private fun submitScan(raw: String?) {
        if (!canScan()) {
            updateScanAvailability()
            return
        }
        val scanValue = WorkflowProtocol.normalizeScan(raw)
        if (scanValue == null) {
            showScanStatus(getString(R.string.scan_invalid), pending = false, retry = false)
            return
        }
        lastScanValue = scanValue

        val sent = workflow.sourceScan(scanValue) { outcome -> onScanOutcome(outcome) }
        if (!sent) {
            showScanStatus(getString(R.string.scan_busy), pending = true, retry = false)
            return
        }
        // A new scan replaces the previous selection on the station (§7) — so clear it here too,
        // rather than leave an old pallet on screen while the new one is pending.
        binding.cardSource.visibility = View.GONE
        showScanStatus(getString(R.string.scan_pending), pending = true, retry = false)
        updateScanAvailability()
    }

    private fun onScanOutcome(outcome: WorkflowOutcome) {
        if (isFinishing || isDestroyed) return
        when (outcome) {
            is WorkflowOutcome.SourceSelected -> {
                binding.layoutScanStatus.visibility = View.GONE
                showSource(outcome.source, outcome.reason)
                binding.etScanValue.setText("")
            }
            is WorkflowOutcome.Rejected -> when (outcome.kind) {
                // WorkflowClient already cleared the session; sessionListener navigates.
                RejectionKind.SIGN_IN_AGAIN -> Unit
                RejectionKind.NOT_ALLOWED -> {
                    scanForbidden = true
                    showScanStatus(outcome.reason.ifBlank { getString(R.string.scan_not_allowed) }, pending = false, retry = false)
                }
                RejectionKind.SOURCE_REJECTED, RejectionKind.INVALID_REQUEST ->
                    showScanStatus(outcome.reason, pending = false, retry = false, error = true)
                RejectionKind.RETRY_LATER ->
                    showScanStatus(outcome.reason, pending = false, retry = true, error = true)
            }
            WorkflowOutcome.TimedOut ->
                showScanStatus(getString(R.string.scan_timeout), pending = false, retry = true, error = true)
            is WorkflowOutcome.Failed ->
                showScanStatus(outcome.message, pending = false, retry = true, error = true)
            WorkflowOutcome.HeartbeatOk -> Unit
        }
        updateScanAvailability()
    }

    private fun showSource(source: SourceContext, reason: String) {
        binding.tvSourceProduct.text = source.productCode
        binding.tvSourceDescription.text = source.productDescription.orEmpty()
        binding.tvSourceDescription.visibility = if (source.productDescription != null) View.VISIBLE else View.GONE
        binding.tvSourceWeight.text = "${kgFormat.format(source.availableWeightKg)} kg available"
        binding.tvSourceDetails.text = listOfNotNull(
            "Pallet ${source.palletCode ?: source.sourcePalletId}",
            source.batch?.let { "Batch $it" },
        ).joinToString(" · ")
        binding.tvSourceInstruction.text = reason
        binding.tvSourceInstruction.visibility = if (reason.isNotBlank()) View.VISIBLE else View.GONE
        binding.cardSource.visibility = View.VISIBLE
    }

    private fun showScanStatus(message: String, pending: Boolean, retry: Boolean, error: Boolean = false) {
        binding.layoutScanStatus.visibility = View.VISIBLE
        binding.progressScan.visibility = if (pending) View.VISIBLE else View.GONE
        binding.tvScanStatus.text = message
        binding.tvScanStatus.setTextColor(getColor(if (error) R.color.danger else R.color.text_primary))
        binding.btnRetryScan.visibility = if (retry && lastScanValue != null) View.VISIBLE else View.GONE
    }

    /** New workflow requests are disabled while the broker or the station is unavailable (§3). */
    private fun canScan(): Boolean =
        connectionStatus == ConnectionStatus.CONNECTED && !scanForbidden && !workflow.isScanPending

    private fun updateScanAvailability() {
        if (!::binding.isInitialized || !binding.scrollMasterBatch.isShown) return
        val available = canScan()
        binding.btnSelectSource.isEnabled = available
        binding.etScanValue.isEnabled = connectionStatus == ConnectionStatus.CONNECTED && !scanForbidden
        binding.btnRetryScan.isEnabled = available

        if (workflow.isScanPending) return
        val blocker = when {
            scanForbidden -> getString(R.string.scan_not_allowed)
            connectionStatus == ConnectionStatus.STATION_OFFLINE -> getString(R.string.scan_unavailable_station)
            connectionStatus != ConnectionStatus.CONNECTED -> getString(R.string.scan_unavailable_broker)
            else -> null
        }
        if (blocker != null) {
            showScanStatus(blocker, pending = false, retry = false)
        } else if (binding.tvScanStatus.text == getString(R.string.scan_unavailable_broker) ||
            binding.tvScanStatus.text == getString(R.string.scan_unavailable_station)
        ) {
            // Connectivity is back — drop the stale blocker message.
            binding.layoutScanStatus.visibility = View.GONE
        }
    }

    private fun showLogoutDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.AppAlertDialogTheme)
            .setTitle(getString(R.string.logout_dialog_title))
            .setMessage(getString(R.string.logout_dialog_message))
            .setPositiveButton(getString(R.string.btn_log_out)) { _, _ ->
                AuthClient(this).logout()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun goToLogin() {
        if (isFinishing) return
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        if (OperatorSessionHolder.session == null) {
            goToLogin()
            return
        }
        val filter = IntentFilter().apply {
            addAction(ScannerApp.ACTION_BARCODE)
            addAction(ScannerApp.ACTION_RFID)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(scanReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(scanReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        if (!::binding.isInitialized) return
        runCatching { unregisterReceiver(scanReceiver) }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!::binding.isInitialized) return
        MqttManager.getInstance(this).removeConnectionStatusListener(connectionStatusListener)
        MqttManager.getInstance(this).removeStationStatusListener(stationStatusListener)
        OperatorSessionHolder.removeListener(sessionListener)
    }
}
