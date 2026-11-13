package com.inyogeshwar.codesathi.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Attached images for chat (Phase 6, Step 2).
 *
 * A picked image is NEVER sent as-is: a raw phone photo is several MB,
 * far too large to base64 into a request. Every image is downscaled so
 * its longest side is at most [MAX_DIMENSION_PX] and re-encoded as JPEG
 * at [JPEG_QUALITY] before anything is sent — and before it is stored.
 * The stored file is what the model gets, so what the user sees in the
 * thumbnail is what the model sees.
 *
 * Storage is app-private: `getFilesDir()/images/img_<uuid>.jpg`. Room
 * stores only the file NAMES ([Message.imagesJson]); the bytes never
 * live in the database, and they never go into a session backup
 * (see SessionBackup.kt) — a restored message keeps its text, not its
 * images. No new dependency: [BitmapFactory] / [Bitmap] + JPEG
 * compression only.
 */
object ImageStore {

    /** At most this many images on one message. */
    const val MAX_IMAGES_PER_MESSAGE = 3

    /**
     * Longest side of a stored image, in pixels. 1568 is Anthropic's
     * long-edge cap for Claude's vision encoder (older Claude models
     * resize to it) and comfortably inside OpenAI's high-detail range —
     * a photo downscaled to it is still readable as a screenshot or a
     * diagram, at a fraction of the tokens of the original.
     */
    const val MAX_DIMENSION_PX = 1568

    /** JPEG quality of the stored (and sent) image. */
    const val JPEG_QUALITY = 80

    private const val DIR_NAME = "images"

    /** File names this store ever writes: "img_<uuid>.jpg". Anything
     *  else is refused on read, so a corrupt Room value can never make
     *  the app open an arbitrary file. */
    private val FILE_NAME = Regex("""img_[0-9a-f\-]+\.jpg""")

    fun imagesDir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    /**
     * Read the image at [uri] (picked with the system image picker), downscale it to
     * at most [MAX_DIMENSION_PX] on its longest side, re-encode it as
     * JPEG ([JPEG_QUALITY]) and store it app-private. Returns the file
     * name, or null when the image could not be read or decoded (not a
     * picture, truncated file, out of memory) — the caller shows a plain
     * note and never crashes. Any format the decoder understands (JPEG,
     * PNG, WebP, GIF, HEIC on newer devices) becomes JPEG on disk, so
     * every provider gets the same `image/jpeg` bytes.
     */
    fun saveImage(context: Context, uri: Uri): String? {
        return try {
            // Bounds first: a phone photo can be 4000+ px, so decode a
            // subsampled copy instead of the full bitmap.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            } ?: return null
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) return null
            var sample = 1
            while (width / sample > MAX_DIMENSION_PX || height / sample > MAX_DIMENSION_PX) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null
            // inSampleSize only halves, so scale the rest of the way to
            // fit the cap exactly (a 3000 px photo at sample 2 is still
            // 1500 px — inside the cap; a 4000 px photo needs the extra
            // scale to reach 1568).
            val scale = minOf(
                1f,
                MAX_DIMENSION_PX / decoded.width.toFloat(),
                MAX_DIMENSION_PX / decoded.height.toFloat()
            )
            val bitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1),
                    true
                ).also { decoded.recycle() }
            } else {
                decoded
            }
            val name = "img_${UUID.randomUUID()}.jpg"
            val file = File(imagesDir(context), name)
            try {
                var written = false
                FileOutputStream(file).use { out ->
                    written = bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
                if (!written) {
                    file.delete()
                    return null
                }
                name
            } catch (_: Exception) {
                file.delete()
                null
            } finally {
                bitmap.recycle()
            }
        } catch (_: Exception) {
            // Unreadable / undecodable image: the caller shows a plain
            // note and never crashes.
            null
        } catch (_: OutOfMemoryError) {
            // A subsampled decode that still runs out of memory — same
            // plain note, never a crash.
            null
        }
    }

    /**
     * The stored JPEG bytes as a base64 string (no line breaks), ready
     * to go into a request body. Null when the file is missing or the
     * name is not one this store wrote.
     */
    fun loadBase64(context: Context, fileName: String): String? {
        if (!FILE_NAME.matches(fileName)) return null
        val file = File(imagesDir(context), fileName)
        if (!file.isFile) return null
        return try {
            Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * A small bitmap for a chat thumbnail (longest side ~[maxPx]).
     * Null when the file is missing or undecodable — the caller shows
     * nothing instead of a broken image.
     */
    fun loadThumbnailBitmap(context: Context, fileName: String, maxPx: Int = 256): Bitmap? {
        if (!FILE_NAME.matches(fileName)) return null
        val file = File(imagesDir(context), fileName)
        if (!file.isFile) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > maxPx || bounds.outHeight / sample > maxPx) {
                sample *= 2
            }
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    /** Delete one stored image; missing files and bad names are no-ops. */
    fun delete(context: Context, fileName: String) {
        if (!FILE_NAME.matches(fileName)) return
        try {
            File(imagesDir(context), fileName).delete()
        } catch (_: Exception) {
            // Best effort — a leftover app-private file is harmless.
        }
    }

    /** Delete several stored images (session delete, staged-image discard). */
    fun deleteAll(context: Context, fileNames: List<String>) {
        fileNames.forEach { delete(context, it) }
    }
}

private val imageNamesJson = Json { ignoreUnknownKeys = true }

/**
 * Encode attached-image file names for [com.inyogeshwar.codesathi.data.Message.imagesJson]:
 * a JSON array of file names, e.g. `["img_….jpg"]`. Only file names —
 * never bytes, never a content URI — are stored in Room.
 */
fun encodeImageNames(fileNames: List<String>): String =
    imageNamesJson.encodeToString(ListSerializer(String.serializer()), fileNames)

/** Decode [com.inyogeshwar.codesathi.data.Message.imagesJson]; corrupt or
 *  missing values mean "no images", never a crash. */
fun decodeImageNames(imagesJson: String?): List<String> {
    if (imagesJson.isNullOrBlank()) return emptyList()
    return try {
        imageNamesJson.decodeFromString(ListSerializer(String.serializer()), imagesJson)
            .filter { it.isNotBlank() }
    } catch (_: Exception) {
        emptyList()
    }
}
