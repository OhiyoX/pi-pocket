package id.harness.pipocket

import java.io.ByteArrayOutputStream

/** SFTP reads are positional: advance by bytes actually returned, including short reads. */
internal fun readBoundedRemote(maxBytes: Int, read: (Long, ByteArray, Int) -> Int): ByteArray {
    require(maxBytes >= 0)
    val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(64 * 1024)
    var offset = 0L
    while (true) {
        // Read one byte beyond the limit to distinguish an exact fit from truncation.
        val requested = minOf(buffer.size.toLong(), maxBytes.toLong() - offset + 1).toInt()
        val count = read(offset, buffer, requested)
        if (count < 0) return out.toByteArray()
        check(count > 0) { "远端读取没有进展" }
        check(count <= requested) { "远端返回的文件数据长度无效" }
        offset += count
        check(offset <= maxBytes) { "文件超过 ${maxBytes / 1024 / 1024}MB，先在电脑上处理" }
        out.write(buffer, 0, count)
    }
}

/** Invalid/stale selections must requeue everything after clear_queue. */
internal data class QueueRemovalPlan(
    val selected: String?,
    val steering: List<String>,
    val followUp: List<String>,
)

internal fun planQueueRemoval(kind: String, index: Int, steering: List<String>, followUp: List<String>): QueueRemovalPlan {
    val target = when (kind) { "steer" -> steering; "followUp" -> followUp; else -> emptyList() }
    val selected = target.getOrNull(index)
    return QueueRemovalPlan(
        selected,
        if (selected != null && kind == "steer") steering.filterIndexed { i, _ -> i != index } else steering,
        if (selected != null && kind == "followUp") followUp.filterIndexed { i, _ -> i != index } else followUp,
    )
}

/** A canceled SSE source must never mutate state belonging to its replacement. */
internal class EventGeneration {
    private var value = 0L
    fun next(): Long = ++value
    fun isCurrent(generation: Long): Boolean = value == generation
}

internal data class QueueRecoveryPlan(val outboxText: String? = null, val composerText: String? = null)

internal fun planQueueRecovery(existing: PendingDraft?, file: String, text: String, composer: String): QueueRecoveryPlan =
    if (existing != null && existing.file != file) {
        QueueRecoveryPlan(composerText = Outbox.merge(composer, text))
    } else {
        QueueRecoveryPlan(outboxText = Outbox.merge(existing?.text.orEmpty(), text))
    }
