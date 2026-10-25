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

/** Missing grants are false. Legacy flags are interpreted only for legacy sessions. */
data class SessionCapabilities(
    val privateQuery: Boolean = false,
    val requestHumans: Boolean = false,
    val publishScene: Boolean = false,
    val replyThread: Boolean = false,
    val matchChat: Boolean = false,
    val autoPlay: Boolean = false,
) {
    val acceptsInput get() = privateQuery || requestHumans || publishScene || replyThread || matchChat
    val needsLocation get() = publishScene || replyThread

    fun toJson() = json("schema_version" to "1", "private_query" to privateQuery,
        "request_humans" to requestHumans, "publish_scene" to publishScene,
        "reply_thread" to replyThread, "match_chat" to matchChat, "auto_play" to autoPlay)

    fun sessionRequest(requestId: String, revision: Int, placeId: String) =
        json("client_request_id" to requestId, "expected_revision" to revision,
            "place_id" to placeId, "capabilities" to toJson())

    companion object {
        fun parse(j: JSONObject): SessionCapabilities {
            require(j.text("schema_version") == "1") { "不支持此授权协议版本" }
            fun grant(key: String): Boolean {
                if (!j.has(key)) return false
                require(j.opt(key) is Boolean) { "授权字段必须为布尔值" }
                return j.getBoolean(key)
            }
            return SessionCapabilities(grant("private_query"), grant("request_humans"),
                grant("publish_scene"), grant("reply_thread"), grant("match_chat"), grant("auto_play"))
        }

        fun fromSession(j: JSONObject): SessionCapabilities {
            j.optJSONObject("capabilities")?.let { return parse(it) }
            val publish = j.optBoolean("auto_publish", false)
            val network = publish && j.optBoolean("network_enabled", false)
            return SessionCapabilities(publish, network, publish, publish, network,
                j.optBoolean("auto_play", false))
        }
    }
}

data class VoiceMessageView(
    val id: String, val kind: String, val time: JSONObject, val place: JSONObject,
    val subject: JSONObject, val event: JSONObject, val body: JSONObject, val state: JSONObject,
    val visibility: String, val recipients: List<String>, val conversationId: String?,
) {
    companion object {
        fun parse(j: JSONObject): VoiceMessageView {
            require(j.text("schema_version") == "1") { "不支持此消息协议版本" }
            val kind = j.getString("kind")
            val visibility = j.getString("visibility")
            val recipients = j.optJSONArray("recipient_ids")?.strings() ?: emptyList()
            require(kind != "chat" || visibility == "participants") { "车友交流不能公开" }
            require(visibility != "participants" || recipients.isNotEmpty()) { "缺少交流对象" }
            return VoiceMessageView(j.getString("message_id"), kind, j.getJSONObject("time"),
                j.getJSONObject("place"), j.getJSONObject("subject"), j.getJSONObject("event"),
                j.getJSONObject("body"), j.getJSONObject("state"), visibility, recipients,
                j.text("conversation_id").ifBlank { null })
        }
    }
}

data class AgentTurnResult(
    val turnId: String, val outcome: String, val messageId: String?,
    val conversationId: String?, val reason: String?, val response: JSONObject?,
) {
    companion object {
        fun parse(envelope: JSONObject): AgentTurnResult {
            val current = envelope.optJSONObject("result")
                ?: envelope.takeIf { it.has("schema_version") }
            if (current != null) {
                require(current.text("schema_version") == "1") { "不支持此动作协议版本" }
                return AgentTurnResult(current.getString("turn_id"), current.getString("outcome"),
                    current.text("message_id").ifBlank { null }, current.text("conversation_id").ifBlank { null },
                    current.text("reason").ifBlank { null }, current.optJSONObject("response"))
            }
            val outcome = when (envelope.text("status")) {
                "published" -> if (envelope.text("action") == "question") "human_request" else "published"
                "clarifying" -> "clarification"
                "responded" -> if (envelope.text("action") in setOf("private_chat", "agent_chat")) "private_chat" else "information"
                "chatting" -> "human_chat"
                "deciding" -> "processing"
                "deferred" -> "deferred"
                "ignored" -> "ignored"
                else -> "unknown"
            }
            return AgentTurnResult(envelope.text("turn_id"), outcome,
                envelope.text("post_id").ifBlank { null }, envelope.text("root_id").ifBlank { null },
                envelope.text("reason").ifBlank { null }, envelope.optJSONObject("agent_response"))
        }
    }
}

data class Profile(
    val userId: String,
    val revision: Int,
    val mode: String,
    val assist: Boolean,
    val focus: String,
    val presence: JSONObject,
    val listening: JSONObject = JSONObject(),
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
                j.optJSONObject("listening") ?: JSONObject(),
            )
    }
}

data class PersonalTask(val id: String, val revision: Int, val status: String, val text: String,
    val questionId: String?, val sources: List<JSONObject>, val deliveries: List<JSONObject>,
    val conversationId: String? = null, val placeId: String? = null, val responseText: String = "") {
    val terminal get() = status in setOf("resolved", "cancelled", "timed_out", "failed")
    val statusLabel get() = when(status) {
        "querying" -> "正在查询"
        "clarifying" -> "等待补充信息"
        "waiting" -> "等待回应"
        "responded" -> "已有回应"
        "resolved" -> "已解决"
        "cancelled" -> "已结束"
        "timed_out" -> "已超时"
        "deferred" -> "暂未执行"
        else -> "暂不可用"
    }
    companion object {
        fun parse(j: JSONObject) = PersonalTask(j.getString("id"), j.getInt("revision"),
            j.getString("status"), j.getString("original_text"), j.text("question_id").ifBlank { null },
            j.objects("sources"), j.objects("deliveries"), j.text("conversation_id").ifBlank { null },
            j.text("place_id").ifBlank { null }, j.optJSONObject("last_result")?.optJSONObject("agent_response")?.text("text").orEmpty())
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
    val targetLabel: String? = null,
) {
    companion object {
        fun parse(j: JSONObject) =
            Question(
                j.getString("question_id"),
                j.getString("status"),
                j.getString("expires_at"),
                j.optJSONObject("answer"),
                j.text("invite_outcome", "none"),
                j.optJSONObject("target_place")?.text("label"),
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
        get() =
            payload.text("visit_id").takeIf { it.isNotBlank() }?.let { "$id:visit:$it" }
                ?: "$id:$requestId"

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

    @Synchronized
    fun now(): Instant = Instant.ofEpochMilli((epoch ?: error("请先连接服务")) + elapsed() - origin)

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
    val targetPlaceId: String = "zhonghuan_jinke",
    val targetLabel: String = "中环路金科路口",
    val recordedLocation: String? = null,
    val voiceMetadata: String? = null,
    val replyQuote: String? = null,
    val replyRecipient: String? = null,
) {
    fun metadata() =
        json(
                "client_request_id" to requestId,
                "kind" to if (replyTo == null) "question" else "answer",
                "transcript" to transcript,
            )
            .apply {
                if (replyTo == null) {
                    put("target_place_id", targetPlaceId)
                    put(
                        "direction",
                        if (targetPlaceId == "zhonghuan_jinke") "demo_exit" else "unknown",
                    )
                } else {
                    put("reply_to", replyTo)
                    put("invitation_id", invitationId)
                }
                voiceMetadata?.let { extra ->
                    val fields = JSONObject(extra)
                    fields.keys().forEach { key -> put(key, fields.get(key)) }
                    remove("direction")
                    if (fields.text("kind") == "reply") remove("invitation_id")
                }
                recordedLocation?.let { put("recorded_location", JSONObject(it)) }
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
            "targetPlaceId" to targetPlaceId,
            "targetLabel" to targetLabel,
            "recordedLocation" to recordedLocation,
            "voiceMetadata" to voiceMetadata,
            "replyQuote" to replyQuote,
            "replyRecipient" to replyRecipient,
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
                j.text("targetPlaceId", "zhonghuan_jinke"),
                j.text("targetLabel", "中环路金科路口"),
                j.text("recordedLocation").ifBlank { null },
                j.text("voiceMetadata").ifBlank { null },
                j.text("replyQuote").ifBlank { null },
                j.text("replyRecipient").ifBlank { null },
            )
    }
}

data class PlaceChoice(val id: String, val label: String) {
    companion object {
        fun parse(j: JSONObject) = PlaceChoice(j.getString("id"), j.getString("label"))
    }
}
