package com.example.africellcontactstask

/**
 * Implements the Contact Updater conversion rules (Gambia national numbering plan,
 * Africell / QCell / Comium, 7-digit -> 9-digit, effective 4 September 2026).
 *
 * Sorts every contact number into exactly one of three buckets:
 *
 *   CHANGEABLE   - old 7-digit number in a known carrier block -> a fix can be computed
 *                  automatically. Always written back as +220 + the new 9-digit number,
 *                  regardless of what style the original was in.
 *   ERROR        - looks like it was meant to be a Gambia number (carries a +220/00220/220
 *                  marker, or is a 9-digit number starting with a recognized operator prefix)
 *                  but can't be safely classified: wrong digit count, non-numeric junk, or a
 *                  9-digit number whose prefix and remaining digits belong to different
 *                  operators (or no operator at all). Never changed silently - flagged for the
 *                  user to fix manually or keep as is.
 *   UNCHANGEABLE - already correct, a valid Gambia number just outside the affected blocks, an
 *                  international number with a different country code, or has no Gambia signal
 *                  at all (no marker, and the wrong length for a local Gambia number) - left
 *                  alone without being flagged.
 *
 * Recognizes four input styles, each tolerant of spaces/dashes/dots/brackets:
 *   +220 7011234        (PLUS)
 *   00220 7011234        (ZERO_ZERO)
 *   220 7011234          (BARE_CC - country code with no + and no 00, 10 or 12 digits total)
 *   7011234              (LOCAL - no country code at all)
 */
object PhoneValidator {

    const val COUNTRY_CODE = "220"

    /**
     * One carrier's OLD-number allocation blocks:
     *  - oldSingleDigits: leading digits where the WHOLE decade belongs to this carrier (e.g. 7 -> 700 0000..799 9999)
     *  - oldTwoDigitRanges: specific 2-digit-leading blocks within a decade (e.g. 40..41 -> 400 0000..419 9999)
     */
    data class Carrier(
        val name: String,
        val newPrefix: String,
        val oldSingleDigits: Set<Int>,
        val oldTwoDigitRanges: List<IntRange>
    )

    // Complete table per the Contact Updater requirements doc.
    val CARRIERS = listOf(
        Carrier("Africell", "87", oldSingleDigits = setOf(7, 2), oldTwoDigitRanges = listOf(40..41, 45..45)),
        Carrier("Qcell", "83", oldSingleDigits = setOf(3), oldTwoDigitRanges = listOf(50..55, 57..59)),
        Carrier("Comium", "86", oldSingleDigits = setOf(6), oldTwoDigitRanges = listOf(84..87))
    )

    private const val OLD_LOCAL_LENGTH = 7
    private const val NEW_LOCAL_LENGTH = 9

    data class MigrationResult(
        val status: ContactStatus,
        val suggestedNumber: String? = null,
        val reason: String? = null
    )

    private enum class Style { PLUS, ZERO_ZERO, BARE_CC, LOCAL }

    /** Finds which carrier's OLD allocation block a 7-digit local number falls into, if any. */
    private fun matchOldCarrier(oldLocal: String): Carrier? {
        if (oldLocal.length != OLD_LOCAL_LENGTH || !oldLocal.all { it.isDigit() }) return null

        val twoDigit = oldLocal.substring(0, 2).toIntOrNull()
        CARRIERS.firstOrNull { c -> twoDigit != null && c.oldTwoDigitRanges.any { twoDigit in it } }
            ?.let { return it }

        val oneDigit = oldLocal.substring(0, 1).toIntOrNull()
        return CARRIERS.firstOrNull { c -> oneDigit != null && oneDigit in c.oldSingleDigits }
    }

    /** Strips spaces, dashes, dots, and both round/square brackets. Leaves a leading "+" alone. */
    private fun stripFormatting(rawNumber: String): String =
        rawNumber.replace(Regex("[\\s\\-.()\\[\\]]"), "")

    fun evaluate(rawNumber: String): MigrationResult {
        val stripped = stripFormatting(rawNumber)

        val (style, localPart) = when {
            stripped.startsWith("+$COUNTRY_CODE") -> Style.PLUS to stripped.substring(4)
            stripped.startsWith("00$COUNTRY_CODE") -> Style.ZERO_ZERO to stripped.substring(5)
            // Bare "220..." only counts as the country code when what's left is exactly a
            // 7-digit (old) or 9-digit (new) local number - otherwise it's just a LOCAL-style
            // number that happens to start with the digits 2-2-0 (e.g. a 7-digit Africell
            // number "2201234", which starts with the single digit "2").
            stripped.startsWith(COUNTRY_CODE) &&
                    (stripped.length == COUNTRY_CODE.length + OLD_LOCAL_LENGTH || stripped.length == COUNTRY_CODE.length + NEW_LOCAL_LENGTH) ->
                Style.BARE_CC to stripped.substring(COUNTRY_CODE.length)
            else -> Style.LOCAL to stripped
        }

        val hasMarker = style != Style.LOCAL

        if (localPart.isEmpty() || !localPart.all { it.isDigit() }) {
            return if (hasMarker) {
                MigrationResult(ContactStatus.ERROR, reason = "Contains non-numeric characters — not a recognized phone number.")
            } else {
                MigrationResult(ContactStatus.UNCHANGEABLE, reason = "Not a recognized Gambia number format.")
            }
        }

        return when (localPart.length) {
            OLD_LOCAL_LENGTH -> evaluateOldLength(localPart)
            NEW_LOCAL_LENGTH -> evaluateNewLength(localPart, hasMarker)
            else -> {
                if (hasMarker) {
                    MigrationResult(
                        ContactStatus.ERROR,
                        reason = "Only ${localPart.length} digits after +$COUNTRY_CODE — not a recognized format (expected 7 or 9)."
                    )
                } else {
                    MigrationResult(ContactStatus.UNCHANGEABLE, reason = "Not a recognized Gambia number format.")
                }
            }
        }
    }

    /** A 7-digit local part: either convertible (known carrier) or an unaffected Gambia number. */
    private fun evaluateOldLength(local7: String): MigrationResult {
        val carrier = matchOldCarrier(local7)
            ?: return MigrationResult(
                ContactStatus.UNCHANGEABLE,
                reason = "Valid Gambian number, but not in an affected Africell/QCell/Comium block."
            )

        val suggested = "+$COUNTRY_CODE${carrier.newPrefix}$local7"
        return MigrationResult(ContactStatus.CHANGEABLE, suggestedNumber = suggested, reason = "${carrier.name} — old format")
    }

    /** A 9-digit local part: either already correctly migrated, inconsistent (ERROR), or just not a Gambia prefix at all. */
    private fun evaluateNewLength(local9: String, hasMarker: Boolean): MigrationResult {
        val prefixCarrier = CARRIERS.firstOrNull { local9.startsWith(it.newPrefix) }
            ?: return if (hasMarker) {
                MigrationResult(ContactStatus.ERROR, reason = "9 digits but doesn't start with a recognized operator prefix (87/83/86).")
            } else {
                MigrationResult(ContactStatus.UNCHANGEABLE, reason = "Not a recognized Gambia number format.")
            }

        val remainder7 = local9.substring(2)
        val consistentCarrier = matchOldCarrier(remainder7)

        return if (consistentCarrier == prefixCarrier) {
            MigrationResult(ContactStatus.UNCHANGEABLE, reason = "Already in the new format (${prefixCarrier.name}).")
        } else {
            MigrationResult(
                ContactStatus.ERROR,
                reason = "Starts with ${prefixCarrier.name}'s prefix (${prefixCarrier.newPrefix}) but the remaining digits don't match ${prefixCarrier.name}'s number blocks."
            )
        }
    }

    fun classify(rawNumber: String): ContactStatus = evaluate(rawNumber).status
}
