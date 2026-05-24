package com.lettemin

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections

class ProfilesActivity : AppCompatActivity() {

    private lateinit var listView: RecyclerView
    private lateinit var emptyView: TextView
    private val items = mutableListOf<Profile>()
    private lateinit var adapter: ProfilesAdapter
    private lateinit var touchHelper: ItemTouchHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profiles)
        listView = findViewById(R.id.list)
        emptyView = findViewById(R.id.emptyText)
        listView.layoutManager = LinearLayoutManager(this)
        adapter = ProfilesAdapter()
        listView.adapter = adapter
        touchHelper = ItemTouchHelper(DragCallback())
        touchHelper.attachToRecyclerView(listView)
        findViewById<Button>(R.id.btnAdd).setOnClickListener { openEditor(null) }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        items.clear()
        items.addAll(ProfileRepo.load(this))
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun openEditor(id: String?) {
        startActivity(Intent(this, ProfileEditActivity::class.java).apply {
            if (id != null) putExtra(ProfileEditActivity.EXTRA_ID, id)
        })
    }

    private fun persistOrder() = ProfileRepo.reorder(this, items.toList())

    // ---------------- Adapter ----------------

    private inner class ProfilesAdapter : RecyclerView.Adapter<RowVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.row_profile, parent, false)
            return RowVH(v)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(h: RowVH, position: Int) {
            val p = items[position]
            h.name.text = p.name
            val audio = p.audioFile ?: "—"
            h.subtitle.text = "${p.behavior.label} · ${p.contactKeys.size} contacts · $audio"
            h.itemView.setOnClickListener { openEditor(p.id) }
            h.dragHandle.setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) touchHelper.startDrag(h)
                false
            }
        }
        override fun getItemCount() = items.size
    }

    private class RowVH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.name)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
        val dragHandle: ImageView = v.findViewById(R.id.dragHandle)
    }

    // ---------------- Drag ----------------

    private inner class DragCallback : ItemTouchHelper.Callback() {
        override fun isLongPressDragEnabled() = false  // we trigger via the handle's onTouch
        override fun isItemViewSwipeEnabled() = false

        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder) =
            makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)

        override fun onMove(
            rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
        ): Boolean {
            val from = vh.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            Collections.swap(items, from, to)
            adapter.notifyItemMoved(from, to)
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            persistOrder()
        }
    }
}
