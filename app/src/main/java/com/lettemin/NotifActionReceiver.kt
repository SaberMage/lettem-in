package com.lettemin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class NotifActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_TOGGLE = "com.lettemin.TOGGLE"
        const val ACTION_STOP = "com.lettemin.STOP"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val svcIntent = Intent(ctx, LettemInService::class.java)
        when (intent.action) {
            ACTION_TOGGLE -> {
                svcIntent.action = LettemInService.ACTION_TOGGLE
                ContextCompat.startForegroundService(ctx, svcIntent)
            }
            ACTION_STOP -> {
                svcIntent.action = LettemInService.ACTION_STOP
                ctx.startService(svcIntent)
            }
        }
    }
}
