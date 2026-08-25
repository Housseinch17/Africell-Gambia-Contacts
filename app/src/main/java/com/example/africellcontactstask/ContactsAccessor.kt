package com.example.africellcontactstask

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * STEP 1: Access all contacts.
 *
 * Standalone, drop-in-anywhere functionality for reading every phone number saved on the
 * device. Nothing here knows about validation, conversion, or status (that's step 2+) —
 * this file's only job is getting the raw data out of the phone's Contacts provider.
 *
 * Requires the READ_CONTACTS permission to already be granted — call hasPermission()
 * first, and if it's false, request Manifest.permission.READ_CONTACTS from an Activity
 * (see MainActivity for the runtime-permission flow) before calling readAllContacts().
 */
object ContactsAccessor {

    /** True if READ_CONTACTS is currently granted. */
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * Reads every phone number from every contact on the device, sorted by name.
     *
     * A contact with several numbers saved yields several entries here — one per number —
     * since each number needs to be checked independently later. Contacts with a blank/
     * missing number are skipped.
     *
     * This does a real query against ContentResolver, so call it off the main thread for
     * phones with large contact lists (see MainActivity.loadContacts() for an example
     * using a background Thread + runOnUiThread).
     *
     * @throws SecurityException if READ_CONTACTS isn't granted — check hasPermission() first.
     */
    fun readAllContacts(contentResolver: ContentResolver): List<RawContact> {
        val result = mutableListOf<RawContact>()

        val cursor: Cursor? = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone._ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )

        cursor?.use {
            val idIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone._ID)
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (it.moveToNext()) {
                val id = it.getString(idIndex) ?: continue
                val name = it.getString(nameIndex) ?: "(No name)"
                val number = it.getString(numberIndex)
                if (number.isNullOrBlank()) continue // skip contacts with no usable number

                result.add(RawContact(id = id, name = name, number = number))
            }
        }

        Log.d("MyTag","result: $result")
        return result
    }

    /**
     * Writes `newNumber` into an existing contact's Data row — modifying that record in
     * place, never creating a new one. This is the single write path shared by both
     * directions of the app: applying a fix (old → suggested) and undoing one (new →
     * old) are the same operation, just with the two numbers swapped, so both MainActivity
     * (apply) and ReportActivity/UndoManager (undo) call this instead of each keeping their
     * own copy of the ContentResolver call.
     *
     * @param dataId the Phone._ID of the row to update (Contact.id / UpdatedNumberRow.contactId).
     */
    fun writeNumber(contentResolver: ContentResolver, dataId: String, newNumber: String): Boolean {
        return try {
            val values = ContentValues().apply {
                put(ContactsContract.CommonDataKinds.Phone.NUMBER, newNumber)
            }
            val rowsUpdated = contentResolver.update(
                ContactsContract.Data.CONTENT_URI,
                values,
                "${ContactsContract.CommonDataKinds.Phone._ID} = ?",
                arrayOf(dataId)
            )
            rowsUpdated > 0
        } catch (e: SecurityException) {
            // Shouldn't happen in practice: every call site should only call this after
            // WRITE_CONTACTS is confirmed granted.
            false
        }
    }
}
