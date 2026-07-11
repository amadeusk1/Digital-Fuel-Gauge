package com.fuelcheck

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fuelcheck.databinding.ActivityLogBinding
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }

        binding.logList.layoutManager = LinearLayoutManager(this)
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val entries = FuelLogStore.load(FuelLogStore.prefs(this))
        if (entries.isEmpty()) {
            binding.emptyMessage.visibility = View.VISIBLE
            binding.logList.visibility = View.GONE
        } else {
            binding.emptyMessage.visibility = View.GONE
            binding.logList.visibility = View.VISIBLE
            binding.logList.adapter = LogAdapter(entries)
        }
    }

    private class LogAdapter(
        private val entries: List<FuelLogEntry>
    ) : RecyclerView.Adapter<LogAdapter.Holder>() {

        private val dateFormat: DateFormat =
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val entryType: TextView = view.findViewById(R.id.entryType)
            val entryDate: TextView = view.findViewById(R.id.entryDate)
            val entryFuelAdded: TextView = view.findViewById(R.id.entryFuelAdded)
            val entryOdometer: TextView = view.findViewById(R.id.entryOdometer)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_log_entry, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = entries[position]
            val context = holder.itemView.context
            holder.entryType.text = if (entry.isFullTank) {
                context.getString(R.string.log_type_full)
            } else {
                context.getString(R.string.log_type_partial)
            }
            holder.entryDate.text = dateFormat.format(Date(entry.timestampMs))
            holder.entryFuelAdded.text = context.getString(
                R.string.log_value_fuel_added,
                entry.litersAdded
            )
            holder.entryOdometer.text = context.getString(
                R.string.log_value_odometer,
                entry.odometerKm
            )
        }

        override fun getItemCount(): Int = entries.size
    }
}
