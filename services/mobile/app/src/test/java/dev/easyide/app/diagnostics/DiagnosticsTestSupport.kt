package dev.easyide.app.diagnostics

import java.io.File
import java.nio.file.Files

/** A sink that remembers what it was given. */
class ListSink : LogSink {
    data class Entry(val level: LogLevel, val source: LogSource, val message: String)

    private val store = mutableListOf<Entry>()
    val entries: List<Entry> get() = synchronized(store) { store.toList() }

    override fun log(level: LogLevel, source: LogSource, message: String) {
        synchronized(store) { store += Entry(level, source, message) }
    }
}

fun logLine(index: Int, level: LogLevel = LogLevel.INFO, source: LogSource = LogSource.APP): LogLine =
    LogLine(atMs = index.toLong(), level = level, source = source, message = "m%03d".format(index))

fun testBuildInfo(versionName: String = "1.2.3-debug", abis: List<String> = listOf("x86_64")): BuildInfo =
    BuildInfo(
        versionName = versionName,
        versionCode = 42,
        manufacturer = "Acme",
        model = "Tab 9",
        sdkInt = 34,
        abis = abis,
    )

/** Writes [bytes] bytes of filler to [file], creating parent directories. */
fun writeBytes(file: File, bytes: Int): File {
    Files.createDirectories(file.parentFile.toPath())
    file.writeBytes(ByteArray(bytes) { 'x'.code.toByte() })
    return file
}
