package com.example.africellcontactstask

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.provider.ContactsContract
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
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
import com.example.africellcontactstask.add_testing.TestDataSeeder.removeTestContacts
import com.example.africellcontactstask.add_testing.TestDataSeeder.seedTestContacts

class MainActivity : AppCompatActivity() {
    private val contacts = mutableListOf<Contact>()
    private lateinit var adapter: ContactAdapter
    private lateinit var summaryText: TextView
    private lateinit var changeableActionsLayout: View
    private lateinit var selectAllCheckbox: CheckBox
    private lateinit var fixSelectedButton: Button

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                findViewById<View>(R.id.permissionLayout).visibility = View.GONE
                loadContacts()
            } else {
                Toast.makeText(
                    this,
                    "Contacts permission is required to run the migration.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    // WRITE_CONTACTS is requested lazily, right before the first thing that actually needs
    // to write (a test-data seed/remove, an "Apply fix", or a "Fix selected numbers") rather
    // than up front, so the permission prompt only appears when it's actually relevant.
    // `pendingWriteAction` holds whatever write was waiting on the permission so it can run
    // immediately once the user grants it, instead of silently failing and making them retry.
    private var pendingWriteAction: (() -> Unit)? = null

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
        summaryText = findViewById(R.id.summaryText)
        changeableActionsLayout = findViewById(R.id.changeableActionsLayout)
        selectAllCheckbox = findViewById(R.id.selectAllCheckbox)
        fixSelectedButton = findViewById(R.id.fixSelectedButton)

        bindSelectAllCheckbox()
        fixSelectedButton.setOnClickListener { confirmFixSelected() }

        val recyclerView = findViewById<RecyclerView>(R.id.contactsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = ContactAdapter(
            contacts,
            onApplyFixClicked = { contact, position -> showConfirmDialog(contact, position) },
            onKeepAsIsClicked = { contact, position -> keepAsIs(contact, position) },
            onSelectionToggled = { contact, _, isChecked ->
                onContactSelectionToggled(contact, isChecked)
            },
        )
        recyclerView.adapter = adapter

        findViewById<Button>(R.id.seedTestContactsButton).setOnClickListener {
            runWithWriteContactsPermission { seedTestContacts() }
        }
        findViewById<Button>(R.id.removeTestContactsButton).setOnClickListener {
            runWithWriteContactsPermission { removeTestContacts() }
        }

        findViewById<View>(R.id.grantPermissionButton).setOnClickListener {
            requestPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            findViewById<View>(R.id.permissionLayout).visibility = View.VISIBLE
        } else {
            loadContacts()
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
                    reason = result.reason
                )
            }

            runOnUiThread {
                contacts.clear()
                contacts.addAll(loaded)

                // Fresh data means fresh selection state.
                syncSelectAllCheckboxState()

                adapter.notifyDataSetChanged()
                updateSummary()
                setLoading(false)
            }
        }.start()
    }

    /** Shows/hides the loading spinner and toggles the contact list + selection bar accordingly. */
    private fun setLoading(loading: Boolean) {
        findViewById<View>(R.id.contactsProgressBar).visibility =
            if (loading) View.VISIBLE else View.GONE
        findViewById<View>(R.id.loadingText).visibility =
            if (loading) View.VISIBLE else View.GONE
        findViewById<RecyclerView>(R.id.contactsRecyclerView).visibility =
            if (loading) View.GONE else View.VISIBLE
        if (loading) {
            changeableActionsLayout.visibility = View.GONE
        }
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
        setLoading(true)
        Thread {
            val count = seedTestContacts(contentResolver)
            runOnUiThread {
                Toast.makeText(this, getString(R.string.seed_test_done, count), Toast.LENGTH_LONG)
                    .show()
                loadContacts() // reload (its own background thread) so the new test contacts show up, classified
            }
        }.start()
    }

    private fun removeTestContacts() {
        setLoading(true)
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
        summaryText.text =
            "Changeable: $changeable   Error: $error   Unchangeable: $unchangeable   Total: ${contacts.size}"

        changeableActionsLayout.visibility = if (changeable > 0) View.VISIBLE else View.GONE
        updateFixSelectedButton()
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
     * "Apply fix" action for one ERROR contact: shows WHY it was flagged (e.g. "Only 5
     * digits after +220 — not a recognized format...") right below the title, then lets
     * the user type the correct number in manually and save it.
     */
    private fun showConfirmDialog(contact: Contact, position: Int) {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }

        if (!contact.reason.isNullOrBlank()) {
            // A solid error-colored chip with white text, rather than gray text on
            // whatever the dialog's own background happens to be — guarantees strong
            // contrast (and reads clearly as "this is why it's flagged") regardless of
            // light/dark theme.
            val reasonView = TextView(this).apply {
                text = contact.reason
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.white))
                setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.status_error_bg))
                textSize = 13f
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            val reasonMargin = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            container.addView(reasonView, reasonMargin)
        }

        val input = EditText(this)
        input.setText(contact.suggestedNumber ?: contact.phoneNumber)
        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_title))
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val newNumber = input.text.toString().trim()
                runWithWriteContactsPermission {
                    if (writeNumberToContact(contact.id, newNumber)) {
                        applyResolvedNumber(contact, position, newNumber)
                    } else {
                        Toast.makeText(this, "Could not update this contact.", Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** "Keep as is" action for an ERROR contact: no change to the number, just marks it as reviewed. */
    private fun keepAsIs(contact: Contact, position: Int) {
        contact.resolved = true
        adapter.updateItem(position, contact)
        updateSummary()
    }

    /** "Fix selected numbers": confirm, then apply every checked CHANGEABLE contact's suggestion in one go. */
    private fun confirmFixSelected() {
        val pending = contacts.withIndex()
            .filter { (_, c) -> c.status == ContactStatus.CHANGEABLE && c.selected }

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
     * jank/ANR for a large selection) and only touches the adapter/UI once, back on the
     * main thread, after all the I/O is done.
     */
    private fun runFixSelected(pending: List<IndexedValue<Contact>>) {
        setLoading(true)
        Thread {
            val successful = mutableListOf<Pair<IndexedValue<Contact>, String>>()
            for (indexed in pending) {
                val contact = indexed.value
                val newNumber = contact.suggestedNumber ?: continue
                if (writeNumberToContact(contact.id, newNumber)) {
                    successful.add(indexed to newNumber)
                }
            }

            runOnUiThread {
                for ((indexed, newNumber) in successful) {
                    applyResolvedNumber(indexed.value, indexed.index, newNumber)
                }
                syncSelectAllCheckboxState()
                Toast.makeText(
                    this,
                    getString(R.string.fix_selected_done, successful.size),
                    Toast.LENGTH_LONG
                ).show()
                updateSummary()
                setLoading(false)
            }
        }.start()
    }

    /**
     * Shared bookkeeping after a number has actually been written back to Contacts.
     *
     * `resolved` is only set once the new number is no longer classified as ERROR — if a
     * manually-typed fix is still ambiguous, the contact stays unresolved so "Apply fix" /
     * "Keep as is" remain visible and the user can correct it again, instead of the row
     * silently losing its action buttons while still flagged as an error.
     */
    private fun applyResolvedNumber(contact: Contact, position: Int, newNumber: String) {
        contact.phoneNumber = newNumber
        val result = PhoneValidator.evaluate(newNumber)
        contact.status = result.status
        contact.suggestedNumber = result.suggestedNumber
        contact.reason = result.reason
        contact.resolved = result.status != ContactStatus.ERROR
        contact.selected = false
        adapter.updateItem(position, contact)
        updateSummary()
    }

    /** Writes the corrected number back to the phone's contact using ContactsContract. */
    private fun writeNumberToContact(rawContactDataId: String, newNumber: String): Boolean {
        return try {
            val values = ContentValues().apply {
                put(ContactsContract.CommonDataKinds.Phone.NUMBER, newNumber)
            }
            val rowsUpdated = contentResolver.update(
                ContactsContract.Data.CONTENT_URI,
                values,
                "${ContactsContract.CommonDataKinds.Phone._ID} = ?",
                arrayOf(rawContactDataId)
            )
            rowsUpdated > 0
        } catch (e: SecurityException) {
            // Shouldn't happen in practice: every call site routes through
            // runWithWriteContactsPermission first, which ensures WRITE_CONTACTS is
            // granted before this function is ever called.
            false
        }
    }
}