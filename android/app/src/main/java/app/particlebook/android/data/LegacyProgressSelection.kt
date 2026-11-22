package app.particlebook.android.data

import app.particlebook.android.reader.ReaderLocator

/** Duplicate legacy aliases choose a complete position; mixing a raw locator with another page corrupts it. */
internal fun selectLegacyProgress(previous: LegacyProgress, candidate: LegacyProgress): LegacyProgress {
    val previousTime = previous.lastReadAt?.takeIf { it > 0 } ?: 0L
    val candidateTime = candidate.lastReadAt?.takeIf { it > 0 } ?: 0L
    if (previousTime != candidateTime) return if (candidateTime > previousTime) candidate else previous
    fun rawQuality(item: LegacyProgress): Int = when {
        item.locatorRaw == null -> 0
        ReaderLocator.decode(item.locatorRaw) == null -> 1
        else -> 2
    }
    return if (rawQuality(candidate) >= rawQuality(previous)) candidate else previous
}
