package com.sonar.app

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** Explicit UI preview only. Audio is generated silence, not a human answer. */
class MockSonarApi(private val clock: ServerClock) : SonarApi {
    private var mode = "listen"
    private var revision = 1
    private var questionId: String? = null
    private var started = 0L
    private var status = "waiting"
    private var played = false
    private var current = 0
    private val now
        get() = Instant.now().toString()

    private fun wrap(j: JSONObject): JSONObject {
        clock.sync(now)
        return j.put("server_time", now)
    }

    private fun profile() =
        json(
            "user_id" to "模拟参与者 A",
            "revision" to revision,
            "mode" to mode,
            "assist_enabled" to true,
            "focus" to "现场问答",
            "presence" to
                json(
                    "place_id" to "demo_approach",
                    "direction" to "demo_exit",
                    "source" to "simulated",
                    "updated_at" to now,
                ),
        )

    override suspend fun me() =
        wrap(
            json(
                "profile" to profile(),
                "recent_question_ids" to JSONArray(listOfNotNull(questionId)),
            )
        )

    override suspend fun context(data: JSONObject): JSONObject {
        val next = data.getString("mode")
        if (next != mode) revision++
        mode = next
        return wrap(json("profile" to profile()))
    }

    override suspend fun upload(draft: LocalDraft) =
        wrap(json("post_id" to "mock-q-${++current}", "status" to "draft"))

    override suspend fun confirm(postId: String): JSONObject {
        questionId = postId
        started = System.currentTimeMillis()
        played = false
        status = "waiting"
        return wrap(
            json(
                "question_id" to postId,
                "question_status" to status,
                "post_id" to postId,
                "status" to "published",
            )
        )
    }

    override suspend fun question(id: String): JSONObject {
        if (questionId == null) {
            questionId = id
            started = System.currentTimeMillis()
        }
        if (status == "waiting" && System.currentTimeMillis() - started > 4000) status = "answered"
        return wrap(
            json(
                "question_id" to id,
                "status" to status,
                "expires_at" to Instant.ofEpochMilli(started + 120000).toString(),
                "answer" to
                    if (status == "answered")
                        json(
                            "post_id" to "mock-a",
                            "transcript" to "模拟回复：好堵，下匝道要十五分钟。",
                            "author_label" to "模拟参与者 B",
                            "published_at" to now,
                            "audio_url" to "/mock.wav",
                        )
                    else null,
                "invite_outcome" to if (status == "answered") "answered" else "pending",
            )
        )
    }

    override suspend fun cancel(id: String): JSONObject {
        status = "cancelled"
        return question(id)
    }

    private fun item() =
        json(
            "id" to "mock-delivery-$current",
            "type" to "audio",
            "question_id" to questionId,
            "request_id" to "mock-r-$revision",
            "profile_revision" to revision,
            "state" to if (played) "played" else "pending",
            "expires_at" to Instant.now().plusSeconds(3600).toString(),
            "actionable" to (mode == "listen" && !played),
            "payload" to
                json(
                    "post_id" to "mock-a",
                    "transcript" to "模拟回复：好堵，下匝道要十五分钟。",
                    "author_label" to "模拟参与者 B（静音测试音频）",
                    "published_at" to now,
                    "audio_url" to "/mock.wav",
                ),
        )

    override suspend fun inbox() =
        wrap(
            json(
                "items" to
                    JSONArray(
                        if (status == "answered" && !played) listOf(item())
                        else emptyList<JSONObject>()
                    )
            )
        )

    override suspend fun delivery(id: String) = wrap(json("delivery" to item()))

    override suspend fun ack(id: String, requestId: String, event: String): JSONObject {
        if (event in setOf("played", "skipped")) played = true
        return delivery(id)
    }

    override suspend fun download(path: String) = Wav.synthetic()
}
