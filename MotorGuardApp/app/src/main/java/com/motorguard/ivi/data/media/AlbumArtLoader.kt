package com.motorguard.ivi.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Album artwork, cached in memory.
 *
 * Deliberately not Coil or Glide: this loads one image at a time, from `content://` URIs, at a
 * known size. An image-loading library would be a third dependency and a lot of machinery to
 * avoid about forty lines — the same reasoning that kept `data/nav` on `HttpURLConnection`.
 *
 * Two lookup paths, in order:
 *  1. `ContentResolver.loadThumbnail` on the *track* URI. The modern route (API 29+), and the
 *     one that works when the file has embedded art but MediaStore has no album-art row.
 *  2. Decoding the album-art collection URI. Older and deprecated, but still populated on many
 *     builds when path 1 finds nothing.
 */
object AlbumArtLoader {

    /** Bitmaps are large; cap by bytes rather than count. ~8 MB holds a good few covers. */
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Returns null when the track has no artwork — the UI shows the design's placeholder. */
    suspend fun load(context: Context, track: Track?, sizePx: Int): Bitmap? {
        if (track == null) return null
        val key = "${track.id}@$sizePx"
        cache.get(key)?.let { return it }

        return withContext(Dispatchers.IO) {
            val bitmap = loadThumbnail(context, track, sizePx) ?: decodeArtworkUri(context, track, sizePx)
            bitmap?.also { cache.put(key, it) }
        }
    }

    private fun loadThumbnail(context: Context, track: Track, sizePx: Int): Bitmap? {
        val uri = track.uri ?: return null
        return runCatching {
            context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
        }.getOrNull()
    }

    private fun decodeArtworkUri(context: Context, track: Track, sizePx: Int): Bitmap? {
        val uri = track.artworkUri ?: return null
        return runCatching {
            // Measure first so a 3000px cover is not decoded in full just to be scaled down.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, sizePx)
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }.getOrNull()
    }

    /** Largest power-of-two downscale that still covers [target]. */
    private fun sampleSizeFor(width: Int, height: Int, target: Int): Int {
        if (width <= 0 || height <= 0 || target <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= target && height / (sample * 2) >= target) sample *= 2
        return sample
    }

    /** Called when the source changes — stale covers are the visible symptom of a stale queue. */
    fun clear() = cache.evictAll()

    private const val CACHE_BYTES = 8 * 1024 * 1024
}
