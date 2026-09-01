package com.mitas.ppnam.station3aa

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mitas.ppnam.station3aa.databinding.ActivityMainBinding

/**
 * Post-login home, in Station 1's MainActivity style but without workflow tiles: Station 3
 * defines no workflow tabs yet, and allowedTabs gating is fail-closed (MQTT base standard §5) —
 * an operator whose login lists tabs this app does not define simply sees none. When Station 3's
 * workflows are contracted, their tiles gate on OperatorSession.canShow exactly like Station 1's.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val connectionStatusListener: (ConnectionStatus) -> Unit = { status ->
        runOnUiThread {
            binding.connectionPill.setStatus(status)
        }
    }

    private val stationStatusListener: (Boolean) -> Unit = { online ->
        runOnUiThread {
            if (online) {
                binding.layoutStationOffline.visibility = android.view.View.GONE
            } else {
                binding.layoutStationOffline.visibility = android.view.View.VISIBLE
                // Bring MainActivity to front and clear others
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // No session (fresh process, or logged out) — the home screen requires an operator.
        if (OperatorSessionHolder.session == null) {
            startActivity(Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        setContentView(binding.root)
        forceLightStatusBarIcons()

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupHome()

        MqttManager.getInstance(this).addConnectionStatusListener(connectionStatusListener)
        MqttManager.getInstance(this).addStationStatusListener(stationStatusListener)
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

    private fun showLogoutDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.AppAlertDialogTheme)
            .setTitle(getString(R.string.logout_dialog_title))
            .setMessage(getString(R.string.logout_dialog_message))
            .setPositiveButton(getString(R.string.btn_log_out)) { _, _ ->
                AuthClient(this).logout {
                    startActivity(Intent(this, LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                    finish()
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        MqttManager.getInstance(this).removeConnectionStatusListener(connectionStatusListener)
        MqttManager.getInstance(this).removeStationStatusListener(stationStatusListener)
    }
}
