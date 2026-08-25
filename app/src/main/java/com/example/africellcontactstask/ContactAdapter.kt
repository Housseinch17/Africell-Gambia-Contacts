package com.example.africellcontactstask

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class ContactAdapter(
    private val items: MutableList<Contact>,
    private val onSaveNumberClicked: (Contact, String) -> Unit,
    private val onKeepAsIsClicked: (Contact) -> Unit,
    private val onSelectionToggled: (Contact, Boolean) -> Unit
) : RecyclerView.Adapter<ContactAdapter.ContactViewHolder>() {

    class ContactViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.contactName)
        val number: TextView = view.findViewById(R.id.contactNumber)
        val suggested: TextView = view.findViewById(R.id.suggestedNumber)
        val reason: TextView = view.findViewById(R.id.reasonText)
        val badge: TextView = view.findViewById(R.id.statusBadge)
        val updateActions: View = view.findViewById(R.id.updateActionsLayout)
        val applyFixButton: View = view.findViewById(R.id.applyFixButton)
        val keepAsIsButton: View = view.findViewById(R.id.keepAsIsButton)
        val selectCheckbox: CheckBox = view.findViewById(R.id.selectCheckbox)

        // Inline "Fix number" edit state — replaces updateActions in place on the same card.
        val editLayout: View = view.findViewById(R.id.editLayout)
        val editNumberInput: EditText = view.findViewById(R.id.editNumberInput)
        val clearEditNumberButton: View = view.findViewById(R.id.clearEditNumberButton)
        val editLiveReasonText: TextView = view.findViewById(R.id.editLiveReasonText)
        val cancelEditButton: View = view.findViewById(R.id.cancelEditButton)
        val saveEditButton: View = view.findViewById(R.id.saveEditButton)

        // Tracks the TextWatcher currently attached to editNumberInput so it can be removed
        // before a recycled view is rebound to a different contact — otherwise old watchers
        // would pile up and fire for the wrong contact.
        var editTextWatcher: TextWatcher? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_contact, parent, false)
        return ContactViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        val contact = items[position]
        val context = holder.itemView.context

        holder.name.text = contact.name
        holder.number.text = contact.carrier?.let { carrier ->
            context.getString(R.string.number_with_carrier_format, contact.phoneNumber, carrier)
        } ?: contact.phoneNumber
        holder.suggested.visibility = View.GONE
        holder.reason.visibility = View.GONE
        holder.updateActions.visibility = View.GONE
        holder.editLayout.visibility = View.GONE
        holder.selectCheckbox.visibility = View.GONE
        holder.selectCheckbox.setOnCheckedChangeListener(null)
        holder.editTextWatcher?.let { holder.editNumberInput.removeTextChangedListener(it) }
        holder.editTextWatcher = null

        when (contact.status) {
            ContactStatus.CHANGEABLE -> {
                holder.badge.text = context.getString(R.string.status_changeable)
                holder.badge.setBackgroundColor(ContextCompat.getColor(context, R.color.status_changeable_bg))
                if (!contact.resolved && contact.suggestedNumber != null) {
                    holder.suggested.visibility = View.VISIBLE
                    holder.suggested.text = context.getString(R.string.suggested_number_format, contact.suggestedNumber)
                }
                holder.selectCheckbox.visibility = View.VISIBLE
                holder.selectCheckbox.isChecked = contact.selected
                holder.selectCheckbox.setOnCheckedChangeListener { _, isChecked ->
                    onSelectionToggled(contact, isChecked)
                }
            }
            ContactStatus.ERROR -> {
                holder.badge.text = context.getString(R.string.status_error)
                holder.badge.setBackgroundColor(ContextCompat.getColor(context, R.color.status_error_bg))
                contact.reason?.let {
                    holder.reason.visibility = View.VISIBLE
                    holder.reason.text = it
                    holder.reason.setTextColor(ContextCompat.getColor(context, R.color.warning_amber))
                    holder.reason.setTypeface(holder.reason.typeface, Typeface.NORMAL)
                }

                when {
                    contact.resolved -> {
                        // Fix applied or "kept as is" — no more actions needed on this card.
                        holder.updateActions.visibility = View.GONE
                        holder.editLayout.visibility = View.GONE
                    }
                    contact.isEditing -> {
                        // "Fix Number" was tapped: show the inline editable field instead of
                        // the Leave As Is / Fix Number row, on this same card, no dialog.
                        holder.editLayout.visibility = View.VISIBLE
                        bindEditState(holder, contact, context)
                    }
                    else -> {
                        holder.updateActions.visibility = View.VISIBLE
                        holder.applyFixButton.setOnClickListener {
                            contact.isEditing = true
                            notifyItemChanged(holder.bindingAdapterPosition)
                        }
                        holder.keepAsIsButton.setOnClickListener { onKeepAsIsClicked(contact) }
                    }
                }
            }
            ContactStatus.UNCHANGEABLE -> {
                holder.badge.text = context.getString(R.string.status_unchangeable)
                holder.badge.setBackgroundColor(ContextCompat.getColor(context, R.color.status_unchangeable_bg))
                contact.reason?.let {
                    holder.reason.visibility = View.VISIBLE
                    holder.reason.text = it
                    holder.reason.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    holder.reason.setTypeface(holder.reason.typeface, Typeface.ITALIC)
                }
            }
        }
    }

    /**
     * Wires up the inline edit card: pre-fills the field with the current/suggested number,
     * re-runs PhoneValidator on every keystroke to show a live result below the field (so the
     * user sees whether their edit resolves the issue before saving), and hooks up the clear
     * (X), Cancel, and Save controls.
     */
    private fun bindEditState(holder: ContactViewHolder, contact: Contact, context: Context) {
        holder.editNumberInput.setText(contact.suggestedNumber ?: contact.phoneNumber)
        holder.editNumberInput.setSelection(holder.editNumberInput.text.length)

        fun updateLiveReason(text: String) {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                holder.editLiveReasonText.text = ""
                return
            }
            val result = PhoneValidator.evaluate(trimmed)
            val (label, colorRes) = when (result.status) {
                ContactStatus.CHANGEABLE -> {
                    val carrierSuffix = result.carrierName?.let { " ($it)" } ?: ""
                    "✓ Will update to ${result.suggestedNumber}$carrierSuffix" to R.color.status_changeable_bg
                }
                ContactStatus.ERROR ->
                    (result.reason ?: "Still not a recognized format.") to R.color.status_error_bg
                ContactStatus.UNCHANGEABLE ->
                    (result.reason ?: "No change needed.") to R.color.status_unchangeable_bg
            }
            holder.editLiveReasonText.text = label
            holder.editLiveReasonText.setTextColor(ContextCompat.getColor(context, colorRes))
        }
        updateLiveReason(holder.editNumberInput.text.toString())

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updateLiveReason(s?.toString().orEmpty())
        }
        holder.editNumberInput.addTextChangedListener(watcher)
        holder.editTextWatcher = watcher

        holder.clearEditNumberButton.setOnClickListener {
            holder.editNumberInput.setText("")
        }
        holder.cancelEditButton.setOnClickListener {
            contact.isEditing = false
            notifyItemChanged(holder.bindingAdapterPosition)
        }
        holder.saveEditButton.setOnClickListener {
            val newNumber = holder.editNumberInput.text.toString().trim()
            onSaveNumberClicked(contact, newNumber)
        }
    }
}
