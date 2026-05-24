package com.lettemin

enum class Behavior(val key: String, val label: String) {
    REJECT("reject", "Reject"),
    DTMF("dtmf", "DTMF only"),
    AUDIO("audio", "Audio only"),
    AUDIO_AND_DTMF("audio_dtmf", "Audio + DTMF");

    companion object {
        fun fromKey(k: String?): Behavior =
            values().firstOrNull { it.key == k } ?: AUDIO_AND_DTMF
    }

    fun involvesDtmf(): Boolean = this == DTMF || this == AUDIO_AND_DTMF
    fun involvesAudio(): Boolean = this == AUDIO || this == AUDIO_AND_DTMF
}

/**
 * One profile groups a set of contacts and prescribes how Lettem In answers calls
 * from any of them.
 *
 * @param dtmf one of 0-9, *, #. Default "9".
 * @param audioDurationMs duration of the uploaded audio in milliseconds. Set when
 *        an audio file is uploaded; used to size the auto-hangup timer dynamically.
 *
 * Constraints:
 *  - A contact key belongs to at most one profile.
 *  - audioFile is the filename stored on the Teensy SD card (e.g. "p_abc.wav").
 *    Null = no audio file selected (behavior must be REJECT or DTMF).
 *
 * Special pseudo contact keys:
 *  - "ANONYMOUS"        — caller with no number / blocked caller ID.
 *  - "SPAM"             — STIR/SHAKEN attestation failed.
 *  - "NOT_IN_CONTACTS"  — caller number present but not saved as a contact.
 */
data class Profile(
    val id: String,
    val name: String,
    val behavior: Behavior,
    val audioFile: String?,
    val audioDurationMs: Long? = null,
    val dtmf: String = "9",
    val volume: Float = 0.7f,           // 0.0..1.0; applied to greeting mixer gain
    val notifyOnPickup: Boolean = false,
    val notifyOnPickupText: String = "",
    val notifyAfterAudio: Boolean = false,
    val notifyAfterAudioText: String = "",
    val hangUpWhenDone: Boolean = true,
    val contactKeys: Set<String>
) {
    companion object {
        const val KEY_ANONYMOUS = "ANONYMOUS"
        const val KEY_SPAM = "SPAM"
        const val KEY_NOT_IN_CONTACTS = "NOT_IN_CONTACTS"

        val DTMF_DIGITS = listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "#")
    }
}
