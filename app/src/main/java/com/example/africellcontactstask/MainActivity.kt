package com.example.africellcontactstask

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.africellcontactstask.add_testing.TestDataSeeder
import com.example.africellcontactstask.add_testing.TestDataSeeder.removeTestContacts
import com.example.africellcontactstask.add_testing.TestDataSeeder.seedTestContacts
import com.google.android.material.tabs.TabLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    // The full, unfiltered source of truth for every contact loaded from the device.
    private val contacts = mutableListOf<Contact>()

    // Step 3, as a tab-filtered view: only the contacts matching `currentFilter`, in the
    // same order they appear in `contacts`. The adapter wraps THIS list, not `contacts` —
    // switching tabs just rebuilds it and re-notifies the adapter.
    private val displayedContacts = mutableListOf<Contact>()
    private var currentFilter: ContactStatus = ContactStatus.CHANGEABLE

    private lateinit var adapter: ContactAdapter
    private lateinit var statusTabLayout: TabLayout
    private lateinit var changeableActionsLayout: View
    private lateinit var selectAllCheckbox: CheckBox
    private lateinit var fixSelectedButton: Button

    // WRITE_CONTACTS is also requested lazily right before the first thing that actually
    // needs to write, as a defensive fallback in case permission was somehow revoked (e.g.
    // from system Settings) after the app's own onboarding already granted both permissions
    // together. `pendingWriteAction` holds whatever write was waiting on the permission so
    // it can run immediately once the user grants it, instead of silently failing.
    private var pendingWriteAction: (() -> Unit)? = null

    // Launched instead of a plain startActivity() so ReportActivity can report back that it
    // performed its own "Undo" — see its performUndo() setting RESULT_OK — at which point
    // MainActivity's own in-memory contacts (and undo banner) need a refresh too.
    private val reportActivityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                loadContacts()
            }
            refreshUndoBannerFromStorage()
        }

    private val writeContactsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                pendingWriteAction?.invoke()
            } else {
                Toast.makeText(
                    this,
                    "Contacts write permission is required to save changes.",
                    Toast.LENGTH_LONG
                ).show()
            }
            pendingWriteAction = null
        }

    // Contacts permission: the app needs BOTH read (to scan) and write (to update numbers)
    // access, requested together in a single system dialog — on first launch, and again by
    // the test-data seed/remove buttons if permission was never granted. Requesting both up
    // front means "Fix number" / "Fix selected numbers" never hit a surprise second prompt.
    private var pendingPermissionAction: (() -> Unit)? = null

    private val contactsPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            setPermissionEntryPointsEnabled(true)
            if (results.values.all { it }) {
                findViewById<View>(R.id.permissionLayout).visibility = View.GONE
                pendingPermissionAction?.invoke()
            } else {
                showPermissionDenied()
            }
            pendingPermissionAction = null
        }

    private fun hasContactsPermissions(): Boolean {
        val hasRead = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        val hasWrite = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        return hasRead && hasWrite
    }

    private fun runWithContactsPermissions(action: () -> Unit) {
        if (hasContactsPermissions()) {
            action()
        } else {
            pendingPermissionAction = action
            // Disabled right away rather than only once the granted action actually starts
            // (setLoading() does that part) — there's a brief gap between calling launch()
            // and the system dialog actually taking over the screen, and a fast repeat tap
            // in that gap used to queue a SECOND permission request/pending action, which
            // could make e.g. seedTestContacts() run more than once for what was really a
            // single grant.
            setPermissionEntryPointsEnabled(false)
            contactsPermissionsLauncher.launch(
                arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
            )
        }
    }

    /** Enables/disables every button that can kick off a permission request (see above). */
    private fun setPermissionEntryPointsEnabled(enabled: Boolean) {
        findViewById<View>(R.id.grantPermissionButton).isEnabled = enabled
        findViewById<Button>(R.id.seedTestContactsButton).isEnabled = enabled
        findViewById<Button>(R.id.removeTestContactsButton).isEnabled = enabled
    }

    /**
     * Shown after the user denies the read+write request: swaps the rationale text to the
     * "denied" phrasing, reveals a button that deep-links straight to this app's page in the
     * system Settings so the permission can still be granted manually, and gives immediate
     * toast feedback.
     */
    private fun showPermissionDenied() {
        findViewById<TextView>(R.id.permissionText).text =
            getString(R.string.permission_denied_rationale)
        findViewById<View>(R.id.openSettingsButton).visibility = View.VISIBLE
        Toast.makeText(
            this,
            "Contacts read & write permission are both required to scan and update numbers.",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Wire up all views FIRST — bindSelectAllCheckbox() and the adapter below both
        // touch these, so they must be assigned before anything else runs.
        statusTabLayout = findViewById(R.id.statusTabLayout)
        changeableActionsLayout = findViewById(R.id.changeableActionsLayout)
        selectAllCheckbox = findViewById(R.id.selectAllCheckbox)
        fixSelectedButton = findViewById(R.id.fixSelectedButton)

        // Three tabs, one per status. Each tab's tag holds the ContactStatus it filters to,
        // so onTabSelected doesn't need to guess from position. Labels (with live counts)
        // are filled in by updateSummary() once contacts are loaded.
        statusTabLayout.addTab(statusTabLayout.newTab().setTag(ContactStatus.CHANGEABLE).setText(R.string.status_changeable))
        statusTabLayout.addTab(statusTabLayout.newTab().setTag(ContactStatus.ERROR).setText(R.string.status_error))
        statusTabLayout.addTab(statusTabLayout.newTab().setTag(ContactStatus.UNCHANGEABLE).setText(R.string.status_unchangeable))
        statusTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentFilter = tab.tag as ContactStatus
                refreshDisplayedContacts()
                updateChangeableActionsVisibility()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        bindSelectAllCheckbox()
        fixSelectedButton.setOnClickListener { confirmFixSelected() }
        findViewById<Button>(R.id.undoLastUpdateButton).setOnClickListener { confirmUndoLastRun() }
        refreshUndoBannerFromStorage()

        val recyclerView = findViewById<RecyclerView>(R.id.contactsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = ContactAdapter(
            displayedContacts,
            onSaveNumberClicked = { contact, newNumber -> saveEditedNumber(contact, newNumber) },
            onKeepAsIsClicked = { contact -> keepAsIs(contact) },
            onSelectionToggled = { contact, isChecked -> onContactSelectionToggled(contact, isChecked) },
        )
        recyclerView.adapter = adapter

        // Label reads the real dataset size directly from TestDataSeeder.TEST_CONTACTS
        // rather than a hardcoded count in strings.xml, so it can't silently go stale
        // again if the dataset is ever expanded (as it was, 25 -> 55, without this).
        findViewById<Button>(R.id.seedTestContactsButton).apply {
            text = getString(R.string.seed_test_contacts_format, TestDataSeeder.TEST_CONTACTS.size)
            setOnClickListener { runWithContactsPermissions { seedTestContacts() } }
        }
        findViewById<Button>(R.id.removeTestContactsButton).setOnClickListener {
            runWithContactsPermissions { removeTestContacts() }
        }

        findViewById<View>(R.id.grantPermissionButton).setOnClickListener {
            runWithContactsPermissions { loadContacts() }
        }
        findViewById<View>(R.id.openSettingsButton).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
        }

        if (hasContactsPermissions()) {
            loadContacts()
        } else {
            findViewById<View>(R.id.permissionLayout).visibility = View.VISIBLE
        }
    }

    /** Wires the "select all" checkbox: toggling it sets `selected` on every CHANGEABLE contact. */
    private fun bindSelectAllCheckbox() {
        selectAllCheckbox.setOnCheckedChangeListener { _, isChecked ->
            changeableContacts().forEach { it.selected = isChecked }
            adapter.notifyDataSetChanged()
            updateFixSelectedButton()
        }
    }

    /**
     * Keeps "Select all" in sync with the individual row checkboxes: checked only when every
     * CHANGEABLE contact is currently selected, unchecked otherwise (including when the list
     * is empty, or only partially selected). Rebinds the listener afterward without firing it,
     * so this can be called freely without triggering bindSelectAllCheckbox()'s own logic.
     */
    private fun syncSelectAllCheckboxState() {
        val changeable = changeableContacts()
        val allSelected = changeable.isNotEmpty() && changeable.all { it.selected }
        selectAllCheckbox.setOnCheckedChangeListener(null)
        selectAllCheckbox.isChecked = allSelected
        bindSelectAllCheckbox()
    }

    /**
     * STEP 1: Access all contacts — via the standalone ContactsAccessor (see that file).
     * STEP 2: Phone Validator — each RawContact is immediately run through PhoneValidator.
     *
     * Runs off the main thread since a phone with a large contact list could otherwise
     * cause a noticeable freeze / ANR.
     */
    private fun loadContacts() {
        setLoading(true)

        Thread {
            // Step 1, in isolation: just the raw (id, name, number) triples off the device.
            val rawContacts = ContactsAccessor.readAllContacts(contentResolver)

            // Step 2: classify each one. (Kept as a separate pass on purpose, so step 1
            // stays reusable on its own — e.g. for a different validator, or no validator.)
            val loaded = rawContacts.map { raw ->
                val result = PhoneValidator.evaluate(raw.number)
                Contact(
                    id = raw.id,
                    name = raw.name,
                    phoneNumber = raw.number,
                    status = result.status,
                    suggestedNumber = result.suggestedNumber,
                    reason = result.reason,
                    carrier = result.carrierName,
                    // "Will be updated" contacts are selected by default — the user
                    // unselects the ones they don't want, rather than opting each one in.
                    selected = result.status == ContactStatus.CHANGEABLE
                )
            }

            runOnUiThread {
                contacts.clear()
                contacts.addAll(loaded)

                // Fresh data means fresh selection state.
                syncSelectAllCheckboxState()

                // Refreshes displayedContacts (for the current tab) and notifies the adapter.
                updateSummary()
                setLoading(false)
            }
        }.start()
    }

    /**
     * Shows/hides the loading spinner and toggles the contact list + selection bar accordingly.
     * `message` lets each call site say what it's actually doing (reading, updating, adding
     * test data, ...) instead of the label always defaulting to "Reading contacts…" regardless
     * of which operation is actually running.
     *
     * Also disables the seed/remove test-data buttons for the duration: they were previously
     * still tappable while a seed/remove was already running, so a fast double-tap (or tapping
     * one while the other was mid-flight) could kick off a second overlapping write — e.g. two
     * concurrent seedTestContacts() calls each inserting their own copy of the dataset. Since
     * every write path routes through here, disabling in one place covers all of them.
     */
    private fun setLoading(loading: Boolean, message: String? = null) {
        findViewById<View>(R.id.contactsProgressBar).visibility =
            if (loading) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.loadingText).apply {
            visibility = if (loading) View.VISIBLE else View.GONE
            if (loading) {
                text = message ?: getString(R.string.loading_contacts)
            }
        }
        findViewById<RecyclerView>(R.id.contactsRecyclerView).visibility =
            if (loading) View.GONE else View.VISIBLE
        if (loading) {
            changeableActionsLayout.visibility = View.GONE
        }
        findViewById<Button>(R.id.seedTestContactsButton).isEnabled = !loading
        findViewById<Button>(R.id.removeTestContactsButton).isEnabled = !loading
    }

    /**
     * Runs `action` immediately if WRITE_CONTACTS is already granted; otherwise requests it
     * and runs `action` right after the user grants it (or shows a message and gives up if
     * they deny it). Used for every code path that writes to Contacts: the test-data
     * seed/remove buttons AND the real "Apply fix" / "Fix selected numbers" actions.
     */
    private fun runWithWriteContactsPermission(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingWriteAction = action
            writeContactsPermissionLauncher.launch(Manifest.permission.WRITE_CONTACTS)
        }
    }

    // --- TESTING ONLY below: seeds/removes the curated TestDataSeeder.TEST_CONTACTS set ---

    private fun seedTestContacts() {
        Log.d("MyTag","seedTestContacts: called")
        setLoading(true, getString(R.string.adding_test_contacts))
        Thread {
            // Clears out any previously-seeded "TEST " contacts first, so this is always
            // idempotent — exactly TEST_CONTACTS.size contacts after this call, never a
            // pile that keeps growing if it ends up running more than once (an earlier
            // permission-request race let that happen; this is a second, independent
            // safety net for it regardless of cause).
            removeTestContacts(contentResolver)
            val count = seedTestContacts(contentResolver)
            runOnUiThread {
                Toast.makeText(this, getString(R.string.seed_test_done, count), Toast.LENGTH_LONG)
                    .show()
                loadContacts() // reload (its own background thread) so the new test contacts show up, classified
            }
        }.start()
    }

    private fun removeTestContacts() {
        setLoading(true, getString(R.string.removing_test_contacts))
        Thread {
            val count = removeTestContacts(contentResolver)
            runOnUiThread {
                Toast.makeText(this, getString(R.string.remove_test_done, count), Toast.LENGTH_LONG)
                    .show()
                loadContacts()
            }
        }.start()
    }

    // STEP 3: sort into three lists. These are derived views over the single `contacts`
    // source of truth (rather than duplicated lists) so a status change can never leave
    // one list stale relative to another — but the three categories you asked for are
    // exactly these three filters, and step 7's PDF will read from these same three.
    private fun changeableContacts(): List<Contact> =
        contacts.filter { it.status == ContactStatus.CHANGEABLE }

    private fun errorContacts(): List<Contact> =
        contacts.filter { it.status == ContactStatus.ERROR }

    private fun unchangeableContacts(): List<Contact> =
        contacts.filter { it.status == ContactStatus.UNCHANGEABLE }

    private fun updateSummary() {
        val changeable = changeableContacts().size
        val error = errorContacts().count { !it.resolved }
        val unchangeable = unchangeableContacts().size

        statusTabLayout.getTabAt(0)?.text = getString(R.string.tab_changeable_format, changeable)
        statusTabLayout.getTabAt(1)?.text = getString(R.string.tab_error_format, error)
        statusTabLayout.getTabAt(2)?.text = getString(R.string.tab_unchangeable_format, unchangeable)

        refreshDisplayedContacts()
        updateChangeableActionsVisibility()
        updateFixSelectedButton()
    }

    /** Rebuilds `displayedContacts` from `contacts` for whichever tab is currently selected. */
    private fun refreshDisplayedContacts() {
        displayedContacts.clear()
        displayedContacts.addAll(contacts.filter { it.status == currentFilter })
        adapter.notifyDataSetChanged()
    }

    /** The checkbox/"Fix selected" bar only makes sense on the Changeable tab. */
    private fun updateChangeableActionsVisibility() {
        changeableActionsLayout.visibility =
            if (currentFilter == ContactStatus.CHANGEABLE && changeableContacts().isNotEmpty())
                View.VISIBLE else View.GONE
    }

    /** Called whenever a row's checkbox is toggled (CHANGEABLE contacts only). */
    private fun onContactSelectionToggled(contact: Contact, isChecked: Boolean) {
        contact.selected = isChecked
        syncSelectAllCheckboxState()
        updateFixSelectedButton()
    }

    /** Keeps the "Fix selected (N)" button label in sync with how many are currently checked. */
    private fun updateFixSelectedButton() {
        val selectedCount = changeableContacts().count { it.selected }
        fixSelectedButton.text = getString(R.string.fix_selected_format, selectedCount)
    }

    /**
     * "Save" action from an ERROR contact's inline edit card (see ContactAdapter — the
     * editable field, live re-classification, and Cancel/Save controls all live in the card
     * itself now, not a popup dialog). Writes the typed number back to the phone's real
     * contact record in place, then exits edit mode either way — on failure the card just
     * returns to its normal "Leave As Is" / "Fix Number" state so the user can retry.
     */
    private fun saveEditedNumber(contact: Contact, newNumber: String) {
        runWithWriteContactsPermission {
            if (writeNumberToContact(contact.id, newNumber)) {
                applyResolvedNumber(contact, newNumber)
            } else {
                contact.isEditing = false
                Toast.makeText(this, "Could not update this contact.", Toast.LENGTH_SHORT).show()
            }
            updateSummary()
        }
    }

    /** "Keep as is" action for an ERROR contact: no change to the number, just marks it as reviewed. */
    private fun keepAsIs(contact: Contact) {
        contact.resolved = true
        updateSummary()
    }

    /** "Fix selected numbers": confirm, then apply every checked CHANGEABLE contact's suggestion in one go. */
    private fun confirmFixSelected() {
        val pending = contacts.filter { it.status == ContactStatus.CHANGEABLE && it.selected }

        if (pending.isEmpty()) {
            Toast.makeText(this, R.string.fix_selected_none, Toast.LENGTH_SHORT).show()
            return
        }

        // Built explicitly instead of relying on setTitle()/setMessage() — on this theme
        // the dialog's default title and message text appearances rendered visually
        // identical (same size/weight/color), making them hard to tell apart. A custom
        // view guarantees the title reads as bold/larger/dark and the message as
        // smaller/regular/gray, regardless of theme.
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(4))
        }

        val titleView = TextView(this).apply {
            text = getString(R.string.fix_selected_confirm_title)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.white))
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(10))
        }
        container.addView(titleView)

        val messageView = TextView(this).apply {
            text = getString(R.string.fix_selected_confirm_message)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.white))
            textSize = 14f
        }
        container.addView(messageView)

        AlertDialog.Builder(this)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                runWithWriteContactsPermission { runFixSelected(pending) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Writes every pending contact's suggested number back to Contacts off the main thread
     * (a synchronous ContentResolver.update() per contact, run on the UI thread, risked
     * jank/ANR for a large selection), then — instead of just a toast — opens ReportActivity
     * with a full summary of what happened plus a row for every number actually changed.
     */
    private fun runFixSelected(pending: List<Contact>) {
        setLoading(true, getString(R.string.updating_contacts))

        // Snapshots taken before any mutation below changes which bucket a contact falls
        // into. applyResolvedNumber() re-evaluates each fixed contact's new (now correctly
        // formatted) number, which re-classifies it as UNCHANGEABLE — so unchangeableContacts()
        // must be read now, not after the loop, or "Numbers not affected" would end up counting
        // the numbers this very run just fixed.
        val notSelectedCount = changeableContacts().count { !it.selected }
        val numbersNotAffectedCount = unchangeableContacts().size

        Thread {
            var updated = 0
            var skipped = 0
            var failed = 0
            val rows = ArrayList<UpdatedNumberRow>()

            for (contact in pending) {
                val newNumber = contact.suggestedNumber
                if (newNumber == null) {
                    skipped++
                    continue
                }
                val oldNumber = contact.phoneNumber
                if (writeNumberToContact(contact.id, newNumber)) {
                    val operator = contact.carrier ?: "-"
                    applyResolvedNumber(contact, newNumber)
                    rows.add(UpdatedNumberRow(contact.id, contact.name, operator, oldNumber, newNumber))
                    updated++
                } else {
                    failed++
                }
            }

            val generatedAt = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date())

            // Saved locally (on-device only, see UndoManager) so this run can be undone
            // later — from the report screen that's about to open, or from the "Undo last
            // update" banner on this screen even after that report is closed.
            UndoManager.saveLastRun(this, rows, generatedAt)

            runOnUiThread {
                syncSelectAllCheckboxState()
                updateSummary()
                updateUndoBanner(rows) // already in hand — no need to re-read what was just saved
                setLoading(false)

                reportActivityLauncher.launch(
                    Intent(this, ReportActivity::class.java).apply {
                        putExtra(ReportActivity.EXTRA_NUMBERS_UPDATED, updated)
                        putExtra(ReportActivity.EXTRA_NUMBERS_SKIPPED, skipped)
                        putExtra(ReportActivity.EXTRA_NUMBERS_FAILED, failed)
                        putExtra(ReportActivity.EXTRA_STILL_NEEDS_REVIEW, errorContacts().count { !it.resolved })
                        putExtra(ReportActivity.EXTRA_CONTACTS_NOT_SELECTED, notSelectedCount)
                        putExtra(ReportActivity.EXTRA_NUMBERS_NOT_AFFECTED, numbersNotAffectedCount)
                        putExtra(ReportActivity.EXTRA_GENERATED_AT, generatedAt)
                        putExtra(ReportActivity.EXTRA_ROWS, rows)
                    }
                )
            }
        }.start()
    }

    /**
     * Shared bookkeeping after a number has actually been written back to Contacts. Only
     * mutates the Contact object itself — it's called both from the UI thread (a single
     * "Apply fix") and from the background thread inside runFixSelected's loop, so it must
     * NOT touch the adapter or any view directly. Every call site is responsible for
     * calling updateSummary() afterward (on the UI thread) to refresh the tab-filtered list.
     *
     * `resolved` is only set once the new number is no longer classified as ERROR — if a
     * manually-typed fix is still ambiguous, the contact stays unresolved so "Apply fix" /
     * "Keep as is" remain visible and the user can correct it again, instead of the row
     * silently losing its action buttons while still flagged as an error.
     */
    private fun applyResolvedNumber(contact: Contact, newNumber: String) {
        contact.phoneNumber = newNumber
        val result = PhoneValidator.evaluate(newNumber)
        contact.status = result.status
        contact.suggestedNumber = result.suggestedNumber
        contact.reason = result.reason
        contact.carrier = result.carrierName
        contact.resolved = result.status != ContactStatus.ERROR
        contact.selected = false
        contact.isEditing = false
    }

    /** Writes the corrected number back to the phone's contact — see ContactsAccessor.writeNumber(). */
    private fun writeNumberToContact(rawContactDataId: String, newNumber: String): Boolean =
        ContactsAccessor.writeNumber(contentResolver, rawContactDataId, newNumber)

    // --- Undo: restores the last Apply run's contacts to their pre-run numbers. The run is
    // saved locally by UndoManager (see runFixSelected below), so this stays available even
    // after the report screen is closed or the app is restarted. ---

    /** Shows/hides the undo banner for a known set of rows (null/empty = hide). */
    private fun updateUndoBanner(rows: List<UpdatedNumberRow>?) {
        val banner = findViewById<View>(R.id.undoBannerLayout)
        if (rows.isNullOrEmpty()) {
            banner.visibility = View.GONE
        } else {
            findViewById<TextView>(R.id.undoBannerText).text =
                getString(R.string.undo_banner_format, rows.size)
            banner.visibility = View.VISIBLE
        }
    }

    /**
     * Cold path: reads whatever UndoManager currently has saved and updates the banner —
     * used where we don't already know that state in memory (app start, and coming back
     * from ReportActivity, which may have undone it independently). A saved run can be
     * sizeable (up to one row per contact fixed, so potentially a few hundred KB of JSON),
     * so the read + parse happens off the main thread rather than blocking onCreate/etc.
     */
    private fun refreshUndoBannerFromStorage() {
        Thread {
            val lastRun = UndoManager.loadLastRun(this)
            runOnUiThread { updateUndoBanner(lastRun) }
        }.start()
    }

    /**
     * "Undo" tapped on the banner: loads the saved run off the main thread (see
     * refreshUndoBannerFromStorage() for why), then confirms and restores it.
     */
    private fun confirmUndoLastRun() {
        Thread {
            val lastRun = UndoManager.loadLastRun(this)
            runOnUiThread {
                if (lastRun != null) showUndoConfirmDialog(lastRun)
            }
        }.start()
    }

    /** Builds and shows the actual confirm dialog once `lastRun` is in hand. */
    private fun showUndoConfirmDialog(lastRun: List<UpdatedNumberRow>) {
        // Same custom-styled dialog as confirmFixSelected() — this theme renders
        // AlertDialog's default title/message identically otherwise.
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(4))
        }
        val titleView = TextView(this).apply {
            text = getString(R.string.undo_confirm_title)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.white))
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(10))
        }
        container.addView(titleView)
        val messageView = TextView(this).apply {
            text = getString(R.string.undo_confirm_message_format, lastRun.size)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.white))
            textSize = 14f
        }
        container.addView(messageView)

        AlertDialog.Builder(this)
            .setView(container)
            .setPositiveButton(R.string.undo) { _, _ ->
                runWithWriteContactsPermission { restoreLastRun(lastRun) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Writes every row's `oldNumber` back over its `newNumber` and, for whichever contacts
     * are still in `contacts` (should be all of them — this only reverses the immediately
     * preceding run), updates that Contact object in place too, exactly the way
     * applyResolvedNumber() does for a normal fix. That keeps the on-screen list correct
     * immediately, without needing a full re-scan of the device.
     */
    private fun restoreLastRun(rows: List<UpdatedNumberRow>) {
        setLoading(true, getString(R.string.undoing_update))
        Thread {
            var restored = 0
            for (row in rows) {
                if (ContactsAccessor.writeNumber(contentResolver, row.contactId, row.oldNumber)) {
                    restored++
                    contacts.find { it.id == row.contactId }?.let {
                        applyResolvedNumber(it, row.oldNumber)
                        // applyResolvedNumber() always leaves `selected = false` (right for a
                        // normal fix, since the contact usually isn't CHANGEABLE anymore
                        // afterward) — but undoing puts it back to CHANGEABLE, and a fresh
                        // scan selects every CHANGEABLE contact by default, so this should too.
                        it.selected = it.status == ContactStatus.CHANGEABLE
                    }
                }
            }
            runOnUiThread {
                UndoManager.clearLastRun(this)
                syncSelectAllCheckboxState()
                updateSummary()
                updateUndoBanner(null) // just cleared it above — no need to re-read
                setLoading(false)
                if (restored == rows.size) {
                    Toast.makeText(this, getString(R.string.undo_done_format, restored), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.undo_partial_format, restored, rows.size),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }
}