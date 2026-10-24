package com.sonar.app

import android.content.Context
import java.io.File
import org.json.JSONObject

data class DemoClip(
    val id: String,
    val placeId: String,
    val category: String,
    val title: String,
    val transcript: String,
    val file: String,
    val duration: Double,
)

/** Bundled fixtures stay usable offline and never publish or acknowledge a real delivery. */
class DemoLibrary(private val context: Context) {
    private val manifest =
        JSONObject(context.assets.open("demo/manifest.json").bufferedReader().use { it.readText() })
    val places = manifest.objects("places").map(PlaceChoice::parse)
    val clips =
        manifest.objects("clips").map {
            DemoClip(
                it.getString("id"),
                it.getString("place_id"),
                it.getString("category"),
                it.getString("title"),
                it.getString("transcript"),
                it.getString("file"),
                it.getDouble("duration_seconds"),
            )
        }

    fun file(clip: DemoClip): File {
        check(clip in clips)
        return File(context.cacheDir, clip.file).apply {
            context.assets.open("demo/${clip.file}").use { input ->
                outputStream().use { input.copyTo(it) }
            }
        }
    }
}
