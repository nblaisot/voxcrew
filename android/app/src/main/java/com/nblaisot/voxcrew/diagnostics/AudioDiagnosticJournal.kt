package com.nblaisot.voxcrew.diagnostics

import android.content.Context
import android.os.Build
import com.nblaisot.voxcrew.BuildConfig
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Small app-owned diagnostic journal that survives logcat rotation.
 *
 * Events contain timings, counters and route types only: never PCM, Opus payloads,
 * display names, network addresses or stable peer identifiers.
 */
class AudioDiagnosticJournal(
    private val directory: File,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val retainedFiles: Int = DEFAULT_RETAINED_FILES,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000L },
) : Closeable {
    private sealed interface Command {
        data class Line(val value: String) : Command
        data class Flush(val latch: CountDownLatch) : Command
        data object Stop : Command
    }

    private val queue = ArrayBlockingQueue<Command>(QUEUE_CAPACITY)
    private val droppedEvents = AtomicLong(0L)
    private val worker = Thread(::writerLoop, "VoxCrewAudioDiagnostics").apply {
        isDaemon = true
        start()
    }

    fun record(component: String, event: String, fields: Map<String, Any?> = emptyMap()) {
        val line = buildJsonLine(component, event, fields)
        if (!queue.offer(Command.Line(line))) droppedEvents.incrementAndGet()
    }

    /** Used by tests and immediately before pulling diagnostics with adb. */
    fun flush(timeoutMs: Long = 2_000L): Boolean {
        val latch = CountDownLatch(1)
        if (!queue.offer(Command.Flush(latch))) return false
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        queue.offer(Command.Stop)
        runCatching { worker.join(2_000L) }
    }

    fun files(): List<File> = directory.listFiles()
        ?.filter { it.name.startsWith(FILE_PREFIX) && it.extension == "jsonl" }
        ?.sortedBy { it.name }
        .orEmpty()

    private fun writerLoop() {
        directory.mkdirs()
        var active = File(directory, "$FILE_PREFIX-0.jsonl")
        while (true) {
            when (val command = queue.take()) {
                is Command.Line -> {
                    val dropped = droppedEvents.getAndSet(0L)
                    if (dropped > 0L) {
                        val overflow = buildJsonLine(
                            component = "journal",
                            event = "queue_overflow",
                            fields = mapOf("droppedEvents" to dropped),
                        )
                        active = appendRotating(active, overflow)
                    }
                    active = appendRotating(active, command.value)
                }
                is Command.Flush -> command.latch.countDown()
                Command.Stop -> return
            }
        }
    }

    private fun appendRotating(active: File, line: String): File {
        val bytes = line.toByteArray(Charsets.UTF_8).size + 1L
        var destination = active
        if (destination.exists() && destination.length() + bytes > maxFileBytes) {
            rotateFiles()
            destination = File(directory, "$FILE_PREFIX-0.jsonl")
        }
        destination.appendText(line + "\n", Charsets.UTF_8)
        return destination
    }

    private fun rotateFiles() {
        File(directory, "$FILE_PREFIX-${retainedFiles - 1}.jsonl").delete()
        for (index in retainedFiles - 2 downTo 0) {
            val source = File(directory, "$FILE_PREFIX-$index.jsonl")
            if (source.exists()) source.renameTo(File(directory, "$FILE_PREFIX-${index + 1}.jsonl"))
        }
    }

    private fun buildJsonLine(component: String, event: String, fields: Map<String, Any?>): String {
        val values = linkedMapOf<String, Any?>(
            "wallTime" to Instant.ofEpochMilli(wallClockMs()).toString(),
            "monotonicMs" to monotonicMs(),
            "component" to component,
            "event" to event,
        )
        fields.forEach { (key, value) -> values[sanitizeKey(key)] = sanitizeValue(value) }
        return values.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"${escape(key)}\":${jsonValue(value)}"
        }
    }

    private fun sanitizeKey(value: String): String = value.filter { it.isLetterOrDigit() || it == '_' }.take(48)

    private fun sanitizeValue(value: Any?): Any? = when (value) {
        null, is Number, is Boolean -> value
        else -> value.toString().replace('\n', ' ').take(MAX_VALUE_LENGTH)
    }

    private fun jsonValue(value: Any?): String = when (value) {
        null -> "null"
        is Number, is Boolean -> value.toString()
        else -> "\"${escape(value.toString())}\""
    }

    private fun escape(value: String): String = buildString(value.length + 8) {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\r' -> append("\\r")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                else -> if (char.code >= 0x20) append(char)
            }
        }
    }

    companion object {
        const val DIRECTORY_NAME = "audio-diagnostics"
        private const val FILE_PREFIX = "audio-events"
        private const val DEFAULT_MAX_FILE_BYTES = 1_000_000L
        private const val DEFAULT_RETAINED_FILES = 8
        private const val QUEUE_CAPACITY = 4_096
        private const val MAX_VALUE_LENGTH = 256
    }
}

object AudioDiagnostics {
    @Volatile private var journal: AudioDiagnosticJournal? = null

    fun initialize(context: Context) {
        if (journal != null) return
        synchronized(this) {
            if (journal != null) return
            val created = AudioDiagnosticJournal(File(context.noBackupFilesDir, AudioDiagnosticJournal.DIRECTORY_NAME))
            journal = created
            created.record(
                "app",
                "started",
                mapOf(
                    "versionName" to BuildConfig.VERSION_NAME,
                    "versionCode" to BuildConfig.VERSION_CODE,
                    "manufacturer" to Build.MANUFACTURER,
                    "model" to Build.MODEL,
                    "sdk" to Build.VERSION.SDK_INT,
                    "fingerprint" to Build.FINGERPRINT,
                ),
            )
        }
    }

    fun event(component: String, event: String, vararg fields: Pair<String, Any?>) {
        journal?.record(component, event, linkedMapOf(*fields))
    }

    fun peerToken(peerUid: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(peerUid.toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }

    fun flush(): Boolean = journal?.flush() ?: false
}
