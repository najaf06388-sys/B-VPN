package com.bubble.vpn

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {

    private lateinit var power: PowerView
    private lateinit var status: TextView
    private lateinit var address: TextView
    private lateinit var hint: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var pendingStart = false

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0B1220")
        window.navigationBarColor = Color.parseColor("#0B1220")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0B1220"))
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        root.addView(text("BubbleVPN", 32f, "#FFFFFF", true))
        root.addView(text("Copy photos and videos to your PC over Wi-Fi", 14f, "#94A3B8", false).also {
            it.layoutParams = lp(top = dp(6))
        })

        power = PowerView(this)
        power.layoutParams = LinearLayout.LayoutParams(dp(220), dp(220)).also { it.topMargin = dp(36) }
        power.setOnClickListener { onPowerTap() }
        root.addView(power)

        status = text("", 22f, "#FFFFFF", true)
        status.layoutParams = lp(top = dp(28))
        root.addView(status)

        address = text("", 20f, "#22C55E", true)
        address.setTextIsSelectable(true)
        address.layoutParams = lp(top = dp(12))
        root.addView(address)

        hint = text("", 14f, "#94A3B8", false)
        hint.layoutParams = lp(top = dp(16))
        root.addView(hint)

        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingStart && hasStorageAccess()) {
            pendingStart = false
            startFtp()
        }
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    // ---------- actions ----------

    private fun onPowerTap() {
        if (FtpService.state != 0) {
            pendingStart = false
            stopService(Intent(this, FtpService::class.java))
            return
        }
        if (!hasStorageAccess()) {
            pendingStart = true
            Toast.makeText(this, "Please allow file access, then come back to this app", Toast.LENGTH_LONG).show()
            requestStorageAccess()
            return
        }
        startFtp()
    }

    private fun startFtp() {
        val i = Intent(this, FtpService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }
    }

    // ---------- screen ----------

    private fun refresh() {
        val st = FtpService.state
        power.state = st
        when (st) {
            2 -> {
                status.text = "Connected"
                status.setTextColor(Color.parseColor("#22C55E"))
                val ip = localIp()
                if (ip != null) {
                    address.text = "ftp://$ip:${FtpService.PORT}"
                    hint.text = "Reliable Speed  " +
                        "Connect Netherlands.\n\nSecure VPN."
                } else {
                    address.text = ""
                    hint.text = "VPN fast internet)."
                }
            }
            1 -> {
                status.text = "Starting..."
                status.setTextColor(Color.parseColor("#F59E0B"))
                address.text = ""
                hint.text = ""
            }
            else -> {
                val err = FtpService.lastError
                if (err != null) {
                    status.text = "Could not start"
                    status.setTextColor(Color.parseColor("#EF4444"))
                    address.text = ""
                    hint.text = err
                } else {
                    status.text = "Tap the power button to start"
                    status.setTextColor(Color.parseColor("#FFFFFF"))
                    address.text = ""
                    hint.text = ""
                }
            }
        }
    }

    private fun localIp(): String? {
        try {
            val list = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
            var fallback: String? = null
            for (ni in list) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name.lowercase()
                for (a in ni.inetAddresses.toList()) {
                    if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) {
                        if (name.contains("wlan") || name.startsWith("ap") || name.contains("eth")) {
                            return a.hostAddress
                        }
                        if (fallback == null) fallback = a.hostAddress
                    }
                }
            }
            return fallback
        } catch (e: Exception) {
            return null
        }
    }

    // ---------- small helpers ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun lp(top: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = top }

    private fun text(s: String, size: Float, color: String, bold: Boolean): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(color))
            if (bold) setTypeface(Typeface.DEFAULT_BOLD)
        }
}