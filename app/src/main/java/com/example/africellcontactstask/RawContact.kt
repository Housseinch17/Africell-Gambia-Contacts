package com.example.africellcontactstask

/**
 * A contact phone number exactly as read from the device — no validation, no status,
 * nothing from later steps. This is the output of step 1 (access all contacts) only.
 */
data class RawContact(
    val id: String,     // the Phone row's _ID (== ContactsContract.Data._ID), used to write back later
    val name: String,
    val number: String
)