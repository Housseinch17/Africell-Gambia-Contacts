package com.example.africellcontactstask

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
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

    // --- TESTING ONLY: seeding/removing fake contacts needs WRITE_CONTACTS up front,
    // requested separately from the READ_CONTACTS flow above. Remove along with
    // TestDataSeeder.kt and its two buttons before shipping. ---
    private var pendingTestAction: (() -> Unit)? = null

    private val writeContactsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                pendingTestAction?.invoke()
            } else {
                Toast.makeText(
                    this,
                    "Contacts write permission is required for test data.",
                    Toast.LENGTH_LONG
                ).show()
            }
            pendingTestAction = null
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

                // Fresh data means fresh selection state — reset "select all" without
                // firing its listener (which would try to mutate the new list mid-reset).
                selectAllCheckbox.setOnCheckedChangeListener(null)
                selectAllCheckbox.isChecked = false
                bindSelectAllCheckbox()

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

    // --- TESTING ONLY below: seeds/removes the curated TestDataSeeder.TEST_CONTACTS set ---

    private fun runWithWriteContactsPermission(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingTestAction = action
            writeContactsPermissionLauncher.launch(Manifest.permission.WRITE_CONTACTS)
        }
    }

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
        updateFixSelectedButton()
    }

    /** Keeps the "Fix selected (N)" button label in sync with how many are currently checked. */
    private fun updateFixSelectedButton() {
        val selectedCount = changeableContacts().count { it.selected }
        fixSelectedButton.text = getString(R.string.fix_selected_format, selectedCount)
    }

    /** "Apply fix" action for one ERROR contact: type the correct number in manually and save it. */
    private fun showConfirmDialog(contact: Contact, position: Int) {
        val input = EditText(this)
        input.setText(contact.suggestedNumber ?: contact.phoneNumber)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_title))
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val newNumber = input.text.toString().trim()
                if (writeNumberToContact(contact.id, newNumber)) {
                    applyResolvedNumber(contact, position, newNumber)
                } else {
                    Toast.makeText(this, "Could not update this contact.", Toast.LENGTH_SHORT)
                        .show()
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

        AlertDialog.Builder(this)
            .setTitle(R.string.fix_selected_confirm_title)
            .setMessage(R.string.fix_selected_confirm_message)
            .setPositiveButton(R.string.save) { _, _ -> runFixSelected(pending) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runFixSelected(pending: List<IndexedValue<Contact>>) {
        var updated = 0
        for ((position, contact) in pending) {
            val newNumber = contact.suggestedNumber ?: continue
            if (writeNumberToContact(contact.id, newNumber)) {
                applyResolvedNumber(contact, position, newNumber)
                updated++
            }
        }

        // Reset "select all" now that its selected contacts have been resolved.
        selectAllCheckbox.setOnCheckedChangeListener(null)
        selectAllCheckbox.isChecked = false
        bindSelectAllCheckbox()

        Toast.makeText(this, getString(R.string.fix_selected_done, updated), Toast.LENGTH_LONG)
            .show()
        updateSummary()
    }

    /** Shared bookkeeping after a number has actually been written back to Contacts. */
    private fun applyResolvedNumber(contact: Contact, position: Int, newNumber: String) {
        contact.phoneNumber = newNumber
        val result = PhoneValidator.evaluate(newNumber)
        contact.status = result.status
        contact.suggestedNumber = result.suggestedNumber
        contact.reason = result.reason
        contact.resolved = true
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
            // WRITE_CONTACTS permission not granted
            requestPermissionLauncher.launch(Manifest.permission.WRITE_CONTACTS)
            false
        }
    }
}