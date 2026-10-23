package com.sonar.app

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

fun JSONObject.text(key: String, fallback: String = "") =
    if (isNull(key)) fallback else optString(key, fallback)

fun JSONObject.objects(key: String): List<JSONObject> =
    optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()

fun JSONArray.strings() = (0 until length()).map { getString(it) }

fun json(vararg pairs: Pair<String, Any?>) =
    JSONObject().apply { pairs.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }

data class Profile(
    val userId: String,
    val revision: Int,
    val mode: String,
    val assist: Boolean,
    val focus: String,
    val presence: JSONObject,
) {
    companion object {
        fun parse(j: JSONObject) =
            Profile(
                j.getString("user_id"),
                j.getInt("revision"),
                j.getString("mode"),
                j.getBoolean("assist_enabled"),
                j.getString("focus"),
                j.getJSONObject("presence"),
            )
    }
}

data class Me(val profile: Profile, val recent: List<String>) {
    companion object {
        fun parse(j: JSONObject) =
            Me(
                Profile.parse(j.getJSONObject("profile")),
                j.optJSONArray("recent_question_ids")?.strings() ?: emptyList(),
            )
    }
}

data class Question(
    val id: String,
    val status: String,
    val expiresAt: String,
    val answer: JSONObject?,
    val inviteOutcome: String,
) {
    companion object {
        fun parse(j: JSONObject) =
            Question(
                j.getString("question_id"),
                j.getString("status"),
                j.getString("expires_at"),
                j.optJSONObject("answer"),
                j.text("invite_outcome", "none"),
            )
    }
}

data class Delivery(
    val id: String,
    val type: String,
    val questionId: String,
    val requestId: String,
    val revision: Int,
    val state: String,
    val expiresAt: String,
    val actionable: Boolean,
    val payload: JSONObject,
) {
    val key
        get() = "$id:$requestId"

    companion object {
        fun parse(j: JSONObject) =
            Delivery(
                j.getString("id"),
                j.getString("type"),
                j.getString("question_id"),
                j.getString("request_id"),
                j.getInt("profile_revision"),
                j.getString("state"),
                j.getString("expires_at"),
                j.getBoolean("actionable"),
                j.getJSONObject("payload"),
            )
    }
}

/** Device wall-clock may be wrong. Deadlines follow the server + monotonic elapsed time. */
class ServerClock(private val elapsed: () -> Long) {
    private var epoch: Long? = null
    private var origin = 0L

    @Synchronized
    fun sync(timestamp: String) {
        epoch = Instant.parse(timestamp).toEpochMilli()
        origin = elapsed()
    }

    @Synchronized
    fun remaining(timestamp: String): Long =
        epoch?.let { Instant.parse(timestamp).toEpochMilli() - (it + elapsed() - origin) } ?: -1L

    fun valid(d: Delivery) = d.actionable && d.state == "pending" && remaining(d.expiresAt) > 0
}

enum class AudioMode {
    IDLE,
    RECORDING,
    TRANSCRIBING,
    PREVIEW,
    ORIGINAL,
    PROMPT,
}

class AudioGate {
    @Volatile
    var mode = AudioMode.IDLE
        private set

    @Synchronized
    fun enter(next: AudioMode): Boolean {
        if (mode != AudioMode.IDLE) return false
        mode = next
        return true
    }

    @Synchronized
    fun leave() {
        mode = AudioMode.IDLE
    }
}

data class LocalDraft(
    val requestId: String,
    val file: String,
    val transcript: String = "",
    val source: String = "",
    val replyTo: String? = null,
    val invitationId: String? = null,
    val postId: String? = null,
    val frozen: Boolean = false,
) {
    fun metadata() =
        json(
                "client_request_id" to requestId,
                "kind" to if (replyTo == null) "question" else "answer",
                "transcript" to transcript,
            )
            .apply {
                if (replyTo == null) {
                    put("target_place_id", "zhonghuan_jinke")
                    put("direction", "demo_exit")
                } else {
                    put("reply_to", replyTo)
                    put("invitation_id", invitationId)
                }
            }

    fun toJson() =
        json(
            "requestId" to requestId,
            "file" to file,
            "transcript" to transcript,
            "source" to source,
            "replyTo" to replyTo,
            "invitationId" to invitationId,
            "postId" to postId,
            "frozen" to frozen,
        )

    companion object {
        fun parse(j: JSONObject) =
            LocalDraft(
                j.getString("requestId"),
                j.getString("file"),
                j.text("transcript"),
                j.text("source"),
                j.text("replyTo").ifBlank { null },
                j.text("invitationId").ifBlank { null },
                j.text("postId").ifBlank { null },
                j.optBoolean("frozen"),
            )
    }
}
