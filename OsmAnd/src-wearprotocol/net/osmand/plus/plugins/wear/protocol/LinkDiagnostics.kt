package net.osmand.plus.plugins.wear.protocol

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Optional on-device log of watch-link events, used by both apps to diagnose field problems after
 * the fact. Off by default; the phone plugin setting turns it on for both devices.
 *
 * Callers pass event names, states, status codes and error descriptions from [describe]: never
 * positions, map content, keys, identities, addresses or pairing codes. Lines carry the local
 * wall-clock time with its UTC offset, so phone and watch lines can be matched. Routine successes
 * are counted and written as one summary line per minute, and [heartbeat] adds one state line per
 * minute while it has something to report. An event repeated within a minute is counted instead
 * of written again. Two files of at most [maxFileBytes] each are kept; when
 * the current file fills, it replaces the previous one.
 *
 * While logging is off, the last [MEMORY_LINES] events are kept in memory only. Turning logging
 * on writes them first, so the events that led up to switching it on (for example the watch's
 * first connection, before the phone's setting reached it) are not lost.
 */
class LinkDiagnostics(
    private val directory: File,
    private val fileName: String,
    /** Build and device facts written when logging starts; no personal data. */
    private val header: () -> String,
    private val maxFileBytes: Long = 1_048_576L,
    /** One state line per minute while logging is on; null when there is nothing to report. */
    private val heartbeat: () -> String? = { null }
) {
    @Volatile var enabled = false
        private set
    private val writer = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "LinkDiagnostics").apply { isDaemon = true }
    }
    /** Lines waiting for the writer; beyond [MAX_QUEUED] they are dropped and counted. */
    private val queued = AtomicInteger()
    private val dropped = AtomicInteger()
    /** Writer thread only. */
    private val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
    /** Writer thread only. */
    private val counts = linkedMapOf<String, Int>()
    /** When each recent event text was last written; writer thread only. */
    private val lastWritten = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 256
    }
    /** Recent events while logging is off; guarded by itself. */
    private val memory = ArrayDeque<Triple<Long, String, String>>()

    init {
        writer.scheduleWithFixedDelay({
            val time = System.currentTimeMillis()
            flushCounts(time)
            if (enabled) runCatching(heartbeat).getOrNull()?.let { append(time, "heartbeat", it) }
        }, SUMMARY_MS, SUMMARY_MS, TimeUnit.MILLISECONDS)
    }

    val current: File get() = File(directory, "$fileName.log")
    val previous: File get() = File(directory, "$fileName.1.log")

    /** Starts or stops logging. The change itself is logged, so the files show when it was on. */
    fun setEnabled(value: Boolean, reason: String) {
        if (enabled == value) return
        if (value) {
            val earlier = synchronized(memory) { memory.toList().also { memory.clear() } }
            enabled = true
            submit {
                if (earlier.isNotEmpty()) {
                    append(System.currentTimeMillis(), "memory", "${earlier.size} earlier events kept in memory while logging was off:")
                    earlier.forEach { (time, thread, event) -> append(time, thread, event) }
                }
            }
            log("diagnostics on ($reason); ${runCatching(header).getOrDefault("header unavailable")}")
        } else {
            log("diagnostics off ($reason)")
            enabled = false
        }
    }

    /** Writes one event line, or keeps it in memory while logging is off. Safe from any thread. */
    fun log(event: String) {
        val time = System.currentTimeMillis()
        val thread = Thread.currentThread().name
        if (!enabled) {
            synchronized(memory) {
                if (memory.size >= MEMORY_LINES) memory.removeFirst()
                memory.addLast(Triple(time, thread, clean(event)))
            }
            return
        }
        submit {
            // The same event within a minute is counted, not written, so a retry loop cannot push
            // older history out of the file; it still appears at least once a minute.
            val previous = lastWritten[event]
            if (previous != null && time - previous < REPEAT_MS) {
                counts["repeated: " + clean(event).take(120)] = (counts["repeated: " + clean(event).take(120)] ?: 0) + 1
            } else {
                lastWritten[event] = time
                flushCounts(time)
                append(time, thread, event)
            }
        }
    }

    /** Counts a routine event; the counts are written as one summary line per minute. */
    fun count(event: String) {
        if (!enabled) return
        submit { counts[event] = (counts[event] ?: 0) + 1 }
    }

    private fun submit(task: () -> Unit) {
        if (queued.incrementAndGet() > MAX_QUEUED) {
            queued.decrementAndGet()
            dropped.incrementAndGet()
            return
        }
        try {
            writer.execute { queued.decrementAndGet(); task() }
        } catch (_: RejectedExecutionException) {
            queued.decrementAndGet()
        }
    }

    private fun flushCounts(time: Long) {
        val lost = dropped.getAndSet(0)
        if (lost > 0) append(time, "summary", "$lost lines dropped: writer queue full")
        if (counts.isEmpty()) return
        val summary = counts.entries.joinToString(", ") { "${it.key}=${it.value}" }
        counts.clear()
        append(time, "summary", "counts since last line: $summary")
    }

    private fun append(time: Long, thread: String, event: String) {
        try {
            if (!directory.isDirectory && !directory.mkdirs()) return
            val line = "${format.format(Date(time))} [$thread] ${clean(event)}\n".toByteArray(Charsets.UTF_8)
            val file = current
            if (file.length() + line.size > maxFileBytes) {
                previous.delete()
                file.renameTo(previous)
            }
            file.appendBytes(line)
        } catch (_: Exception) {
            // Diagnostics must never affect the link; a failed write drops the line.
        }
    }

    companion object {
        private const val SUMMARY_MS = 60_000L
        private const val REPEAT_MS = 60_000L
        private const val MAX_EVENT_CHARS = 1_200
        private const val MAX_MESSAGE_CHARS = 400
        internal const val MAX_QUEUED = 1_000
        internal const val MEMORY_LINES = 200
        private val ADDRESS = Regex("(?i)\\b[0-9a-f]{2}([:-][0-9a-f]{2}){5}\\b")
        private val LONG_HEX = Regex("(?i)\\b[0-9a-f]{16,}\\b")
        private val COORDINATES = Regex("-?\\d{1,3}\\.\\d{4,}")

        private fun clean(event: String): String =
            event.map { if (it.code < 0x20 || it.code == 0x7f) ' ' else it }.joinToString("").take(MAX_EVENT_CHARS)

        /** System-provided text for the log, with addresses, long hex values and coordinates replaced. */
        fun scrub(text: String): String =
            COORDINATES.replace(LONG_HEX.replace(ADDRESS.replace(text, "<address>"), "<hex>"), "<number>").take(MAX_MESSAGE_CHARS)

        /**
         * An error for the log: its class and message, then its first cause. Bluetooth addresses,
         * long hexadecimal values (keys, fingerprints) and decimal coordinates are replaced, and
         * each message is cut short.
         */
        fun describe(error: Throwable?): String {
            if (error == null) return "no error"
            fun one(item: Throwable): String {
                val message = item.message?.let(::scrub)
                return if (message.isNullOrBlank()) item.javaClass.simpleName else "${item.javaClass.simpleName}: $message"
            }
            val cause = error.cause?.takeIf { it !== error }
            return if (cause == null) one(error) else "${one(error)} (cause ${one(cause)})"
        }
    }
}
