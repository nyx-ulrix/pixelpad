package com.pixelpad.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/** The Profiles picker on the controller screen: load a layout kept on the PC, or save the one you have made. */
object ProfilesUi {
    private val INK = 0xFF2F6FE0.toInt(); private val PAPER = 0xFFF7FBFF.toInt()

    /**
     * template and layout() describe the layout on screen now (for saving); apply(profile) puts a profile on screen.
     * The list shown first is the last one the PC sent, then it is refreshed.
     */
    fun picker(a: Activity, template: String, layout: () -> String, apply: (RemoteProfile) -> Unit) {
        var items: List<Pair<Int, String>> = ProfileCache.list()
        val labels = ArrayList<String>()
        val adapter = object : ArrayAdapter<String>(a, android.R.layout.simple_list_item_1, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = ((convertView as? TextView) ?: TextView(a)).apply {
                text = labels[position]; typeface = Typeface.MONOSPACE; setTextColor(INK); textSize = 13f; gravity = Gravity.CENTER_VERTICAL
                setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12)); setBackgroundColor(if (position == 0) 0xFFB8E986.toInt() else PAPER)
            }
        }
        fun rebuild() {
            labels.clear(); labels.add("+ SAVE THIS LAYOUT AS A NEW PROFILE")
            if (items.isEmpty()) labels.add("(NO PROFILES YET)") else items.forEach { labels.add(it.second.uppercase()) }
            adapter.notifyDataSetChanged()
        }
        rebuild()
        val list = ListView(a).apply { this.adapter = adapter; setBackgroundColor(PAPER) }
        val dlg = AlertDialog.Builder(a).setTitle("PROFILES (KEPT ON THE PC)").setView(list).setNegativeButton("CLOSE", null).create()
        list.setOnItemClickListener { _, _, pos, _ ->
            if (pos == 0) { dlg.dismiss(); save(a, template, layout, items) { items = it } }
            else items.getOrNull(pos - 1)?.let { (id, name) ->
                dlg.dismiss()
                Core.profile(id) { p -> if (p != null) { apply(p); Toast.makeText(a, "PROFILE: ${p.name.uppercase()}", Toast.LENGTH_SHORT).show() } else Toast.makeText(a, "COULDN'T LOAD $name: IS THE PC CONNECTED?", Toast.LENGTH_LONG).show() }
            }
        }
        dlg.show()
        Core.profiles.list { r -> if (r != null) { ProfileCache.saveList(r); items = r; rebuild() } }
    }

    private fun save(a: Activity, template: String, layout: () -> String, known: List<Pair<Int, String>>, remember: (List<Pair<Int, String>>) -> Unit) {
        val name = android.widget.EditText(a).apply {
            typeface = Typeface.MONOSPACE; setTextColor(INK); textSize = 14f; setSingleLine(); hint = "NAME, E.G. FPS"; setBackgroundColor(PAPER); setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8)); alwaysKeyboard()
        }
        val box = android.widget.LinearLayout(a).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(8)); setBackgroundColor(PAPER); addView(name) }
        AlertDialog.Builder(a).setTitle("SAVE ON THE PC").setView(box).setPositiveButton("SAVE") { _, _ ->
            val n = name.text.toString().trim().ifEmpty { "PROFILE" }
            val same = known.firstOrNull { it.second.equals(n, true) }?.first ?: 0   // the same name saves over that profile
            Core.profiles.put(n, template, layout(), same) { id ->
                if (id > 0) {
                    Toast.makeText(a, if (same > 0) "UPDATED ON THE PC" else "SAVED ON THE PC: EVERY CONNECTED DEVICE CAN USE IT", Toast.LENGTH_LONG).show()
                    Core.profiles.list { r -> if (r != null) { ProfileCache.saveList(r); remember(r) } }
                } else Toast.makeText(a, "COULDN'T SAVE: IS THE PC CONNECTED? (NEEDS PIXELPAD DESK 1.4.0 OR NEWER)", Toast.LENGTH_LONG).show()
            }
        }.setNegativeButton("CANCEL", null).show()
    }

    private fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
