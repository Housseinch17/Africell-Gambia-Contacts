package com.example.africellcontactstask

import java.io.Serializable

/**
 * One row in the "Updated numbers" table on the report screen (see ReportActivity) — a
 * plain, screen-agnostic record of one number that was actually changed, built while
 * MainActivity.runFixSelected() is writing to Contacts. Serializable so it can travel to
 * ReportActivity via Intent extras.
 */
data class UpdatedNumberRow(
    val name: String,
    val operator: String,
    val oldNumber: String,
    val newNumber: String
) : Serializable
