package com.example.africellcontactstask

import android.app.Activity
import android.content.ContentValues
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Second screen shown after a bulk "Apply" finishes writing (see
 * MainActivity.runFixSelected()): a summary report of what happened, a table of every
 * number that actually changed, and a "Save PDF" export. This screen only reports on work
 * MainActivity already did — it never touches Contacts itself, it just reads the numbers
 * passed to it via Intent extras.
 */
class ReportActivity : AppCompatActivity() {

    private val rows = mutableListOf<UpdatedNumberRow>()
    private var numbersUpdated = 0
    private var numbersSkipped = 0
    private var numbersFailed = 0
    private var stillNeedsReview = 0
    private var contactsNotSelected = 0
    private var numbersNotAffected = 0
    private var generatedAt = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_report)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.reportRoot)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        numbersUpdated = intent.getIntExtra(EXTRA_NUMBERS_UPDATED, 0)
        numbersSkipped = intent.getIntExtra(EXTRA_NUMBERS_SKIPPED, 0)
        numbersFailed = intent.getIntExtra(EXTRA_NUMBERS_FAILED, 0)
        stillNeedsReview = intent.getIntExtra(EXTRA_STILL_NEEDS_REVIEW, 0)
        contactsNotSelected = intent.getIntExtra(EXTRA_CONTACTS_NOT_SELECTED, 0)
        numbersNotAffected = intent.getIntExtra(EXTRA_NUMBERS_NOT_AFFECTED, 0)
        generatedAt = intent.getStringExtra(EXTRA_GENERATED_AT) ?: ""
        @Suppress("UNCHECKED_CAST")
        val passedRows = intent.getSerializableExtra(EXTRA_ROWS) as? ArrayList<UpdatedNumberRow>
        rows.addAll(passedRows.orEmpty())

        findViewById<TextView>(R.id.reportHeaderCount).text =
            getString(R.string.report_header_format, numbersUpdated)
        findViewById<TextView>(R.id.reportGeneratedAt).text =
            getString(R.string.report_generated_format, generatedAt)

        val statsLayout = findViewById<LinearLayout>(R.id.reportStatsLayout)
        addStatRow(statsLayout, getString(R.string.report_stat_numbers_updated), numbersUpdated)
        addStatRow(statsLayout, getString(R.string.report_stat_numbers_skipped), numbersSkipped)
        addStatRow(statsLayout, getString(R.string.report_stat_numbers_failed), numbersFailed)
        addStatRow(statsLayout, getString(R.string.report_stat_still_needs_review), stillNeedsReview)
        addStatRow(statsLayout, getString(R.string.report_stat_contacts_not_selected), contactsNotSelected)
        addStatRow(statsLayout, getString(R.string.report_stat_numbers_not_affected), numbersNotAffected)

        val recyclerView = findViewById<RecyclerView>(R.id.reportRowsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = ReportRowAdapter(rows)
        recyclerView.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))

        findViewById<Button>(R.id.savePdfButton).setOnClickListener { savePdf() }
        findViewById<ImageButton>(R.id.reportBackButton).setOnClickListener { finish() }

        val undoButton = findViewById<Button>(R.id.undoButton)
        if (rows.isEmpty()) {
            // Nothing was actually changed this run (e.g. everything skipped/failed) —
            // there's nothing to undo.
            undoButton.visibility = View.GONE
        } else {
            undoButton.setOnClickListener { confirmUndo() }
        }
    }

    /** One label/value line in the stats block (e.g. "Numbers updated:" … "114"). */
    private fun addStatRow(container: LinearLayout, label: String, value: Int) {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(3), 0, dp(3))
        }
        val labelView = TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(themeColor(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valueView = TextView(this).apply {
            text = value.toString()
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(themeColor(R.color.text_primary))
        }
        row.addView(labelView)
        row.addView(valueView)
        container.addView(row)
    }

    private fun themeColor(colorRes: Int): Int = ContextCompat.getColor(this, colorRes)

    /**
     * Confirms, then restores every row in THIS report to its pre-run number — the same
     * "last run" UndoManager has saved locally, since this report IS that run. Styled the
     * same custom way as MainActivity's own confirm dialogs (see its confirmFixSelected()):
     * this theme renders AlertDialog's default title/message identically otherwise.
     */
    private fun confirmUndo() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(4))
        }
        val titleView = TextView(this).apply {
            text = getString(R.string.undo_confirm_title)
            setTextColor(ContextCompat.getColor(this@ReportActivity, R.color.white))
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(10))
        }
        container.addView(titleView)
        val messageView = TextView(this).apply {
            text = getString(R.string.undo_confirm_message_format, rows.size)
            setTextColor(ContextCompat.getColor(this@ReportActivity, R.color.white))
            textSize = 14f
        }
        container.addView(messageView)

        AlertDialog.Builder(this)
            .setView(container)
            .setPositiveButton(R.string.undo) { _, _ -> performUndo() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Writes every row's `oldNumber` back over its `newNumber`, off the main thread — the
     * exact reverse of what MainActivity.runFixSelected() did. Only touches Contacts
     * directly; MainActivity's own in-memory list is refreshed separately once we return to
     * it (see the RESULT_OK below, and MainActivity's reportActivityLauncher).
     */
    private fun performUndo() {
        val undoButton = findViewById<Button>(R.id.undoButton)
        undoButton.isEnabled = false
        Thread {
            var restored = 0
            for (row in rows) {
                if (ContactsAccessor.writeNumber(contentResolver, row.contactId, row.oldNumber)) {
                    restored++
                }
            }
            runOnUiThread {
                UndoManager.clearLastRun(this)
                setResult(Activity.RESULT_OK)
                if (restored == rows.size) {
                    Toast.makeText(this, getString(R.string.undo_done_format, restored), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.undo_partial_format, restored, rows.size),
                        Toast.LENGTH_LONG
                    ).show()
                    undoButton.isEnabled = true // let them retry for whatever didn't restore
                }
            }
        }.start()
    }

    /**
     * Renders the same report onto one or more PDF pages — paginating the table whenever it
     * runs past the bottom margin, redrawing the column header at the top of each new page —
     * and saves it. On Android 10+, saved into the shared Downloads folder via MediaStore, no
     * permission needed. On older versions, saved into this app's own external files folder
     * instead (also permission-free, just only reachable from inside the app / a file
     * manager's "Android/data" view rather than the shared Downloads folder).
     */
    private fun savePdf() {
        val pageWidth = 595
        val pageHeight = 842
        val margin = 40
        var y = margin
        val document = PdfDocument()
        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        var canvas = page.canvas

        val titlePaint = Paint().apply { textSize = 16f; isFakeBoldText = true; color = Color.BLACK }
        val subtitlePaint = Paint().apply { textSize = 10f; color = Color.DKGRAY }
        val labelPaint = Paint().apply { textSize = 11f; color = Color.BLACK }
        val boldPaint = Paint().apply { textSize = 11f; isFakeBoldText = true; color = Color.BLACK }
        val headerPaint = Paint().apply { textSize = 10f; isFakeBoldText = true; color = Color.DKGRAY }
        val cellPaint = Paint().apply { textSize = 10f; color = Color.DKGRAY }
        val cellBoldPaint = Paint().apply { textSize = 10f; color = Color.BLACK }

        // Table cells are fixed-width columns — a long contact name or operator drawn past its
        // column's width would visually run into the next column's text, since Canvas.drawText
        // never wraps or clips on its own. Ellipsize anything that doesn't fit instead.
        fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
            if (paint.measureText(text) <= maxWidth) return text
            val ellipsis = "…"
            val ellipsisWidth = paint.measureText(ellipsis)
            var end = paint.breakText(text, true, (maxWidth - ellipsisWidth).coerceAtLeast(0f), null)
            while (end > 0 && paint.measureText(text, 0, end) + ellipsisWidth > maxWidth) end--
            return text.substring(0, end) + ellipsis
        }

        fun newPageIfNeeded(needed: Int) {
            if (y + needed > pageHeight - margin) {
                document.finishPage(page)
                pageNumber++
                page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
                canvas = page.canvas
                y = margin
            }
        }

        canvas.drawText(getString(R.string.report_title), margin.toFloat(), y.toFloat(), titlePaint)
        y += 18
        canvas.drawText(getString(R.string.report_generated_format, generatedAt), margin.toFloat(), y.toFloat(), subtitlePaint)
        y += 24

        val stats = listOf(
            getString(R.string.report_stat_numbers_updated) to numbersUpdated,
            getString(R.string.report_stat_numbers_skipped) to numbersSkipped,
            getString(R.string.report_stat_numbers_failed) to numbersFailed,
            getString(R.string.report_stat_still_needs_review) to stillNeedsReview,
            getString(R.string.report_stat_contacts_not_selected) to contactsNotSelected,
            getString(R.string.report_stat_numbers_not_affected) to numbersNotAffected
        )
        for ((label, value) in stats) {
            newPageIfNeeded(16)
            canvas.drawText(label, margin.toFloat(), y.toFloat(), labelPaint)
            canvas.drawText(value.toString(), (pageWidth - margin - 30).toFloat(), y.toFloat(), boldPaint)
            y += 16
        }

        y += 12
        newPageIfNeeded(18)
        canvas.drawText(getString(R.string.report_updated_numbers_title), margin.toFloat(), y.toFloat(), titlePaint)
        y += 20

        val col1 = margin
        val col2 = margin + 180
        val col3 = margin + 300
        val col4 = margin + 420
        val columnGap = 8f
        val col1MaxWidth = (col2 - col1).toFloat() - columnGap
        val col2MaxWidth = (col3 - col2).toFloat() - columnGap
        val col3MaxWidth = (col4 - col3).toFloat() - columnGap
        val col4MaxWidth = (pageWidth - margin - col4).toFloat()

        fun drawTableHeader() {
            canvas.drawText(getString(R.string.report_col_contact), col1.toFloat(), y.toFloat(), headerPaint)
            canvas.drawText(getString(R.string.report_col_operator), col2.toFloat(), y.toFloat(), headerPaint)
            canvas.drawText(getString(R.string.report_col_old_number), col3.toFloat(), y.toFloat(), headerPaint)
            canvas.drawText(getString(R.string.report_col_new_number), col4.toFloat(), y.toFloat(), headerPaint)
            y += 14
        }
        drawTableHeader()

        for (row in rows) {
            newPageIfNeeded(16)
            if (y == margin) drawTableHeader() // fresh page: redraw the column header first
            canvas.drawText(ellipsize(row.name, cellBoldPaint, col1MaxWidth), col1.toFloat(), y.toFloat(), cellBoldPaint)
            canvas.drawText(ellipsize(row.operator, cellPaint, col2MaxWidth), col2.toFloat(), y.toFloat(), cellPaint)
            canvas.drawText(ellipsize(row.oldNumber, cellPaint, col3MaxWidth), col3.toFloat(), y.toFloat(), cellPaint)
            canvas.drawText(ellipsize(row.newNumber, cellBoldPaint, col4MaxWidth), col4.toFloat(), y.toFloat(), cellBoldPaint)
            y += 16
        }

        document.finishPage(page)

        val date = SimpleDateFormat("MM.dd.yyyy", Locale.US).format(Date())
        val fileName = "numbering_plan_report_$date.pdf"
        val savedLocation = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out -> document.writeTo(out) }
                    "Downloads/$fileName"
                } else {
                    null
                }
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                val file = File(dir, fileName)
                FileOutputStream(file).use { out -> document.writeTo(out) }
                file.absolutePath
            }
        } catch (e: Exception) {
            null
        }

        document.close()

        if (savedLocation != null) {
            Toast.makeText(this, getString(R.string.pdf_saved_format, savedLocation), Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, R.string.pdf_save_failed, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_NUMBERS_UPDATED = "numbers_updated"
        const val EXTRA_NUMBERS_SKIPPED = "numbers_skipped"
        const val EXTRA_NUMBERS_FAILED = "numbers_failed"
        const val EXTRA_STILL_NEEDS_REVIEW = "still_needs_review"
        const val EXTRA_CONTACTS_NOT_SELECTED = "contacts_not_selected"
        const val EXTRA_NUMBERS_NOT_AFFECTED = "numbers_not_affected"
        const val EXTRA_GENERATED_AT = "generated_at"
        const val EXTRA_ROWS = "rows"
    }
}
