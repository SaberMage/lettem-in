package com.lettemin

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ProfilesActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private var profiles: List<Profile> = emptyList()
    private lateinit var adapter: ProfilesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profiles)
        listView = findViewById(R.id.list)
        emptyView = findViewById(R.id.emptyText)
        adapter = ProfilesAdapter()
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, pos, _ ->
            openEditor(profiles[pos].id)
        }
        findViewById<Button>(R.id.btnAdd).setOnClickListener {
            openEditor(null)
        }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        profiles = ProfileRepo.load(this)
        emptyView.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun openEditor(id: String?) {
        startActivity(Intent(this, ProfileEditActivity::class.java).apply {
            if (id != null) putExtra(ProfileEditActivity.EXTRA_ID, id)
        })
    }

    private inner class ProfilesAdapter : BaseAdapter() {
        override fun getCount() = profiles.size
        override fun getItem(position: Int): Any = profiles[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.row_profile, parent, false)
            val p = profiles[position]
            view.findViewById<TextView>(R.id.name).text = p.name
            val audio = p.audioFile ?: "—"
            view.findViewById<TextView>(R.id.subtitle).text =
                "${p.behavior.label} · ${p.contactKeys.size} contacts · $audio"
            return view
        }
    }
}
