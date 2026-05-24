package com.lettemin

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.Connection
import androidx.core.content.ContextCompat

class LettemInScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val incoming = callDetails.callDirection == Call.Details.DIRECTION_INCOMING
        if (!incoming) {
            respondToCall(callDetails, allowResponse()); return
        }
        val handle: Uri? = callDetails.handle
        val number = handle?.schemeSpecificPart

        val keys = resolveKeys(number, callDetails)
        val profile = keys.firstNotNullOfOrNull { k -> ProfileRepo.findByContactKey(this, k) }

        if (profile != null) {
            applyProfile(callDetails, number, profile)
            return
        }
        // No profile matched. Let the call ring through normally.
        respondToCall(callDetails, allowResponse())
    }

    private fun applyProfile(callDetails: Call.Details, number: String?, profile: Profile) {
        when (profile.behavior) {
            Behavior.REJECT -> {
                respondToCall(callDetails, rejectResponse())
                return
            }
            else -> {
                respondToCall(callDetails, allowResponse())
                if (AppState.teensyAttached) {
                    val intent = Intent(this, LettemInService::class.java).apply {
                        action = LettemInService.ACTION_ARM_ANSWER
                        putExtra(LettemInService.EXTRA_NUMBER, number ?: "")
                        putExtra(LettemInService.EXTRA_BEHAVIOR, profile.behavior.key)
                        putExtra(LettemInService.EXTRA_AUDIO_FILE, profile.audioFile)
                        putExtra(LettemInService.EXTRA_AUDIO_DURATION, profile.audioDurationMs ?: 0L)
                        putExtra(LettemInService.EXTRA_DTMF, profile.dtmf)
                    }
                    ContextCompat.startForegroundService(this, intent)
                }
            }
        }
    }

    private fun allowResponse(): CallResponse = CallResponse.Builder()
        .setDisallowCall(false)
        .setRejectCall(false)
        .setSkipCallLog(false)
        .setSkipNotification(false)
        .build()

    private fun rejectResponse(): CallResponse = CallResponse.Builder()
        .setDisallowCall(true)
        .setRejectCall(true)
        .setSkipCallLog(false)
        .setSkipNotification(false)
        .build()

    /**
     * Buckets the caller falls into. Order matters — first match wins.
     * Priority:
     *   SPAM (STIR/SHAKEN failed)  →  real contact key  →  NOT_IN_CONTACTS  →  ANONYMOUS
     * Anonymous callers (no number) intentionally skip NOT_IN_CONTACTS.
     */
    private fun resolveKeys(number: String?, details: Call.Details): List<String> {
        val out = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (details.callerNumberVerificationStatus == Connection.VERIFICATION_STATUS_FAILED) {
                out += Profile.KEY_SPAM
            }
        }
        if (number.isNullOrBlank()) {
            out += Profile.KEY_ANONYMOUS
        } else {
            val key = contactLookupKey(number)
            if (key != null) out += key
            else out += Profile.KEY_NOT_IN_CONTACTS
        }
        return out
    }

    private fun contactLookupKey(number: String): String? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) return null
        val lookup = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number)
        )
        contentResolver.query(
            lookup,
            arrayOf(ContactsContract.PhoneLookup.LOOKUP_KEY),
            null, null, null
        )?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return null
    }
}
