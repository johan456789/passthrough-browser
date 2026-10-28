package com.thyu.passthroughbrowser

import android.app.Activity
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.graphics.Typeface
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/** Launcher screen: help text, default-browser shortcut, port setting and connection test. */
class MainActivity : Activity() {
    private lateinit var gatewayView: TextView
    private lateinit var portField: EditText
    private lateinit var statusView: TextView
    private lateinit var defaultStatusView: TextView
    private lateinit var testButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val root = findViewById<android.view.View>(R.id.root)
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                WindowInsets.CONSUMED
            }
        }

        gatewayView = findViewById(R.id.gateway)
        portField = findViewById(R.id.port)
        statusView = findViewById(R.id.status)
        defaultStatusView = findViewById(R.id.default_status)
        testButton = findViewById(R.id.btn_test)
        if (savedInstanceState == null) portField.setText(Prefs.getPort(this).toString())

        findViewById<TextView>(R.id.version).text = getString(R.string.version_fmt, versionName())
        findViewById<Button>(R.id.btn_set_default).setOnClickListener { requestDefaultBrowser() }
        findViewById<Button>(R.id.btn_save).setOnClickListener { save() }
        testButton.setOnClickListener { testConnection() }
        portField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                save()
                true
            } else {
                false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val gw = GatewayResolver.resolve(this)
        val host = gw.address.hostAddress
        gatewayView.text = getString(
            if (gw.discovered) R.string.host_discovered else R.string.host_fallback, host,
        )
        if (Build.VERSION.SDK_INT >= 29) showDefaultStatus()
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun readPort(): Int? {
        val p = parsePort(portField.text.toString())
        if (p == null) {
            portField.error = getString(R.string.port_error)
        } else {
            portField.error = null
        }
        return p
    }

    private fun save() {
        val p = readPort() ?: return
        Prefs.setPort(this, p)
        statusView.text = getString(R.string.saved_port, p)
    }

    private fun testConnection() {
        val port = readPort() ?: return
        val gw = GatewayResolver.resolve(this)
        val target = "${gw.address.hostAddress}:$port"
        testButton.isEnabled = false
        statusView.text = getString(R.string.testing)
        val app = applicationContext
        Thread({
            val r = ServerClient(gw.address, port).health()
            val (ok, text) = ServerResponses.describeHealth(app, r, target)
            val client = (r as? CallResult.Http)?.body?.let { extractClient(it) } ?: "?"
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                statusView.text = if (ok) {
                    styledWord(
                        "", getString(R.string.test_ok_word), R.color.status_yes,
                        ": " + getString(R.string.test_ok_detail, text, target, client),
                    )
                } else {
                    styledWord("", getString(R.string.test_failed_word), R.color.status_no, ": $text")
                }
                testButton.isEnabled = true
            }
        }, "pb-test").start()
    }

    private fun extractClient(body: String): String? = try {
        org.json.JSONObject(body).optString("client", "").ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    /** [prefix] + [word] (bold, coloured) + [suffix]; only the word is styled. */
    private fun styledWord(prefix: String, word: String, colorRes: Int, suffix: String): CharSequence {
        val text = SpannableStringBuilder(prefix)
        val start = text.length
        text.append(word)
        text.setSpan(ForegroundColorSpan(getColor(colorRes)), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.append(suffix)
        return text
    }

    private fun showDefaultStatus() {
        val rm = getSystemService(RoleManager::class.java) ?: return
        if (!rm.isRoleAvailable(RoleManager.ROLE_BROWSER)) return
        val held = rm.isRoleHeld(RoleManager.ROLE_BROWSER)
        defaultStatusView.text = styledWord(
            getString(R.string.default_label),
            getString(if (held) R.string.default_yes else R.string.default_no),
            if (held) R.color.status_yes else R.color.status_no,
            "",
        )
    }

    private fun requestDefaultBrowser() {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
                if (rm.isRoleHeld(RoleManager.ROLE_BROWSER)) {
                    statusView.text = getString(R.string.already_default)
                    return
                }
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_BROWSER), REQ_ROLE)
                    return
                } catch (e: ActivityNotFoundException) {
                    // fall through to settings
                }
            }
        }
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ROLE && Build.VERSION.SDK_INT >= 29) showDefaultStatus()
    }

    private companion object {
        const val REQ_ROLE = 1
    }
}
