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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class LettemInService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_ARM_ANSWER = "arm"
        const val EXTRA_NUMBER = "number"
        private const val CHANNEL_ID = "lettemin.svc"
        private const val NOTIF_ID = 1
        private const val TEENSY_VID = 0x16C0  // PJRC

        fun start(ctx: Context) {
            val i = Intent(ctx, LettemInService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(ctx, i)
        }
        fun stop(ctx: Context) {
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
        registerPhoneListener()
        AppState.serviceRunning = true
        // Scan for already-attached Teensy
        scanForTeensy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // ACTION_START or ACTION_ARM_ANSWER both fall through to (re)foreground.
        startForegroundWithNotif()
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
        refreshNotif()
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
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
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
                // Arm condition: Teensy attached AND ScreeningService flagged this as non-contact
                // (it's the one that startForegroundService'd us with ACTION_ARM_ANSWER).
                if (AppState.teensyAttached && !inProgress) {
                    inProgress = true
                    answerCall()
                }
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (inProgress) {
                    main.postDelayed({ runGreetingSequence() }, 400)
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (inProgress) {
                    cleanupAudio()
                    inProgress = false
                }
            }
        }
    }

    private fun answerCall() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS)
            != PackageManager.PERMISSION_GRANTED) return
        try { telecom.acceptRingingCall() } catch (_: SecurityException) {}
    }

    // ---------------- Greeting sequence ----------------

    private fun runGreetingSequence() {
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        if (!routeToUsbHeadset()) return
        if (!teensy.isReady) teensy.open()
        main.postDelayed({ teensy.send('G') }, 250)
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
