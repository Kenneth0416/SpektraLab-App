package app.spektralab.gl

/** Bounds display-space diffusion buffers independently of the film processing size. */
object GroundGlassSizing {
    const val MAX_LONG_EDGE = 1280

    data class Size(val width: Int, val height: Int)

    /**
     * Keep small outputs unchanged; otherwise fix the long edge at [MAX_LONG_EDGE] and
     * round the other edge to its nearest positive pixel. Neither edge is ever upscaled.
     */
    fun sourceSize(outputWidth: Int, outputHeight: Int): Size {
        require(outputWidth > 0 && outputHeight > 0) { "Output dimensions must be positive" }
        val longEdge = maxOf(outputWidth, outputHeight)
        if (longEdge <= MAX_LONG_EDGE) return Size(outputWidth, outputHeight)

        // Integer rounding avoids float drift; Long also keeps large inputs from overflowing.
        fun scaled(edge: Int): Int =
            ((edge.toLong() * MAX_LONG_EDGE + longEdge / 2) / longEdge).toInt().coerceIn(1, edge)
        return Size(scaled(outputWidth), scaled(outputHeight))
    }
}
