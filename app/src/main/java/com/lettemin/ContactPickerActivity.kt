package com.lettemin

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Multi-select contact picker. Includes two pseudo-entries (Anonymous, Likely Spam)
 * at the top of the list.
 *
 *  Input  : EXTRA_PROFILE_ID (String?, the profile being edited; used to pre-check)
 *           EXTRA_SELECTED   (String[],  current selection from edit activity, used
 *                             to override repo state if the user has been editing)
 *  Output : EXTRA_SELECTED   (String[],  new selection)
 */
class ContactPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_SELECTED = "selected"   // String[] of contact keys
    }

    private data class Row(val key: String, val label: String)

    private val rows = mutableListOf<Row>()
    private val filtered = mutableListOf<Row>()
    private val selected = mutableSetOf<String>()
    private lateinit var adapter: Adapter
    private lateinit var list: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact_picker)
        list = findViewById(R.id.list)
        adapter = Adapter()
        list.adapter = adapter

        intent.getStringArrayExtra(EXTRA_SELECTED)?.let { selected.addAll(it) }

        loadAll()
        filter("")

        findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        list.setOnItemClickListener { _, _, pos, _ ->
            val row = filtered[pos]
            if (selected.contains(row.key)) selected.remove(row.key) else selected.add(row.key)
            adapter.notifyDataSetChanged()
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            setResult(RESULT_OK, intent.apply {
                putExtra(EXTRA_SELECTED, selected.toTypedArray())
            })
            finish()
        }
    }

    private fun loadAll() {
        rows.add(Row(Profile.KEY_ANONYMOUS, getString(R.string.anonymous_caller)))
        rows.add(Row(Profile.KEY_SPAM, getString(R.string.likely_spam)))

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) return

        contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            "${ContactsContract.Contacts.HAS_PHONE_NUMBER} = ?",
            arrayOf("1"),
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC"
        )?.use { c ->
            val idxKey = c.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY)
            val idxName = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            val seen = HashSet<String>()
            while (c.moveToNext()) {
                val key = c.getString(idxKey) ?: continue
                if (!seen.add(key)) continue
                val name = c.getString(idxName) ?: key
                rows.add(Row(key, name))
            }
        }
    }

    private fun filter(q: String) {
        filtered.clear()
        if (q.isBlank()) {
            filtered.addAll(rows)
        } else {
            val ql = q.lowercase()
            filtered.addAll(rows.filter { it.label.lowercase().contains(ql) })
        }
        adapter.notifyDataSetChanged()
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = filtered.size
        override fun getItem(position: Int) = filtered[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.row_contact, parent, false)
            val row = filtered[position]
            val tv = view.findViewById<CheckedTextView>(android.R.id.text1)
            tv.text = row.label
            tv.isChecked = selected.contains(row.key)
            return view
        }
    }
}
