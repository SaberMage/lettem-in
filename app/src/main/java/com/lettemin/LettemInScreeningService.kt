package com.lettemin

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.core.content.ContextCompat

class LettemInScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val incoming = callDetails.callDirection == Call.Details.DIRECTION_INCOMING
        val handle: Uri? = callDetails.handle
        val number = handle?.schemeSpecificPart

        // Always allow the call to ring. We never block.
        val response = CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()
        respondToCall(callDetails, response)

        if (!incoming || number.isNullOrBlank()) return
        // Arm only when Teensy is attached. No Teensy = no greeting + no DTMF = pointless to answer.
        if (!AppState.teensyAttached) return
        if (isContact(number)) return

        val intent = Intent(this, LettemInService::class.java).apply {
            action = LettemInService.ACTION_ARM_ANSWER
            putExtra(LettemInService.EXTRA_NUMBER, number)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun isContact(number: String): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) return false
        val lookup = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number)
        )
        contentResolver.query(
            lookup,
            arrayOf(ContactsContract.PhoneLookup._ID),
            null, null, null
        )?.use { c -> if (c.moveToFirst()) return true }
        return false
    }
}
