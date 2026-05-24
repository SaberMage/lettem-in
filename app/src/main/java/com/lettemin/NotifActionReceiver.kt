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
            ctx.startService(
                Intent(ctx, LettemInService::class.java).setAction(LettemInService.ACTION_STOP)
            )
        }
    }
}
