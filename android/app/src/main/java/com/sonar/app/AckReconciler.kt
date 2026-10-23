package com.sonar.app

import org.json.JSONObject

/** Report a known local completion even if a mode change rotated the server request. */
suspend fun sendStoredAck(api: SonarApi, ack: JSONObject) {
    val id = ack.getString("id")
    val event = ack.getString("event")
    try {
        api.ack(id, ack.getString("request_id"), event)
    } catch (e: ApiFailure) {
        if (e.status !in setOf(404, 409, 410)) throw e
        if (
            e.status == 409 &&
                event in setOf("played", "skipped") &&
                ack.text("post_id").isNotBlank()
        ) {
            try {
                val current = Delivery.parse(api.delivery(id).getJSONObject("delivery"))
                // Delivery ID and immutable post must match. Never rebase an invitation or a new
                // sound.
                if (
                    current.type == "audio" &&
                        current.state == "pending" &&
                        current.payload.text("post_id") == ack.getString("post_id")
                ) {
                    api.ack(id, current.requestId, event)
                }
            } catch (next: ApiFailure) {
                if (next.status !in setOf(404, 409, 410)) throw next
            }
        }
    }
}
