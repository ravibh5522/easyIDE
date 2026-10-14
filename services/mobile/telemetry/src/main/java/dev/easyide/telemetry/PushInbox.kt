package dev.easyide.telemetry

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.edit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The last announcement not yet seen in the app. The push service writes it when one arrives, even
 * with no screen open; the app shows it once and clears it. One slot: a newer announcement replaces it.
 * Its image is downloaded once on arrival, so the notification and the dialog share one copy.
 */
class PushInbox(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val imageFile = File(context.filesDir, IMAGE_FILE)

    val pending: Flow<PushMessage?> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(read()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(read())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    /** Blocking: downloads the image. Call from a worker thread (the push service's callback is one). */
    fun put(message: PushMessage) {
        imageFile.delete()
        message.image?.let(::download)?.let(imageFile::writeBytes)
        prefs.edit {
            clear()
            message.toData().forEach { (k, v) -> putString(k, v) }
        }
    }

    /** The pending announcement's image, or null when it has none or its download failed. */
    fun image(): Bitmap? = if (imageFile.exists()) BitmapFactory.decodeFile(imageFile.path) else null

    fun clear() {
        prefs.edit { clear() }
        imageFile.delete()
    }

    private fun read(): PushMessage? =
        PushMessage.fromData(prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap())

    /** Boundary: the network. A missing or oversized image leaves the announcement without one. */
    private fun download(url: String): ByteArray? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) null
            else conn.inputStream.use { it.readNBytes(IMAGE_MAX_BYTES + 1) }.takeIf { it.size <= IMAGE_MAX_BYTES }
        } finally {
            conn.disconnect()
        }
    } catch (e: IOException) {
        null
    }

    private companion object {
        const val FILE = "push_inbox"
        const val IMAGE_FILE = "push_inbox_image"
        const val TIMEOUT_MS = 10_000
        const val IMAGE_MAX_BYTES = 1 shl 20
    }
}
