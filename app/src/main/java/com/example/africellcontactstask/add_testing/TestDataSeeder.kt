package com.example.africellcontactstask.add_testing

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.OperationApplicationException
import android.os.RemoteException
import android.provider.ContactsContract

/**
 * TESTING ONLY — not part of the Contact Updater feature itself.
 *
 * A curated set of phone numbers exercising every branch of PhoneValidator, plus helpers
 * to write them into (and remove them from) the device's/emulator's real Contacts
 * provider, so the whole pipeline (steps 1-5) can be exercised end to end without having
 * to type test numbers in by hand.
 *
 * All seeded contacts are named starting with "TEST " and include the expected status in
 * the name, so they're easy to recognize in the list and to bulk-remove afterward with
 * removeTestContacts(). Remove this file (and its button in MainActivity) before shipping.
 */
object TestDataSeeder {

    private const val NAME_PREFIX = "TEST "

    /** name -> number. The name encodes what PhoneValidator is expected to return. */
    val TEST_CONTACTS: List<Pair<String, String>> = listOf(
        // ---- CHANGEABLE: Africell (old prefixes 7, 2, 40, 41, 45) across all 4 input styles ----
        "${NAME_PREFIX}01 Changeable Africell +220 (7)" to "+220 701 1234",
        "${NAME_PREFIX}02 Changeable Africell 00220 (2)" to "00220 2021234",
        "${NAME_PREFIX}03 Changeable Africell bare-220 (40)" to "220 401 1234",
        "${NAME_PREFIX}04 Changeable Africell local (41)" to "411 1234",
        "${NAME_PREFIX}05 Changeable Africell punctuation (45)" to "+220.451-1234",

        // ---- CHANGEABLE: Qcell (old prefixes 3, 50-55, 57-59) ----
        "${NAME_PREFIX}06 Changeable Qcell +220 (3)" to "+220 301 1234",
        "${NAME_PREFIX}07 Changeable Qcell local (50)" to "501 1234",
        "${NAME_PREFIX}08 Changeable Qcell bare-220 (55)" to "220 551 1234",
        "${NAME_PREFIX}09 Changeable Qcell 00220 (57)" to "00220 5711234",
        "${NAME_PREFIX}10 Changeable Qcell brackets (59)" to "+220 (591) 1234",

        // ---- CHANGEABLE: Comium (old prefixes 6, 84-87) ----
        "${NAME_PREFIX}11 Changeable Comium +220 (6)" to "+220 601 1234",
        "${NAME_PREFIX}12 Changeable Comium local (84)" to "841 1234",
        "${NAME_PREFIX}13 Changeable Comium bare-220 (85)" to "220 851 1234",
        "${NAME_PREFIX}14 Changeable Comium 00220 (86)" to "00220 8611234",
        "${NAME_PREFIX}15 Changeable Comium (87)" to "+220 871 1234",

        // ---- ERROR: has a Gambia marker but can't be safely classified ----
        "${NAME_PREFIX}16 Error too short +220 (5 digits)" to "+220 12345",
        "${NAME_PREFIX}17 Error too long +220 (8 digits)" to "+220 12345678",
        "${NAME_PREFIX}18 Error non-numeric junk" to "+220 12a4567",
        "${NAME_PREFIX}19 Error inconsistent 9-digit (87+Qcell block)" to "+220 87 3011234",
        "${NAME_PREFIX}20 Error 9-digit unrecognized prefix" to "+220 912345678",

        // ---- UNCHANGEABLE: left alone, not flagged ----
        "${NAME_PREFIX}21 Unchangeable already valid Africell" to "+220 87 7011234",
        "${NAME_PREFIX}22 Unchangeable already valid Qcell" to "+220 83 3011234",
        "${NAME_PREFIX}23 Unchangeable unaffected 7-digit (9)" to "+220 901 1234",
        "${NAME_PREFIX}24 Unchangeable international (US)" to "+1 202 555 0143",
        "${NAME_PREFIX}25 Unchangeable no marker wrong length" to "0612345678"
    )

    /**
     * Inserts every TEST_CONTACTS entry as a real contact (one ContentProviderOperation
     * batch per contact: new raw contact + display name + phone number). Returns how many
     * were successfully inserted. Requires WRITE_CONTACTS to already be granted.
     */
    fun seedTestContacts(contentResolver: ContentResolver): Int {
        var inserted = 0
        for ((name, number) in TEST_CONTACTS) {
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
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build()
            )

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

    /** Deletes every contact whose display name starts with "TEST ". Returns how many raw contacts were removed. */
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
