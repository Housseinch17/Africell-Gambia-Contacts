package com.example.africellcontactstask

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class ContactAdapter(
    private val items: MutableList<Contact>,
    private val onApplyFixClicked: (Contact, Int) -> Unit,
    private val onKeepAsIsClicked: (Contact, Int) -> Unit,
    private val onSelectionToggled: (Contact, Int, Boolean) -> Unit
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
        holder.selectCheckbox.visibility = View.GONE
        holder.selectCheckbox.setOnCheckedChangeListener(null)

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
                    onSelectionToggled(contact, holder.bindingAdapterPosition, isChecked)
                }
            }
            ContactStatus.ERROR -> {
                holder.badge.text = context.getString(R.string.status_error)
                holder.badge.setBackgroundColor(ContextCompat.getColor(context, R.color.status_error_bg))
                contact.reason?.let {
                    holder.reason.visibility = View.VISIBLE
                    holder.reason.text = it
                }
                // Once resolved (fix applied or "kept as is"), hide the action buttons.
                // (Step 4: apply the change for CHANGEABLE contacts.)
                holder.updateActions.visibility = if (contact.resolved) View.GONE else View.VISIBLE
            }
            ContactStatus.UNCHANGEABLE -> {
                holder.badge.text = context.getString(R.string.status_unchangeable)
                holder.badge.setBackgroundColor(ContextCompat.getColor(context, R.color.status_unchangeable_bg))
                contact.reason?.let {
                    holder.reason.visibility = View.VISIBLE
                    holder.reason.text = it
                }
            }
        }

        holder.applyFixButton.setOnClickListener { onApplyFixClicked(contact, holder.bindingAdapterPosition) }
        holder.keepAsIsButton.setOnClickListener { onKeepAsIsClicked(contact, holder.bindingAdapterPosition) }
    }
}
