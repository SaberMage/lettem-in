package com.lettemin

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val perms = buildList {
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.ANSWER_PHONE_CALLS)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.MODIFY_AUDIO_SETTINGS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }

    private val roleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refresh() }

    private val pickGreetingLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {}
            AppState.setGreetingUri(this, uri)
        }
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnGrant).setOnClickListener {
            permLauncher.launch(perms)
        }
        findViewById<Button>(R.id.btnDefaultScreening).setOnClickListener {
            requestScreeningRole()
        }
        findViewById<Button>(R.id.btnPickGreeting).setOnClickListener {
            pickGreetingLauncher.launch(arrayOf("audio/*"))
        }
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            LettemInService.start(this)
            refresh()
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            LettemInService.stop(this)
            refresh()
        }
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun requestScreeningRole() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val rm = getSystemService(RoleManager::class.java) ?: return
        if (rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
            !rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
            roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
        }
    }

    private fun refresh() {
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        val screen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
        } else true
        val running = AppState.serviceRunning
        val enabled = AppState.isEnabled(this)
        val greetingUri = AppState.getGreetingUri(this)
        val greetingLabel = greetingUri?.let { displayName(it) } ?: getString(R.string.greeting_none)
        findViewById<TextView>(R.id.statusText).text = buildString {
            append("Perms: ${if (missing.isEmpty()) "OK" else "MISSING ${missing.size}"}\n")
            append("Screening role: ${if (screen) "OK" else "NOT SET"}\n")
            append("Greeting: $greetingLabel\n")
            append("Service: ${if (running) "RUNNING" else "stopped"}\n")
            append("Auto-answer: ${if (enabled) "ENABLED" else "disabled"}")
        }
    }

    private fun displayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else uri.lastPathSegment ?: "set"
            } ?: "set"
        } catch (_: Exception) { "set" }
    }
}
