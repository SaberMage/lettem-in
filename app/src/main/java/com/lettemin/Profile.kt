package com.lettemin

enum class Behavior(val key: String, val label: String) {
    REJECT("reject", "Reject"),
    DTMF("dtmf", "DTMF 9 only"),
    AUDIO("audio", "Audio only"),
    AUDIO_AND_DTMF("audio_dtmf", "Audio + DTMF 9");

    companion object {
        fun fromKey(k: String?): Behavior =
            values().firstOrNull { it.key == k } ?: AUDIO_AND_DTMF
    }
}

/**
 * One profile groups a set of contacts and prescribes how Lettem In answers calls
 * from any of them. Constraints:
 *  - A contact key belongs to at most one profile.
 *  - audioFile is the filename stored on the Teensy SD card (e.g. "greet1.wav").
 *    Null = no audio file selected (behavior must be REJECT or DTMF).
 *
 * Special pseudo contact keys:
 *  - "ANONYMOUS" — caller with no number / blocked caller ID.
 *  - "SPAM"      — STIR/SHAKEN attestation failed (where available).
 */
data class Profile(
    val id: String,
    val name: String,
    val behavior: Behavior,
    val audioFile: String?,
    val contactKeys: Set<String>
) {
    companion object {
        const val KEY_ANONYMOUS = "ANONYMOUS"
        const val KEY_SPAM = "SPAM"
    }
}
