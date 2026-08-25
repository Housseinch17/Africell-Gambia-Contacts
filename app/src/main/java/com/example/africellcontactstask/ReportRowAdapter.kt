package com.example.africellcontactstask

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Renders the "Updated numbers" table on the report screen — one row per UpdatedNumberRow. */
class ReportRowAdapter(private val rows: List<UpdatedNumberRow>) :
    RecyclerView.Adapter<ReportRowAdapter.RowViewHolder>() {

    class RowViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.rowContactName)
        val operator: TextView = view.findViewById(R.id.rowOperator)
        val oldNumber: TextView = view.findViewById(R.id.rowOldNumber)
        val newNumber: TextView = view.findViewById(R.id.rowNewNumber)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_report_row, parent, false)
        return RowViewHolder(view)
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        val row = rows[position]
        holder.name.text = row.name
        holder.operator.text = row.operator
        holder.oldNumber.text = row.oldNumber
        holder.newNumber.text = row.newNumber
    }
}
