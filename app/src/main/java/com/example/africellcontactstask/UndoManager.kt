package com.example.africellcontactstask

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.content.edit

/**
 * Persists the before/after numbers from the most recent "Apply" run to local storage
 * (SharedPreferences — a plain on-device key/value file, nothing ever leaves the phone),
 * so that run can be undone later — restoring every one of its contacts back to the
 * number it had immediately before that run. "Later" specifically means even after the
 * report screen is closed or the app is restarted, since this is written to disk rather
 * than kept only in memory.
 *
 * Only ever holds ONE run at a time: a fresh "Apply" overwrites whatever was saved
 * before it, and a successful undo clears it — so "undo" only ever means "undo the most
 * recent run", matching the spec ("Undo shall be available for the last run").
 */
object UndoManager {
    private const val PREFS_NAME = "undo_store"
    private const val KEY_ROWS = "rows"
    private const val KEY_GENERATED_AT = "generated_at"

    /** Saves `rows` as the new "last run". No-ops if `rows` is empty — nothing to undo. */
    fun saveLastRun(context: Context, rows: List<UpdatedNumberRow>, generatedAt: String) {
        if (rows.isEmpty()) return
        val array = JSONArray()
        for (row in rows) {
            val obj = JSONObject()
            obj.put("contactId", row.contactId)
            obj.put("name", row.name)
            obj.put("operator", row.operator)
            obj.put("oldNumber", row.oldNumber)
            obj.put("newNumber", row.newNumber)
            array.put(obj)
        }
        prefs(context).edit {
            putString(KEY_ROWS, array.toString())
                .putString(KEY_GENERATED_AT, generatedAt)
        }
    }

    /** True if there's a saved run available to undo right now. */
    fun hasLastRun(context: Context): Boolean =
        prefs(context).getString(KEY_ROWS, null)?.let { JSONArray(it).length() > 0 } ?: false

    /** The saved run's rows plus when that run happened, or null if there's nothing saved. */
    fun loadLastRun(context: Context): List<UpdatedNumberRow>? {
        val json = prefs(context).getString(KEY_ROWS, null) ?: return null
        val array = JSONArray(json)
        if (array.length() == 0) return null
        val rows = mutableListOf<UpdatedNumberRow>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            rows.add(
                UpdatedNumberRow(
                    contactId = obj.getString("contactId"),
                    name = obj.getString("name"),
                    operator = obj.getString("operator"),
                    oldNumber = obj.getString("oldNumber"),
                    newNumber = obj.getString("newNumber")
                )
            )
        }
        return rows
    }

    /** Clears the saved run — called once it's been undone, so it can't be undone twice. */
    fun clearLastRun(context: Context) {
        prefs(context).edit { remove(KEY_ROWS).remove(KEY_GENERATED_AT) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
