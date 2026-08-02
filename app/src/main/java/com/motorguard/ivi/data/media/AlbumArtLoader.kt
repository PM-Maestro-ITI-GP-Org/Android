package com.motorguard.ivi.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Collections

/**
 * Album artwork, with four lookups in order of cost: memory, embedded, disk, network.
 *
 * Deliberately not Coil or Glide: this loads one image at a time, at a known size, and needs a
 * bespoke network fallback anyway — the same reasoning that kept `data/nav` on
 * `HttpURLConnection`.
 *
 * 1. **Memory** — LRU, capped by bytes.
 * 2. **`loadThumbnail`** on the track URI. The modern route (API 29+), and the one that works
 *    when the file has embedded art but MediaStore has no album-art row.
 * 3. **Album-art collection URI.** Older and deprecated, still populated on many builds.
 * 4. **Disk, then [ArtworkProvider]** — keyed by artist+album, because a downloaded cover serves
 *    every track on the record and must survive a restart.
 *
 * Bitmaps are decoded into software config on purpose. `loadThumbnail` returns a
 * `Config.HARDWARE` bitmap on most devices, and `Palette` cannot read pixels from one — the
 * album theme would silently never work, which is exactly the kind of bug that looks like "the
 * feature just doesn't do anything".
 */
object AlbumArtLoader {

    /** Bitmaps are large; cap by bytes rather than count. ~8 MB holds a good few covers. */
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Albums already looked up online and found to have nothing. Stops repeat requests. */
    private val misses = Collections.synchronizedSet(mutableSetOf<String>())

    /** One network lookup at a time — a 541-track library would otherwise fan out badly. */
    private val networkLock = Mutex()

    /** Returns null when the track has no findable artwork — the UI shows the placeholder. */
    suspend fun load(context: Context, track: Track?, sizePx: Int): Bitmap? {
        if (track == null) return null
        val key = "${track.id}@$sizePx"
        cache.get(key)?.let { return it }

        return withContext(Dispatchers.IO) {
            val bitmap = loadThumbnail(context, track, sizePx)
                ?: decodeUri(context, track.artworkUri, sizePx)
                ?: loadRemote(context, track, sizePx)
            bitmap?.also { cache.put(key, it) }
        }
    }

    private fun loadThumbnail(context: Context, track: Track, sizePx: Int): Bitmap? {
        val uri = track.uri ?: return null
        return runCatching {
            context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
        }.getOrNull()?.toSoftware()
    }

    private fun decodeUri(context: Context, uri: android.net.Uri?, sizePx: Int): Bitmap? {
        if (uri == null) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, sizePx)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }.getOrNull()
    }

    // ---------------------------------------------------------------- disk + network

    /**
     * Disk cache, then the network. Keyed by artist+album rather than by track, so one lookup
     * covers a whole record and a re-scan of the same library costs nothing.
     */
    private suspend fun loadRemote(context: Context, track: Track, sizePx: Int): Bitmap? {
        if (!MediaConfig.onlineArtwork) return null

        val albumKey = albumKey(track)
        if (albumKey.isBlank()) return null

        val file = File(artworkDir(context), "${albumKey.sha1()}.img")
        if (file.exists()) {
            decodeFile(file, sizePx)?.let { return it }
            // A truncated or corrupt file is worse than none — drop it and try again.
            file.delete()
        }
        if (albumKey in misses) return null

        return networkLock.withLock {
            // Re-check inside the lock: several tracks from the same album can queue up here.
            if (file.exists()) return@withLock decodeFile(file, sizePx)
            if (albumKey in misses) return@withLock null

            val bytes = runCatching {
                MediaConfig.artworkProvider.findArtwork(track.artist, track.album, track.title)
            }.getOrNull()

            if (bytes == null || bytes.isEmpty()) {
                misses.add(albumKey)
                return@withLock null
            }
            runCatching { file.writeBytes(bytes) }
            decodeFile(file, sizePx) ?: BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    private fun decodeFile(file: File, sizePx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, sizePx)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(file.path, options)
    }.getOrNull()

    private fun artworkDir(context: Context): File =
        File(context.cacheDir, "album-art").apply { mkdirs() }

    /** Artist + album identifies a cover; the track title does not. */
    private fun albumKey(track: Track): String =
        listOf(track.artist, track.album)
            .filter { it.isNotBlank() }
            .joinToString("|")
            .lowercase()

    private fun String.sha1(): String =
        MessageDigest.getInstance("SHA-1").digest(toByteArray())
            .joinToString("") { "%02x".format(it) }

    // ---------------------------------------------------------------- helpers

    /**
     * Palette needs `getPixels`, which a `Config.HARDWARE` bitmap does not support. Copying to
     * ARGB_8888 costs one allocation per cover and is what makes the album theme work at all.
     */
    private fun Bitmap.toSoftware(): Bitmap =
        if (config == Bitmap.Config.HARDWARE) {
            copy(Bitmap.Config.ARGB_8888, false) ?: this
        } else {
            this
        }

    /** Largest power-of-two downscale that still covers [target]. */
    private fun sampleSizeFor(width: Int, height: Int, target: Int): Int {
        if (width <= 0 || height <= 0 || target <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= target && height / (sample * 2) >= target) sample *= 2
        return sample
    }

    /** Called when the source changes — stale covers are the visible symptom of a stale queue. */
    fun clear() {
        cache.evictAll()
        misses.clear()
    }

    private const val CACHE_BYTES = 8 * 1024 * 1024
}
