package app.spektralab.camera

/** Allow for rounding differences between advertised zoom ratios and sensor-derived ratios. */
internal fun nearLensRatio(a: Float, b: Float): Boolean =
    a > 0f && b > 0f && maxOf(a, b) / minOf(a, b) < 1.1f

/** Independent cameras need a rebind unless the current logical camera already reaches that lens. */
internal fun separateLensRatios(
    currentId: String,
    baseMm: Float,
    physical: List<Float>,
    min: Float,
    max: Float,
    available: List<Pair<String, Float>>,
): List<Pair<String, Float>> {
    if (!baseMm.isFinite() || baseMm <= 0f) return emptyList()
    val reachable = physical.filter { it in min..max } + 1f
    return available.mapNotNull { (id, mm) ->
        val ratio = mm / baseMm
        if (id == currentId || !ratio.isFinite() || ratio <= 0f || reachable.any { nearLensRatio(it, ratio) }) null
        else id to ratio
    }.sortedBy { it.second }.fold(emptyList()) { acc, choice ->
        if (acc.any { nearLensRatio(it.second, choice.second) }) acc else acc + choice
    }
}

/** Always retain a supported wide end, even when physical metadata only lists main and tele. */
internal fun lensZoomStops(min: Float, max: Float, physical: List<Float>): List<Float> {
    if (!min.isFinite() || !max.isFinite() || min <= 0f || max < min) return listOf(1f)
    val real = physical.filter { it.isFinite() && it in min..max }
        .sorted().fold(emptyList<Float>()) { acc, z ->
            if (acc.any { nearLensRatio(it, z) }) acc else acc + z
        }
    val candidates = buildList {
        if (min < 0.95f) add(min)
        add(1f.coerceIn(min, max))
        addAll(real)
        if (real.size < 2) {
            if (2f in min..max) add(2f)
            if (5f in min..max) add(5f)
        }
    }
    return candidates.fold(emptyList<Float>()) { acc, z ->
        if (acc.any { nearLensRatio(it, z) }) acc else acc + z
    }.sorted()
}
