// app/src/main/java/com/guardian/shield/ui/dashboard/MainActivity.kt
package com.guardian.shield.ui.dashboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.guardian.shield.R
import com.guardian.shield.databinding.ActivityMainBinding
import com.guardian.shield.ui.settings.SettingsActivity
import com.guardian.shield.ui.setup.PinSetupActivity
import com.guardian.shield.ui.setup.PinVerifyActivity
import com.guardian.shield.viewmodel.DashboardViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: DashboardViewModel by viewModels()
    private lateinit var eventAdapter: BlockEventAdapter

    // FIX #7: Guard for switch listener
    private var isUpdatingSwitch = false

    // BUG FIX: Request POST_NOTIFICATIONS on Android 13+ so the foreground
    // service notification displays — without it the OS silently drops the
    // notification and may kill the service on low-memory devices.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Snackbar.make(
                    binding.root,
                    "Notification permission needed to keep protection running",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        requestNotificationPermission()
        setupToolbar()
        setupRecyclerView()
        setupClickListeners()
        observeState()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshProtectionState()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> { openSettingsWithPinCheck(); true }
                else -> false
            }
        }
    }

    private fun setupRecyclerView() {
        eventAdapter = BlockEventAdapter()
        binding.rvRecentEvents.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = eventAdapter
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
        }
    }

    private fun setupClickListeners() {
        // FIX #7: Switch with guard
        binding.switchProtection.setOnCheckedChangeListener { _, checked ->
            if (!isUpdatingSwitch) {
                viewModel.toggleProtection(checked)
            }
        }
        binding.cardAccessibility.setOnClickListener { showAccessibilityDialog() }
        binding.btnSetupPin.setOnClickListener {
            startActivity(Intent(this, PinSetupActivity::class.java))
        }
        binding.fabSettings.setOnClickListener { openSettingsWithPinCheck() }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                // Status banner
                val isActive = state.protectionState.isProtectionActive
                binding.tvProtectionStatus.text =
                    if (isActive) getString(R.string.protection_active)
                    else getString(R.string.protection_inactive)
                binding.cardStatus.setCardBackgroundColor(
                    getColor(if (isActive) R.color.status_active else R.color.status_inactive)
                )
                // Stats
                binding.tvTotalBlocked.text = state.stats.totalBlocked.toString()
                binding.tvTodayBlocked.text = state.stats.todayBlocked.toString()
                binding.tvLastBlocked.text  = state.stats.lastBlockedApp.ifEmpty { "None yet" }

                // FIX #7: Switch update with guard
                isUpdatingSwitch = true
                if (binding.switchProtection.isChecked != state.isProtectionOn)
                    binding.switchProtection.isChecked = state.isProtectionOn
                isUpdatingSwitch = false

                // Accessibility card
                val accessOk = state.protectionState.isAccessibilityEnabled
                binding.ivAccessibilityStatus.setImageResource(
                    if (accessOk) R.drawable.ic_check_circle else R.drawable.ic_warning
                )
                binding.tvAccessibilityStatus.text =
                    if (accessOk) "Accessibility Service: Active"
                    else "Tap to enable Accessibility Service"
                binding.cardAccessibility.strokeColor =
                    getColor(if (accessOk) R.color.status_active else R.color.status_inactive)
                // PIN card
                val pinSet = state.protectionState.isPinSet
                binding.tvPinStatus.text = if (pinSet) "PIN: Set ✓" else "PIN: Not set"
                binding.btnSetupPin.text = if (pinSet) "Change PIN" else "Set PIN"
                // Recent events
                binding.tvNoEvents.isVisible     = state.recentEvents.isEmpty()
                binding.rvRecentEvents.isVisible = state.recentEvents.isNotEmpty()
                eventAdapter.submitList(state.recentEvents)
                // Error
                state.errorMessage?.let { msg ->
                    Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
                    viewModel.clearError()
                }
            }
        }
    }

    private fun showAccessibilityDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Enable Accessibility Service")
            .setMessage(
                "Guardian Shield needs the Accessibility Service to detect and block content.\n\n" +
                "Go to: Settings → Accessibility → Installed Services → Guardian Shield → Enable."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openSettingsWithPinCheck() {
        val pinSet = viewModel.uiState.value.protectionState.isPinSet
        if (pinSet) {
            startActivity(Intent(this, PinVerifyActivity::class.java).apply {
                putExtra(PinVerifyActivity.EXTRA_DESTINATION, PinVerifyActivity.DEST_SETTINGS)
            })
        } else {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }
}