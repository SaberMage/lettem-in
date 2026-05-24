package com.lettemin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NotifActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_STOP = "com.lettemin.STOP"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == ACTION_STOP) {
            LettemInService.stop(ctx)  // flips AppState.serviceRunning = false
        }
    }
}
