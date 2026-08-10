package com.watch.hsp.service

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.watch.hsp.BleProtocol
import com.watch.hsp.WatchNotificationListenerService
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bridges Android's active media session to the watch. AVRCP has no lyrics
 * field, so the current track is matched against LRCLIB on the phone.
 */
class MediaLyricsMonitor(
    context: Context,
    private val handler: Handler,
    private val listener: Listener
) {
    interface Listener {
        fun onLyricChanged(generation: Int, lyric: String)
        fun onCoverAvailable(generation: Int, jpeg: ByteArray)
    }

    private data class Track(
        val key: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val artwork: Bitmap?,
        val artworkUri: String?
    )

    private data class LyricsResponse(val synced: String?, val plain: String?)

    private val appContext = context.applicationContext
    private val mediaSessionManager = appContext.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(appContext, WatchNotificationListenerService::class.java)
    private val coverWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hsp-cover-sync").apply { isDaemon = true }
    }
    private val lyricsWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hsp-lyrics-sync").apply { isDaemon = true }
    }
    private var running = false
    private var generation = 0
    private var currentTrackKey: String? = null
    private var currentController: MediaController? = null
    private var currentLines: List<TimedLyricLine> = emptyList()
    private var cachedCover: ByteArray? = null
    private var coverLoadInFlight = false
    private var nextCoverRetryAtMs = 0L
    private var currentLyricText = ""
    private var lastSentLyric: String? = null
    private var nextPollDelayMs = IDLE_POLL_INTERVAL_MS
    private var mediaAccessWarningLogged = false
    private var noMediaSessionLogged = false

    private val pollTask = object : Runnable {
        override fun run() {
            if (!running) return
            refresh()
            handler.postDelayed(this, nextPollDelayMs)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(pollTask)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(pollTask)
        generation = nextGeneration(generation)
        coverWorker.shutdownNow()
        lyricsWorker.shutdownNow()
    }

    /** Re-send cached media state after a new BLE connection becomes writable. */
    fun syncNow() {
        handler.post {
            lastSentLyric = null
            cachedCover?.let { listener.onCoverAvailable(generation, it) }
            if (currentLyricText.isNotEmpty()) emitLyric(currentLyricText)
            refresh()
        }
    }

    private fun refresh() {
        val controllers = try {
            mediaSessionManager?.getActiveSessions(listenerComponent).orEmpty()
        } catch (exception: SecurityException) {
            nextPollDelayMs = ACCESS_RETRY_INTERVAL_MS
            if (currentTrackKey != null) clearTrack("需要开启通知使用权以同步歌词")
            if (!mediaAccessWarningLogged) {
                mediaAccessWarningLogged = true
                Log.w(TAG, "Media-session access is not granted", exception)
            }
            return
        }
        mediaAccessWarningLogged = false

        val controller = controllers.maxByOrNull(::controllerScore)
        val metadata = controller?.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.description?.title?.toString()
            ?: ""
        if (controller == null || metadata == null || title.isBlank()) {
            nextPollDelayMs = IDLE_POLL_INTERVAL_MS
            if (!noMediaSessionLogged) {
                noMediaSessionLogged = true
                Log.w(TAG, "No active media session with usable metadata")
            }
            if (currentTrackKey != null) clearTrack("")
            return
        }
        noMediaSessionLogged = false
        nextPollDelayMs = when (controller.playbackState?.state) {
            PlaybackState.STATE_PLAYING -> PLAYING_POLL_INTERVAL_MS
            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING ->
                PLAYING_POLL_INTERVAL_MS
            PlaybackState.STATE_PAUSED -> PAUSED_POLL_INTERVAL_MS
            else -> IDLE_POLL_INTERVAL_MS
        }

        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata.description?.subtitle?.toString()
            ?: ""
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)
        val mediaId = metadata.description?.mediaId.orEmpty()
        val trackNumber = metadata.getLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER)
        val artwork = readArtwork(metadata)
        val artworkUri = readArtworkUri(metadata)
        val artIdentity = artworkIdentity(artwork, artworkUri)
        // Some players publish the current lyric as TITLE. Prefer stable media
        // fields so every lyric line is not mistaken for a new track.
        val stableIdentity = when {
            mediaId.isNotBlank() -> listOf("id", mediaId, artist, album, duration.toString())
            artist.isNotBlank() || album.isNotBlank() || duration > 0L ->
                listOf("metadata", artist, album, duration.toString(), trackNumber.toString())
            else -> listOf("title", title)
        }
        val key = (listOf(controller.packageName) + stableIdentity +
            listOf("art", artIdentity)).joinToString("\u001f")

        currentController = controller
        if (key != currentTrackKey) {
            val track = Track(
                key = key,
                title = title,
                artist = artist,
                album = album,
                durationMs = duration,
                artwork = artwork,
                artworkUri = artworkUri
            )
            beginTrack(track)
        } else {
            if (cachedCover == null && !coverLoadInFlight &&
                SystemClock.elapsedRealtime() >= nextCoverRetryAtMs) {
                val refreshedTrack = Track(
                    key = key,
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = duration,
                    artwork = artwork,
                    artworkUri = artworkUri
                )
                maybeLoadCover(refreshedTrack)
            }
            emitCurrentLyric()
        }
    }

    private fun beginTrack(track: Track) {
        generation = nextGeneration(generation)
        val trackGeneration = generation
        currentTrackKey = track.key
        currentLines = emptyList()
        cachedCover = null
        coverLoadInFlight = false
        nextCoverRetryAtMs = 0L
        lastSentLyric = null
        emitLyric("")
        Log.i(TAG, "Media track changed: ${track.title} (${track.key.hashCode()})")
        startCoverLoad(track, trackGeneration)

        lyricsWorker.execute {
            val response = runCatching { fetchLyrics(track) }
                .onFailure { Log.w(TAG, "Unable to obtain lyrics for ${track.title}", it) }
                .getOrNull()
            val lines = LrcLyrics.parse(response?.synced, response?.plain, track.durationMs)
            handler.post {
                if (!running || generation != trackGeneration) return@post
                currentLines = lines
                lastSentLyric = null
                if (lines.isEmpty()) emitLyric("") else emitCurrentLyric()
            }
        }
    }

    private fun maybeLoadCover(track: Track) {
        if (cachedCover != null || coverLoadInFlight ||
            SystemClock.elapsedRealtime() < nextCoverRetryAtMs) {
            return
        }
        startCoverLoad(track, generation)
    }

    private fun startCoverLoad(track: Track, trackGeneration: Int) {
        if (coverLoadInFlight) return

        coverLoadInFlight = true
        coverWorker.execute {
            val cover = runCatching { buildCoverJpeg(track.artwork, track.artworkUri) }
                .onFailure { Log.w(TAG, "Unable to prepare media cover", it) }
                .getOrNull()
            handler.post {
                if (!running || generation != trackGeneration) return@post
                if (cover == null) {
                    coverLoadInFlight = false
                    nextCoverRetryAtMs = SystemClock.elapsedRealtime() + COVER_RETRY_DELAY_MS
                    Log.w(TAG, "No usable cover for ${track.title}; retrying later")
                    return@post
                }

                cachedCover = cover
                coverLoadInFlight = false
                nextCoverRetryAtMs = 0L
                Log.i(TAG, "Phone cover ready: ${cover.size} bytes")
                listener.onCoverAvailable(trackGeneration, cover)
            }
        }
    }

    private fun clearTrack(message: String) {
        generation = nextGeneration(generation)
        currentTrackKey = null
        currentController = null
        currentLines = emptyList()
        cachedCover = null
        coverLoadInFlight = false
        nextCoverRetryAtMs = 0L
        lastSentLyric = null
        emitLyric(message)
    }

    private fun emitCurrentLyric() {
        if (currentLines.isEmpty()) return
        emitLyric(LrcLyrics.lineAt(currentLines, playbackPositionMs(currentController)))
    }

    private fun emitLyric(text: String) {
        val normalized = text.trim()
        currentLyricText = normalized
        if (normalized == lastSentLyric || generation == 0) return
        lastSentLyric = normalized
        listener.onLyricChanged(generation, normalized)
    }

    private fun playbackPositionMs(controller: MediaController?): Long {
        val state = controller?.playbackState ?: return 0L
        var position = state.position.coerceAtLeast(0L)
        if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0L) {
            val elapsed = (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0L)
            position += (elapsed * state.playbackSpeed).toLong()
        }
        return position.coerceAtLeast(0L)
    }

    private fun controllerScore(controller: MediaController): Int {
        val hasTitle = !controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank()
        return when (controller.playbackState?.state) {
            PlaybackState.STATE_PLAYING -> 300
            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> 250
            PlaybackState.STATE_PAUSED -> 200
            else -> if (hasTitle) 100 else 0
        }
    }

    @Suppress("DEPRECATION")
    private fun readArtwork(metadata: MediaMetadata): Bitmap? =
        metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: metadata.description?.iconBitmap

    private fun readArtworkUri(metadata: MediaMetadata): String? =
        metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            ?: metadata.description?.iconUri?.toString()

    private fun artworkIdentity(bitmap: Bitmap?, artworkUri: String?): String {
        val bitmapIdentity = if (bitmap == null || bitmap.isRecycled ||
            bitmap.width <= 0 || bitmap.height <= 0) {
            ""
        } else {
            runCatching {
                var hash = 17
                for (y in 0..2) {
                    val sampleY = (bitmap.height - 1) * y / 2
                    for (x in 0..2) {
                        val sampleX = (bitmap.width - 1) * x / 2
                        hash = 31 * hash + bitmap.getPixel(sampleX, sampleY)
                    }
                }
                "${bitmap.width}x${bitmap.height}:${Integer.toHexString(hash)}"
            }.getOrElse {
                "${bitmap.width}x${bitmap.height}:g${bitmap.generationId}"
            }
        }
        return "${artworkUri.orEmpty()}|$bitmapIdentity"
    }

    private fun buildCoverJpeg(bitmap: Bitmap?, artworkUri: String?): ByteArray? {
        val decoded = if (bitmap == null) decodeArtworkUri(artworkUri) else null
        val source = bitmap ?: decoded ?: return null
        if (source.width <= 0 || source.height <= 0) return null

        val side = minOf(source.width, source.height)
        val sourceLeft = (source.width - side) / 2
        val sourceTop = (source.height - side) / 2
        val output = Bitmap.createBitmap(COVER_EDGE_PX, COVER_EDGE_PX, Bitmap.Config.RGB_565)
        Canvas(output).apply {
            drawColor(Color.BLACK)
            drawBitmap(
                source,
                Rect(sourceLeft, sourceTop, sourceLeft + side, sourceTop + side),
                Rect(0, 0, COVER_EDGE_PX, COVER_EDGE_PX),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            )
        }

        var quality = 80
        var jpeg: ByteArray
        do {
            jpeg = ByteArrayOutputStream().use { stream ->
                output.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                stream.toByteArray()
            }
            quality -= 8
        } while (jpeg.size > BleProtocol.COVER_MAX_BYTES && quality >= 32)

        output.recycle()
        decoded?.recycle()
        return jpeg.takeIf { it.size <= BleProtocol.COVER_MAX_BYTES }
    }

    private fun decodeArtworkUri(value: String?): Bitmap? {
        if (value.isNullOrBlank()) return null
        val uri = Uri.parse(value)
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                val connection = URL(value).openConnection() as HttpURLConnection
                connection.connectTimeout = HTTP_TIMEOUT_MS
                connection.readTimeout = HTTP_TIMEOUT_MS
                connection.instanceFollowRedirects = true
                try {
                    if (connection.responseCode !in 200..299) null
                    else connection.inputStream.use(BitmapFactory::decodeStream)
                } finally {
                    connection.disconnect()
                }
            }
            else -> appContext.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
        }
    }

    private fun fetchLyrics(track: Track): LyricsResponse? {
        val exactParameters = linkedMapOf(
            "track_name" to track.title,
            "artist_name" to track.artist,
            "album_name" to track.album,
            "duration" to (track.durationMs / 1_000L).toString()
        ).filterValues(String::isNotBlank)
        requestJsonObject("$LRCLIB_BASE/api/get?${encodeQuery(exactParameters)}")
            ?.let(::lyricsFromJson)?.let { return it }

        val searchParameters = linkedMapOf(
            "track_name" to track.title,
            "artist_name" to track.artist
        ).filterValues(String::isNotBlank)
        val results = requestJsonArray("$LRCLIB_BASE/api/search?${encodeQuery(searchParameters)}")
            ?: return null
        val candidates = (0 until results.length()).mapNotNull { results.optJSONObject(it) }
        return candidates.firstNotNullOfOrNull { lyricsFromJson(it)?.takeIf { item -> !item.synced.isNullOrBlank() } }
            ?: candidates.firstNotNullOfOrNull(::lyricsFromJson)
    }

    private fun lyricsFromJson(json: JSONObject): LyricsResponse? {
        val synced = json.optString("syncedLyrics").takeUnless { it.isBlank() || it == "null" }
        val plain = json.optString("plainLyrics").takeUnless { it.isBlank() || it == "null" }
        return if (synced == null && plain == null) null else LyricsResponse(synced, plain)
    }

    private fun requestJsonObject(url: String): JSONObject? = requestText(url)?.let(::JSONObject)

    private fun requestJsonArray(url: String): JSONArray? = requestText(url)?.let(::JSONArray)

    private fun requestText(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = HTTP_TIMEOUT_MS
        connection.readTimeout = HTTP_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "HSP-Watch/1.0")
        return try {
            when (connection.responseCode) {
                in 200..299 -> connection.inputStream.bufferedReader().use { it.readText() }
                404 -> null
                else -> throw IllegalStateException("LRCLIB HTTP ${connection.responseCode}")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun encodeQuery(parameters: Map<String, String>): String = parameters.entries.joinToString("&") {
        "${URLEncoder.encode(it.key, StandardCharsets.UTF_8.name())}=" +
            URLEncoder.encode(it.value, StandardCharsets.UTF_8.name())
    }

    private fun nextGeneration(value: Int): Int = if (value >= 0xffff) 1 else value + 1

    private companion object {
        const val TAG = "HspMediaSync"
        const val PLAYING_POLL_INTERVAL_MS = 500L
        const val PAUSED_POLL_INTERVAL_MS = 1_500L
        const val IDLE_POLL_INTERVAL_MS = 2_500L
        const val ACCESS_RETRY_INTERVAL_MS = 10_000L
        const val COVER_RETRY_DELAY_MS = 3_000L
        const val HTTP_TIMEOUT_MS = 8_000
        /* Match the watch widget exactly; LVGL's streamed JPG path cannot zoom reliably. */
        const val COVER_EDGE_PX = 128
        const val LRCLIB_BASE = "https://lrclib.net"
    }
}
