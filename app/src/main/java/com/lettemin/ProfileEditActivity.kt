package com.lettemin

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class ProfileEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "id"
    }

    private val behaviors = Behavior.values().toList()
    private var profile: Profile = Profile(
        id = ProfileRepo.newId(),
        name = "",
        behavior = Behavior.AUDIO_AND_DTMF,
        audioFile = null,
        contactKeys = emptySet()
    )

    private lateinit var nameInput: EditText
    private lateinit var behaviorSpinner: Spinner
    private lateinit var audioLabel: TextView
    private lateinit var contactsLabel: TextView

    private var teensy: TeensyBridge? = null

    private val pickAudioLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) handleAudioPicked(uri)
    }

    private val pickContactsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val arr = result.data?.getStringArrayExtra(ContactPickerActivity.EXTRA_SELECTED)
                ?: return@registerForActivityResult
            profile = profile.copy(contactKeys = arr.toSet())
            renderContacts()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_edit)

        nameInput = findViewById(R.id.nameInput)
        behaviorSpinner = findViewById(R.id.behaviorSpinner)
        audioLabel = findViewById(R.id.audioLabel)
        contactsLabel = findViewById(R.id.contactsLabel)

        behaviorSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            behaviors.map { it.label }
        )

        intent.getStringExtra(EXTRA_ID)?.let { id ->
            ProfileRepo.load(this).firstOrNull { it.id == id }?.let { profile = it }
        }

        nameInput.setText(profile.name)
        behaviorSpinner.setSelection(behaviors.indexOf(profile.behavior).coerceAtLeast(0))
        renderAudio()
        renderContacts()

        findViewById<Button>(R.id.btnPickAudio).setOnClickListener {
            pickAudioLauncher.launch(arrayOf("audio/*"))
        }
        findViewById<Button>(R.id.btnPickContacts).setOnClickListener {
            val i = Intent(this, ContactPickerActivity::class.java).apply {
                putExtra(ContactPickerActivity.EXTRA_PROFILE_ID, profile.id)
                putExtra(ContactPickerActivity.EXTRA_SELECTED, profile.contactKeys.toTypedArray())
            }
            pickContactsLauncher.launch(i)
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { onSave() }
        findViewById<Button>(R.id.btnDelete).setOnClickListener { onDelete() }
    }

    override fun onDestroy() {
        teensy?.close()
        super.onDestroy()
    }

    private fun renderAudio() {
        audioLabel.text = profile.audioFile?.let {
            getString(R.string.audio_file_label, it)
        } ?: getString(R.string.audio_file_none)
    }

    private fun renderContacts() {
        contactsLabel.text = if (profile.contactKeys.isEmpty())
            getString(R.string.contacts_count_zero)
        else
            getString(R.string.contacts_count, profile.contactKeys.size)
    }

    private fun handleAudioPicked(uri: Uri) {
        if (!AppState.teensyAttached) {
            Toast.makeText(this, R.string.need_teensy_to_upload, Toast.LENGTH_LONG).show()
            return
        }
        val progress = AlertDialog.Builder(this)
            .setMessage(R.string.uploading_audio)
            .setCancelable(false)
            .show()
        Thread {
            val ok = try {
                val wav = WavConverter.convert(this, uri)
                val target = filenameFor(profile.id)
                val bridge = teensy ?: TeensyBridge(this).also { teensy = it }
                bridge.open()
                bridge.uploadFile(target, wav)
            } catch (e: Throwable) {
                android.util.Log.e("LettemIn", "audio upload failed", e); false
            }
            Handler(Looper.getMainLooper()).post {
                progress.dismiss()
                if (ok) {
                    profile = profile.copy(audioFile = filenameFor(profile.id))
                    renderAudio()
                    Toast.makeText(this, R.string.upload_ok, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.upload_fail, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** Stable filename per profile so re-uploads overwrite. 8.3 friendly. */
    private fun filenameFor(profileId: String): String {
        val short = profileId.replace("-", "").take(8).lowercase()
        return "p_$short.wav"
    }

    private fun onSave() {
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.need_name, Toast.LENGTH_SHORT).show(); return
        }
        val behavior = behaviors[behaviorSpinner.selectedItemPosition]
        if ((behavior == Behavior.AUDIO || behavior == Behavior.AUDIO_AND_DTMF)
            && profile.audioFile == null) {
            Toast.makeText(this, R.string.need_audio_file, Toast.LENGTH_LONG).show(); return
        }
        val toSave = profile.copy(name = name, behavior = behavior)
        ProfileRepo.upsert(this, toSave)
        finish()
    }

    private fun onDelete() {
        ProfileRepo.delete(this, profile.id)
        finish()
    }
}
