package com.example.africellcontactstask.add_testing

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.OperationApplicationException
import android.os.RemoteException
import android.provider.ContactsContract

/**
 * TESTING ONLY — not part of the Contact Updater feature itself.
 *
 * A curated demo dataset exercising every branch of PhoneValidator, plus helpers to write
 * it into (and remove it from) the device's/emulator's real Contacts provider, so the whole
 * pipeline can be exercised end to end without having to type test numbers in by hand.
 *
 * Coverage (per the Contact Updater test-data requirements):
 *  - Every operator prefix — Africell (7, 2, 40, 41, 45), QCell (3, 50-55, 57-59),
 *    Comium (6, 84, 85, 86, 87) — each in both LOCAL (no country code) and INTERNATIONAL
 *    format (rotated across +220 / 00220 / bare 220 so all three marker styles, plus
 *    space/dot/dash/bracket punctuation, are exercised).
 *  - Numbers already in the new 9-digit format, one per operator, to confirm they're left
 *    alone rather than double-converted.
 *  - An unaffected (valid but not-in-any-block) Gambian number and foreign international
 *    numbers, both of which must be left completely untouched.
 *  - Too-short / too-long / non-numeric / inconsistent-prefix numbers, which must all land
 *    in ERROR ("needs review") rather than being silently changed or silently ignored.
 *  - Two contacts that each hold more than one phone number, so a contact with one
 *    affected number and one unrelated number is exercised too.
 *
 * All seeded contacts are named starting with "TEST " and include the expected status in
 * the name, so they're easy to recognize in the list and to bulk-remove afterward with
 * removeTestContacts(). Remove this file (and its buttons in MainActivity) before shipping.
 */
object TestDataSeeder {

    private const val NAME_PREFIX = "TEST "

    /** One seeded contact: a display name, and the one-or-more phone numbers under it. */
    private fun single(name: String, number: String): Pair<String, List<String>> =
        "$NAME_PREFIX$name" to listOf(number)

    private fun multi(name: String, vararg numbers: String): Pair<String, List<String>> =
        "$NAME_PREFIX$name" to numbers.toList()

    /** name -> phone number(s). The name encodes what PhoneValidator is expected to return. */
    val TEST_CONTACTS: List<Pair<String, List<String>>> = listOf(

        // ============================================================
        // CHANGEABLE — every operator prefix, once as a LOCAL number and once as an
        // INTERNATIONAL number. International style rotates through +220 / 00220 / bare
        // 220 (with a couple written in dots/dashes/brackets instead of spaces) so every
        // recognized marker style and punctuation gets exercised, not just "+220".
        // ============================================================

        // ---- Africell: single digits 7, 2 — whole decades; two-digit blocks 40, 41, 45 ----
        single("001 Changeable Africell local (7)", "701 1234"),
        single("002 Changeable Africell +220 (7)", "+220 701 1234"),
        single("003 Changeable Africell local (2)", "201 1234"),
        single("004 Changeable Africell 00220 (2)", "00220 201 1234"),
        single("005 Changeable Africell local (40)", "401 1234"),
        single("006 Changeable Africell bare-220 (40)", "220 401 1234"),
        single("007 Changeable Africell local (41)", "411 1234"),
        single("008 Changeable Africell +220 punctuation (41)", "+220.411-1234"),
        single("009 Changeable Africell local (45)", "451 1234"),
        single("010 Changeable Africell 00220 (45)", "00220 451 1234"),

        // ---- QCell: single digit 3 — whole decade; two-digit blocks 50-55, 57-59 ----
        single("011 Changeable QCell local (3)", "301 1234"),
        single("012 Changeable QCell bare-220 (3)", "220 301 1234"),
        single("013 Changeable QCell local (50)", "501 1234"),
        single("014 Changeable QCell +220 (50)", "+220 501 1234"),
        single("015 Changeable QCell local (51)", "511 1234"),
        single("016 Changeable QCell 00220 (51)", "00220 511 1234"),
        single("017 Changeable QCell local (52)", "521 1234"),
        single("018 Changeable QCell bare-220 (52)", "220 521 1234"),
        single("019 Changeable QCell local (53)", "531 1234"),
        single("020 Changeable QCell +220 brackets (53)", "+220 (531) 1234"),
        single("021 Changeable QCell local (54)", "541 1234"),
        single("022 Changeable QCell 00220 (54)", "00220 541 1234"),
        single("023 Changeable QCell local (55)", "551 1234"),
        single("024 Changeable QCell bare-220 (55)", "220 551 1234"),
        single("025 Changeable QCell local (57)", "571 1234"),
        single("026 Changeable QCell +220 (57)", "+220 571 1234"),
        single("027 Changeable QCell local (58)", "581 1234"),
        single("028 Changeable QCell 00220 (58)", "00220 581 1234"),
        single("029 Changeable QCell local (59)", "591 1234"),
        single("030 Changeable QCell bare-220 (59)", "220 591 1234"),

        // ---- Comium: single digit 6 — whole decade; two-digit blocks 84, 85, 86, 87 ----
        single("031 Changeable Comium local (6)", "601 1234"),
        single("032 Changeable Comium +220 (6)", "+220 601 1234"),
        single("033 Changeable Comium local (84)", "841 1234"),
        single("034 Changeable Comium 00220 (84)", "00220 841 1234"),
        single("035 Changeable Comium local (85)", "851 1234"),
        single("036 Changeable Comium bare-220 (85)", "220 851 1234"),
        single("037 Changeable Comium local (86)", "861 1234"),
        single("038 Changeable Comium +220 (86)", "+220 861 1234"),
        single("039 Changeable Comium local (87)", "871 1234"),
        single("040 Changeable Comium 00220 (87)", "00220 871 1234"),

        // ============================================================
        // UNCHANGEABLE — already in the new format: must be recognized and left alone,
        // never converted a second time. One per operator.
        // ============================================================
        single("041 Unchangeable already-new-format Africell", "+220 87 7011234"),
        single("042 Unchangeable already-new-format QCell", "+220 83 3011234"),
        single("043 Unchangeable already-new-format Comium", "+220 86 6011234"),

        // ============================================================
        // UNCHANGEABLE — a valid Gambian number that simply isn't in any affected block.
        // ============================================================
        single("044 Unchangeable unaffected +220 (9)", "+220 901 1234"),
        single("045 Unchangeable unaffected local (9)", "901 1234"),

        // ============================================================
        // UNCHANGEABLE — foreign international numbers: must be left completely untouched.
        // ============================================================
        single("046 Unchangeable foreign (US)", "+1 202 555 0143"),
        single("047 Unchangeable foreign (UK)", "+44 20 7946 0958"),

        // ============================================================
        // ERROR ("needs review") — has a Gambia marker (or a 9-digit operator prefix) but
        // can't be safely classified: wrong length, non-numeric junk, or an inconsistent
        // 9-digit number. Must be flagged for manual review, never silently changed or
        // silently ignored.
        // ============================================================
        single("048 Error too short +220 (5 digits)", "+220 12345"),
        single("049 Error too long +220 (8 digits)", "+220 12345678"),
        single("050 Error non-numeric junk", "+220 12a4567"),
        single("051 Error inconsistent 9-digit (87 prefix + QCell block)", "+220 87 3011234"),
        single("052 Error 9-digit unrecognized prefix", "+220 912345678"),
        single("053 Error too short 00220 (6 digits)", "00220 123456"),

        // ============================================================
        // Contacts holding more than one number — one affected number plus another,
        // unrelated number under the SAME contact. Each number is still classified and
        // listed independently.
        // ============================================================
        multi(
            "054 Multi-number (Changeable Africell + foreign)",
            "+220 701 1234",   // Changeable
            "+1 202 555 0143"  // Unchangeable (foreign)
        ),
        multi(
            "055 Multi-number (Changeable QCell + Error)",
            "+220 501 1234",  // Changeable
            "+220 12345"      // Error (too short)
        ),
    )

    /**
     * Inserts every TEST_CONTACTS entry as a real contact — one ContentProviderOperation
     * batch per contact: a new raw contact, its display name, and one Data/Phone row per
     * number (a multi-number entry gets several Phone rows under the same raw contact).
     * Returns how many contacts were successfully inserted. Requires WRITE_CONTACTS to
     * already be granted.
     */
    fun seedTestContacts(contentResolver: ContentResolver): Int {
        var inserted = 0
        for ((name, numbers) in TEST_CONTACTS) {
            val ops = ArrayList<ContentProviderOperation>()

            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build()
            )
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    .build()
            )
            for (number in numbers) {
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                        .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                        .build()
                )
            }

            try {
                contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                inserted++
            } catch (e: RemoteException) {
                // skip this one, keep seeding the rest
            } catch (e: OperationApplicationException) {
                // skip this one, keep seeding the rest
            }
        }
        return inserted
    }

    /**
     * Deletes every contact whose display name starts with "TEST ". Deleting a raw contact
     * cascades to all of its Data rows, so a multi-number contact's extra phone numbers are
     * removed along with it in one delete — no separate cleanup needed. Returns how many
     * raw contacts were removed.
     */
    fun removeTestContacts(contentResolver: ContentResolver): Int {
        var deleted = 0

        val cursor = contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY} LIKE ?",
            arrayOf("$NAME_PREFIX%"),
            null
        )

        cursor?.use {
            val idIndex = it.getColumnIndex(ContactsContract.RawContacts._ID)
            while (it.moveToNext()) {
                val rawContactId = it.getLong(idIndex)
                val rows = contentResolver.delete(
                    ContactsContract.RawContacts.CONTENT_URI,
                    "${ContactsContract.RawContacts._ID} = ?",
                    arrayOf(rawContactId.toString())
                )
                if (rows > 0) deleted++
            }
        }

        return deleted
    }
}