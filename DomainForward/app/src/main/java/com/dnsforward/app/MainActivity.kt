package com.dnsforward.app

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

private val Int.dp: Int get() = (this * Resources.getSystem().displayMetrics.density).toInt()

class MainActivity : AppCompatActivity() {

    private lateinit var domainInput: EditText
    private lateinit var ipInput: EditText
    private lateinit var subSwitch: Switch
    private lateinit var ruleListBox: LinearLayout
    private lateinit var toggleButton: Button
    private lateinit var statusText: TextView

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                DomainForwardService.start(this)
            } else {
                toast(getString(R.string.vpn_permission_denied))
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                toast(getString(R.string.notif_permission_denied))
            }
            prepareAndStart()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        domainInput = findViewById(R.id.domain_input)
        ipInput = findViewById(R.id.ip_input)
        subSwitch = findViewById(R.id.sub_switch)
        ruleListBox = findViewById(R.id.rule_list)
        toggleButton = findViewById(R.id.toggle_button)
        statusText = findViewById(R.id.status_text)

        findViewById<Button>(R.id.add_button).setOnClickListener { addRule() }
        toggleButton.setOnClickListener { onToggleClicked() }

        RulesStore.load(this)
        renderRules()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ------------------------------------------------------------------ rules

    private fun addRule() {
        val domain = domainInput.text.toString().trim().trimEnd('.')
        val ip = ipInput.text.toString().trim()
        if (domain.isEmpty() || ip.isEmpty()) {
            toast(getString(R.string.rule_empty))
            return
        }
        if (!Valid.isDomain(domain)) {
            toast(getString(R.string.invalid_domain))
            return
        }
        if (!Valid.isIp(ip)) {
            toast(getString(R.string.invalid_ip))
            return
        }
        if (RulesStore.rules.any { it.domain == domain.lowercase() }) {
            toast(getString(R.string.duplicate_rule))
            return
        }
        val list = RulesStore.rules.toMutableList()
        list.add(Rule(domain.lowercase(), ip, subSwitch.isChecked))
        RulesStore.save(this, list)
        domainInput.text.clear()
        ipInput.text.clear()
        renderRules()
        toast(getString(R.string.rule_added))
    }

    private fun removeRule(rule: Rule) {
        val list = RulesStore.rules.filterNot { it.domain == rule.domain && it.ip == rule.ip }
        RulesStore.save(this, list)
        renderRules()
        toast(getString(R.string.rule_removed))
    }

    private fun renderRules() {
        ruleListBox.removeAllViews()
        for (rule in RulesStore.rules) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, 2.dp, 0, 2.dp)

            val label = TextView(this)
            val subTag = if (rule.sub) getString(R.string.sub_tag) else ""
            label.text = getString(R.string.rule_row_format, rule.domain, rule.ip) + subTag
            label.setTextColor(0xFF212121.toInt())
            label.textSize = 14f
            label.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

            val delete = Button(this)
            delete.text = getString(R.string.delete)
            delete.setOnClickListener { removeRule(rule) }

            row.addView(label)
            row.addView(delete)
            ruleListBox.addView(row)
        }
    }

    // ------------------------------------------------------------------ vpn

    private fun onToggleClicked() {
        if (DomainForwardService.isRunning) {
            DomainForwardService.stop(this)
            refreshStatus()
            toggleButton.postDelayed({ refreshStatus() }, 600)
        } else {
            if (RulesStore.rules.isEmpty()) {
                toast(getString(R.string.no_rules))
                return
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                prepareAndStart()
            }
        }
    }

    private fun prepareAndStart() {
        val prepared = VpnService.prepare(this)
        if (prepared == null) {
            DomainForwardService.start(this)
            toggleButton.postDelayed({ refreshStatus() }, 500)
        } else {
            vpnPermissionLauncher.launch(prepared)
        }
    }

    private fun refreshStatus() {
        val running = DomainForwardService.isRunning
        toggleButton.setText(if (running) R.string.btn_stop else R.string.btn_start)
        statusText.setText(if (running) R.string.status_running else R.string.status_idle)
        toggleButton.isEnabled = true
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
