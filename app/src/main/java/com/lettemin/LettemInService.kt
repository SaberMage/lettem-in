package com.lettemin

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class LettemInService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_ARM_ANSWER = "arm"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_BEHAVIOR = "behavior"      // Behavior.key (string)
        const val EXTRA_AUDIO_FILE = "audio_file"  // String?, filename on Teensy SD
        const val EXTRA_AUDIO_DURATION = "audio_duration_ms"  // Long, 0 if unknown
        const val EXTRA_DTMF = "dtmf"              // String, e.g. "9" or "*"
        private const val CHANNEL_ID = "lettemin.svc"
        private const val NOTIF_ID = 1
        private const val TEENSY_VID = 0x16C0  // PJRC

        fun start(ctx: Context) {
            AppState.serviceRunning = true  // optimistic; onDestroy clears on real exit
            val i = Intent(ctx, LettemInService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(ctx, i)
        }
        fun stop(ctx: Context) {
            AppState.serviceRunning = false
            ctx.startService(Intent(ctx, LettemInService::class.java).setAction(ACTION_STOP))
        }
    }

    private lateinit var audio: AudioManager
    private lateinit var telecom: TelecomManager
    private lateinit var telephony: TelephonyManager
    private lateinit var usb: UsbManager
    private lateinit var teensy: TeensyBridge
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var inProgress: Boolean = false
    // Set by ScreeningService via ACTION_ARM_ANSWER. Cleared on IDLE.
    @Volatile private var armed: Boolean = false
    // What to do once we answer. Captured from ARM intent.
    @Volatile private var pendingBehavior: Behavior = Behavior.AUDIO_AND_DTMF
    @Volatile private var pendingAudioFile: String? = null
    @Volatile private var pendingAudioDurationMs: Long = 0L
    @Volatile private var pendingDtmf: String = "9"

    private val hangupRunnable = Runnable { hangUp() }

    /** Approx total wall-clock time the Teensy needs to finish the play sequence.
     *  Keep in sync with constants in teensy/lettem_in/lettem_in.ino. */
    private val DTMF_TOTAL_MS = 3L * 600L + 2L * 120L     // 3 bursts × 600ms + 2 gaps × 120ms
    private val GAP_AFTER_AUDIO_MS = 1000L                 // GAP_MS in firmware
    private val POST_BUFFER_MS = 2000L                     // safety pad before hangup
    private val FLOOR_MS = 5000L                           // minimum hold-time, even for DTMF-only

    private var legacyListener: PhoneStateListener? = null
    private var modernCallback: Any? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val device = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            if (device.vendorId != TEENSY_VID) return
            when (i.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> onTeensyAttached()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> onTeensyDetached()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        telecom = getSystemService(TELECOM_SERVICE) as TelecomManager
        telephony = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        usb = getSystemService(USB_SERVICE) as UsbManager
        teensy = TeensyBridge(this)
        createChannel()
        registerUsbReceiver()
        registerPhoneListener()  // now guarded internally
        AppState.serviceRunning = true
        scanForTeensy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_ARM_ANSWER) {
            armed = AppState.teensyAttached
            pendingBehavior = Behavior.fromKey(intent.getStringExtra(EXTRA_BEHAVIOR))
            pendingAudioFile = intent.getStringExtra(EXTRA_AUDIO_FILE)
            pendingAudioDurationMs = intent.getLongExtra(EXTRA_AUDIO_DURATION, 0L)
            pendingDtmf = intent.getStringExtra(EXTRA_DTMF) ?: "9"
            Log.d("LettemIn", "ARMED behavior=$pendingBehavior audio=$pendingAudioFile " +
                "dur=$pendingAudioDurationMs dtmf=$pendingDtmf armed=$armed")
        }
        try {
            startForegroundWithNotif()
        } catch (e: Throwable) {
            Log.e("LettemIn", "startForeground failed", e)
            Toast.makeText(
                this,
                "Service failed: ${e.javaClass.simpleName}: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        AppState.serviceRunning = false
        AppState.teensyAttached = false
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        unregisterPhoneListener()
        cleanupAudio()
        teensy.close()
        super.onDestroy()
    }

    // ---------------- USB attach/detach ----------------

    private fun registerUsbReceiver() {
        val f = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, f, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, f)
        }
    }

    private fun scanForTeensy() {
        val present = usb.deviceList.values.any { it.vendorId == TEENSY_VID }
        if (present) onTeensyAttached()
    }

    private fun onTeensyAttached() {
        AppState.teensyAttached = true
        teensy.open()
        refreshNotif()
    }

    private fun onTeensyDetached() {
        AppState.teensyAttached = false
        teensy.close()
        // Service is pointless without Teensy — bail out cleanly. User can replug to relaunch.
        stopSelf()
    }

    // ---------------- Notification ----------------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
    }

    private fun startForegroundWithNotif() {
        val n = buildNotif()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: FGS type=phoneCall is ONLY granted to ROLE_DIALER apps or
            // services bound by Telecom as InCallService. ROLE_CALL_SCREENING is NOT
            // sufficient — promotion throws ForegroundServiceTypeNotAllowedException and
            // the system kills the service. Stay on specialUse for the whole lifecycle.
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotif(): android.app.Notification {
        val stopIntent = PendingIntent.getBroadcast(this, 2,
            Intent(this, NotifActionReceiver::class.java).setAction(NotifActionReceiver.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openIntent = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val text = if (AppState.teensyAttached)
            getString(R.string.notif_armed)
        else
            getString(R.string.notif_idle)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.stop), stopIntent)
            .build()
    }

    private fun refreshNotif() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotif())
    }

    // ---------------- Phone state ----------------

    private fun registerPhoneListener() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) {
            // No permission → skip. Service can still hold the notif; will subscribe later.
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) { handleState(state) }
                }
                modernCallback = cb
                telephony.registerTelephonyCallback(mainExecutor, cb)
            } else {
                val l = object : PhoneStateListener() {
                    @Deprecated("legacy")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handleState(state)
                    }
                }
                legacyListener = l
                @Suppress("DEPRECATION")
                telephony.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            }
        } catch (e: SecurityException) {
            Log.w("LettemIn", "telephony listen denied", e)
        }
    }

    private fun unregisterPhoneListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (modernCallback as? TelephonyCallback)?.let { telephony.unregisterTelephonyCallback(it) }
        } else {
            legacyListener?.let { @Suppress("DEPRECATION") telephony.listen(it, PhoneStateListener.LISTEN_NONE) }
        }
    }

    private fun handleState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                // Two gates: ScreeningService must have armed (non-contact) AND Teensy attached.
                if (armed && AppState.teensyAttached && !inProgress) {
                    inProgress = true
                    answerCall()
                }
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (inProgress) {
                    main.postDelayed({ runGreetingSequence() }, 400)
                    // Auto hang-up after the play sequence finishes (dynamic).
                    main.removeCallbacks(hangupRunnable)
                    main.postDelayed(hangupRunnable, computeHangupDelayMs())
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                main.removeCallbacks(hangupRunnable)
                if (inProgress) {
                    cleanupAudio()
                    inProgress = false
                }
                armed = false  // disarm regardless of inProgress
            }
        }
    }

    private fun answerCall() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS)
            != PackageManager.PERMISSION_GRANTED) return
        try { telecom.acceptRingingCall() } catch (_: SecurityException) {}
    }

    private fun hangUp() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS)
            != PackageManager.PERMISSION_GRANTED) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                telecom.endCall()
            }
        } catch (e: SecurityException) {
            Log.w("LettemIn", "endCall denied", e)
        }
    }

    // ---------------- Greeting sequence ----------------

    private fun runGreetingSequence() {
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        if (!routeToUsbHeadset()) return
        if (!teensy.isReady) teensy.open()

        val behavior = pendingBehavior
        val file = pendingAudioFile
        val digit = pendingDtmf.firstOrNull() ?: '9'

        // Blocking CDC round-trips — off-load to a worker thread.
        Thread {
            if (behavior.involvesAudio() && !file.isNullOrBlank()) {
                if (!teensy.setActiveFile(file)) Log.w("LettemIn", "setActiveFile failed for $file")
            }
            if (behavior.involvesDtmf()) {
                if (!teensy.setDtmfDigit(digit)) Log.w("LettemIn", "setDtmfDigit failed for $digit")
            }
            val cmd = when (behavior) {
                Behavior.DTMF -> 'M'
                Behavior.AUDIO -> 'A'
                Behavior.AUDIO_AND_DTMF -> 'G'
                Behavior.REJECT -> return@Thread
            }
            main.postDelayed({ teensy.send(cmd) }, 250)
        }.start()
    }

    private fun computeHangupDelayMs(): Long {
        val audio = if (pendingBehavior.involvesAudio()) pendingAudioDurationMs else 0L
        val gap = if (pendingBehavior == Behavior.AUDIO_AND_DTMF) GAP_AFTER_AUDIO_MS else 0L
        val dtmf = if (pendingBehavior.involvesDtmf()) DTMF_TOTAL_MS else 0L
        return maxOf(FLOOR_MS, audio + gap + dtmf + POST_BUFFER_MS)
    }

    private fun routeToUsbHeadset(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audio.availableCommunicationDevices
            val usbDev = devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
            } ?: return false
            audio.setCommunicationDevice(usbDev)
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = false
            true
        }
    }

    private fun cleanupAudio() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audio.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = false
            }
            audio.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}
    }
}
