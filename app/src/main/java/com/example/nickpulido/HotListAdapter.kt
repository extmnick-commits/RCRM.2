package com.nickpulido.rcrm

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

class HotListAdapter(
    private val context: Context,
    private val leads: List<Map<String, Any>>,
    private val onComplete: (String) -> Unit
) : BaseAdapter() {

    var currentTab: Int = 0

    override fun getCount(): Int = leads.size

    override fun getItem(position: Int): Any = leads[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        // Using a standard Android list item layout as a fallback
        val view = convertView ?: LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_2, parent, false)
        val lead = leads[position]
        
        val text1 = view.findViewById<TextView>(android.R.id.text1)
        val text2 = view.findViewById<TextView>(android.R.id.text2)
        
        text1.text = lead["name"] as? String ?: "Unknown Lead"
        text2.text = lead["phone"] as? String ?: "No Phone"
        
        return view
    }
}