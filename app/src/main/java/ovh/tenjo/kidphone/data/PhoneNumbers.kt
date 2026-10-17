package ovh.tenjo.kidphone.data

/**
 * Decides whether two phone numbers are the same line, whatever way each was written.
 *
 * Numbers arrive in different shapes: the network sends "+353871234567", a parent types
 * "087 123 4567". Both are reduced to their digits without the leading 0 or 00
 * ("871234567" and "353871234567"); they match when one ends with the other. Numbers shorter
 * than seven digits (short codes) must match exactly.
 */
object PhoneNumbers {
    private const val MIN_SUFFIX_MATCH = 7

    fun digits(raw: String?): String = raw?.filter { it.isDigit() } ?: ""

    private fun core(raw: String?): String {
        val d = digits(raw)
        return when {
            d.startsWith("00") -> d.drop(2)
            d.startsWith("0") -> d.drop(1)
            else -> d
        }
    }

    fun same(a: String?, b: String?): Boolean {
        val ca = core(a)
        val cb = core(b)
        if (ca.isEmpty() || cb.isEmpty()) return false
        if (ca == cb) return true
        if (minOf(ca.length, cb.length) < MIN_SUFFIX_MATCH) return false
        return ca.endsWith(cb) || cb.endsWith(ca)
    }
}
