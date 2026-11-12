package app.spektralab.raw

/*
 * From LibRaw 0.22.2 (LGPL 2.1 / CDDL; derived from dcraw.c by Dave Coffin, used here under
 * GPLv3): the `canon[][11]` table of identify() (src/metadata/identify.cpp).
 */

/** Canon raw frame sizes with fixed margins, shared by the CR2 and CR3 readers. */
internal object CanonSizes {
    /**
     * LibRaw identify() `canon[][11]`: raw_width, raw_height, left_margin, top_margin,
     * width_decrement, height_decrement, mask01, mask03, mask11, mask13 (optical-black area
     * adjustments), dcraw CFA filters byte (0 = the format's own). Every row is padded to 11.
     */
    private val TABLE: Array<IntArray> = arrayOf(
        intArrayOf(1944, 1416, 0, 0, 48, 0),
        intArrayOf(2144, 1560, 4, 8, 52, 2, 0, 0, 0, 25),
        intArrayOf(2224, 1456, 48, 6, 0, 2),
        intArrayOf(2376, 1728, 12, 6, 52, 2),
        intArrayOf(2672, 1968, 12, 6, 44, 2),
        intArrayOf(3152, 2068, 64, 12, 0, 0, 16),
        intArrayOf(3160, 2344, 44, 12, 4, 4),
        intArrayOf(3344, 2484, 4, 6, 52, 6),
        intArrayOf(3516, 2328, 42, 14, 0, 0),
        intArrayOf(3596, 2360, 74, 12, 0, 0),
        intArrayOf(3744, 2784, 52, 12, 8, 12),
        intArrayOf(3944, 2622, 30, 18, 6, 2),
        intArrayOf(3948, 2622, 42, 18, 0, 2),
        intArrayOf(3984, 2622, 76, 20, 0, 2, 14),
        intArrayOf(4032, 2656, 112, 44, 10, 0),
        intArrayOf(4104, 3048, 48, 12, 24, 12),
        intArrayOf(4116, 2178, 4, 2, 0, 0),
        intArrayOf(4152, 2772, 192, 12, 0, 0),
        intArrayOf(4160, 3124, 104, 11, 8, 65),
        intArrayOf(4176, 3062, 96, 17, 8, 0, 0, 16, 0, 7, 0x49),
        intArrayOf(4192, 3062, 96, 17, 24, 0, 0, 16, 0, 0, 0x49),
        intArrayOf(4312, 2876, 22, 18, 0, 2),
        intArrayOf(4352, 2850, 144, 46, 0, 0),
        intArrayOf(4352, 2874, 62, 18, 0, 0),
        intArrayOf(4476, 2954, 90, 34, 0, 0),
        intArrayOf(4480, 3348, 12, 10, 36, 12, 0, 0, 0, 18, 0x49),
        intArrayOf(4480, 3366, 80, 50, 0, 0),
        intArrayOf(4496, 3366, 80, 50, 12, 0),
        intArrayOf(4768, 3516, 96, 16, 0, 0, 0, 16),
        intArrayOf(4832, 3204, 62, 26, 0, 0),
        intArrayOf(4832, 3228, 62, 51, 0, 0),
        intArrayOf(5108, 3349, 98, 13, 0, 0),
        intArrayOf(5120, 3318, 142, 45, 62, 0),
        intArrayOf(5280, 3528, 72, 52, 0, 0),
        intArrayOf(5344, 3516, 142, 51, 0, 0),
        intArrayOf(5344, 3584, 126, 100, 0, 2),
        intArrayOf(5344, 3950, 98, 18, 0, 0, 0, 24, 0, 0),
        intArrayOf(5360, 3516, 158, 51, 0, 0),
        intArrayOf(5568, 3708, 72, 38, 0, 0),
        intArrayOf(5632, 3710, 96, 17, 0, 0, 0, 16, 0, 0, 0x49),
        intArrayOf(5712, 3774, 62, 20, 10, 2),
        intArrayOf(5792, 3804, 158, 51, 0, 0),
        intArrayOf(5920, 3950, 122, 80, 2, 0),
        intArrayOf(6096, 4051, 76, 35, 0, 0),
        intArrayOf(6096, 4056, 72, 34, 0, 0),
        intArrayOf(6288, 4056, 264, 36, 0, 0),
        intArrayOf(6384, 4224, 120, 44, 0, 0),
        intArrayOf(6880, 4544, 136, 42, 0, 0),
        intArrayOf(6888, 4546, 146, 48, 0, 0),
        intArrayOf(7128, 4732, 144, 72, 0, 0),
        intArrayOf(8896, 5920, 160, 64, 0, 0),
        intArrayOf(6192, 4152, 160, 120, 0, 0),
        intArrayOf(6192, 4060, 168, 52, 24, 8, 16, 48, 32, 0),
        intArrayOf(6188, 4120, 154, 96, 12, 0, 16, 48, 32, 0),
        intArrayOf(8480, 5650, 258, 162, 0, 0, 128, 2, 128, 0),
        intArrayOf(5376, 3574, 258, 162, 0, 0, 128, 2, 128, 0),
        intArrayOf(6160, 4144, 128, 120, 0, 0),
    ).map { it.copyOf(11) }.toTypedArray()

    /** The row for a raw frame size (LibRaw applies every matching row; the sizes are unique), or null. */
    fun find(rawWidth: Int, rawHeight: Int): IntArray? = TABLE.lastOrNull { it[0] == rawWidth && it[1] == rawHeight }
}
