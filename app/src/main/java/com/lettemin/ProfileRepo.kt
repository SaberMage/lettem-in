package com.lettemin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Single-source-of-truth for profiles. Stored as JSON in SharedPreferences.
 *
 * Schema invariant: every contactKey appears in at most one profile. mutate*() helpers
 * enforce by stripping the key from any other profile before adding it here.
 */
object ProfileRepo {
    private const val PREFS = "lettemin_profiles"
    private const val KEY_JSON = "profiles"

    fun load(ctx: Context): List<Profile> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    fun save(ctx: Context, profiles: List<Profile>) {
        val arr = JSONArray()
        for (p in profiles) arr.put(toJson(p))
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_JSON, arr.toString()).apply()
    }

    /** Insert or update by id. Any contactKeys in this profile are removed from others. */
    fun upsert(ctx: Context, profile: Profile) {
        val list = load(ctx).toMutableList()
        val cleaned = list.map { other ->
            if (other.id == profile.id) other
            else other.copy(contactKeys = other.contactKeys - profile.contactKeys)
        }.toMutableList()
        val idx = cleaned.indexOfFirst { it.id == profile.id }
        if (idx >= 0) cleaned[idx] = profile else cleaned.add(profile)
        save(ctx, cleaned)
    }

    fun delete(ctx: Context, id: String) {
        save(ctx, load(ctx).filter { it.id != id })
    }

    /** Replace storage with the given list, preserving order. */
    fun reorder(ctx: Context, ordered: List<Profile>) = save(ctx, ordered)

    fun newId(): String = UUID.randomUUID().toString()

    /** Find a profile that owns the given contact key, if any. */
    fun findByContactKey(ctx: Context, key: String): Profile? =
        load(ctx).firstOrNull { key in it.contactKeys }

    private fun toJson(p: Profile): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("behavior", p.behavior.key)
        put("audioFile", p.audioFile ?: JSONObject.NULL)
        put("audioDurationMs", p.audioDurationMs ?: JSONObject.NULL)
        put("dtmf", p.dtmf)
        put("volume", p.volume.toDouble())
        put("notifyOnPickup", p.notifyOnPickup)
        put("notifyOnPickupText", p.notifyOnPickupText)
        put("notifyAfterAudio", p.notifyAfterAudio)
        put("notifyAfterAudioText", p.notifyAfterAudioText)
        put("hangUpWhenDone", p.hangUpWhenDone)
        put("contactKeys", JSONArray(p.contactKeys.toList()))
    }

    private fun fromJson(j: JSONObject): Profile {
        val keysArr = j.optJSONArray("contactKeys") ?: JSONArray()
        val keys = mutableSetOf<String>()
        for (i in 0 until keysArr.length()) keys.add(keysArr.getString(i))
        return Profile(
            id = j.getString("id"),
            name = j.getString("name"),
            behavior = Behavior.fromKey(if (j.has("behavior")) j.getString("behavior") else null),
            audioFile = if (!j.has("audioFile") || j.isNull("audioFile")) null else j.getString("audioFile"),
            audioDurationMs = if (!j.has("audioDurationMs") || j.isNull("audioDurationMs")) null else j.getLong("audioDurationMs"),
            dtmf = if (j.has("dtmf")) j.getString("dtmf") else "9",
            volume = if (j.has("volume")) j.getDouble("volume").toFloat().coerceIn(0f, 1f) else 0.7f,
            notifyOnPickup = j.optBoolean("notifyOnPickup", false),
            notifyOnPickupText = j.optString("notifyOnPickupText", ""),
            notifyAfterAudio = j.optBoolean("notifyAfterAudio", false),
            notifyAfterAudioText = j.optString("notifyAfterAudioText", ""),
            hangUpWhenDone = j.optBoolean("hangUpWhenDone", true),
            contactKeys = keys
        )
    }
}
