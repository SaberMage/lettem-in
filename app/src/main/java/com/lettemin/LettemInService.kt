package com.lettemin

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
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
        const val ACTION_TOGGLE = "toggle"
        const val ACTION_ARM_ANSWER = "arm"
        const val EXTRA_NUMBER = "number"
        private const val CHANNEL_ID = "lettemin.svc"
        private const val NOTIF_ID = 1

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
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var armed: Boolean = false
    @Volatile private var inProgress: Boolean = false
    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null

    private var legacyListener: PhoneStateListener? = null
    private var modernCallback: Any? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        telecom = getSystemService(TELECOM_SERVICE) as TelecomManager
        telephony = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        createChannel()
        registerPhoneListener()
        AppState.serviceRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                AppState.setEnabled(this, !AppState.isEnabled(this))
            }
            ACTION_ARM_ANSWER -> {
                armed = AppState.isEnabled(this)
            }
        }
        startForegroundWithNotif()
        return START_STICKY
    }

    override fun onDestroy() {
        AppState.serviceRunning = false
        unregisterPhoneListener()
        cleanupAudio()
        super.onDestroy()
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
        val enabled = AppState.isEnabled(this)
        val toggleIntent = PendingIntent.getBroadcast(this, 1,
            Intent(this, NotifActionReceiver::class.java).setAction(NotifActionReceiver.ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getBroadcast(this, 2,
            Intent(this, NotifActionReceiver::class.java).setAction(NotifActionReceiver.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openIntent = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val toggleLabel = if (enabled) getString(R.string.disable) else getString(R.string.enable)
        val text = if (enabled) getString(R.string.notif_text_enabled) else getString(R.string.notif_text_disabled)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, toggleLabel, toggleIntent)
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
                if (armed && !inProgress) {
                    inProgress = true
                    answerCall()
                }
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (inProgress) {
                    // small delay to let route stabilize
                    main.postDelayed({ playGreetingThenDtmf() }, 400)
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (inProgress) {
                    cleanupAudio()
                    inProgress = false
                    armed = false
                }
            }
        }
    }

    private fun answerCall() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS)
            != PackageManager.PERMISSION_GRANTED) return
        try { telecom.acceptRingingCall() } catch (_: SecurityException) {}
    }

    // ---------------- Audio: play greeting -> DTMF 9 ----------------

    private fun playGreetingThenDtmf() {
        try {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            forceSpeakerOn()

            val uri = AppState.getGreetingUri(this)
            if (uri == null) {
                sendDtmf9()
                return
            }
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            }
            mp.setDataSource(this, uri)
            mp.setOnCompletionListener {
                main.post { sendDtmf9() }
            }
            mp.setOnErrorListener { _, _, _ -> sendDtmf9(); true }
            mp.prepare()
            mp.start()
            player = mp
        } catch (_: Exception) {
            sendDtmf9()
        }
    }

    @Suppress("DEPRECATION")
    private fun forceSpeakerOn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audio.availableCommunicationDevices
            val speaker = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (speaker != null) audio.setCommunicationDevice(speaker)
        } else {
            audio.isSpeakerphoneOn = true
        }
    }

    private fun sendDtmf9() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 100)
            tone = tg
            tg.startTone(ToneGenerator.TONE_DTMF_9, 350)
            main.postDelayed({
                try { tg.release() } catch (_: Exception) {}
                tone = null
            }, 500)
        } catch (_: RuntimeException) {}
    }

    private fun cleanupAudio() {
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        try { tone?.release() } catch (_: Exception) {}
        tone = null
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

    fun onToggleChanged() = refreshNotif()
}
