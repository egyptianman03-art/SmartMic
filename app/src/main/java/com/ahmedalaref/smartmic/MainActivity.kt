package com.ahmedalaref.smartmic

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.ahmedalaref.smartmic.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding          : ActivityMainBinding
    private lateinit var audioEngine      : AudioEngine
    private lateinit var btHandler        : BluetoothAudioHandler
    private lateinit var wavRecorder      : Mp3Recorder
    private lateinit var recordingsAdapter: RecordingsAdapter

    private val sysAudio by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) { initAudioComponents(); toast("Permissions granted ✓") }
        else showPermissionDialog()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupRecordingsList()
        setupButtonListeners()
        checkAndRequestPermissions()
        loadRecordings()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::audioEngine.isInitialized) audioEngine.cleanup()
        if (::btHandler.isInitialized)   btHandler.cleanup()
        recordingsAdapter.cleanup()
    }

    // ── Permissions ────────────────────────────────────────────────────────

    private fun checkAndRequestPermissions() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.MODIFY_AUDIO_SETTINGS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            else
                add(Manifest.permission.BLUETOOTH)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isEmpty()) initAudioComponents() else permLauncher.launch(needed.toTypedArray())
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("Smart Mic needs Microphone permission to work.")
            .setPositiveButton("Try again") { _, _ -> checkAndRequestPermissions() }
            .setNegativeButton("Continue anyway") { _, _ -> initAudioComponents() }
            .setNeutralButton("Exit") { _, _ -> finish() }
            .setCancelable(false).show()
    }

    // ── Initialisation ────────────────────────────────────────────────────

    private fun initAudioComponents() {
        audioEngine  = AudioEngine(this)
        btHandler    = BluetoothAudioHandler(this)
        wavRecorder  = Mp3Recorder(this)

        btHandler.initialize()

        btHandler.onScoStateChanged = { connected ->
            runOnUiThread {
                setStatus(if (connected) "🔵 Bluetooth headset active" else "Bluetooth disconnected")
            }
        }

        audioEngine.onAudioLevelChanged = { level -> binding.audioLevelView.setLevel(level) }
        audioEngine.onError = { msg -> runOnUiThread { showError(msg) } }

        binding.switchNoiseSuppression.setOnCheckedChangeListener { _, on ->
            if (::audioEngine.isInitialized) audioEngine.setNoiseSuppression(on)
        }
        binding.switchGainControl.setOnCheckedChangeListener { _, on ->
            if (::audioEngine.isInitialized) audioEngine.setAutoGainControl(on)
        }
    }

    // ── UI Setup ──────────────────────────────────────────────────────────

    private fun setupRecordingsList() {
        recordingsAdapter = RecordingsAdapter(mutableListOf()) { file ->
            AlertDialog.Builder(this)
                .setTitle("Delete recording?")
                .setMessage(file.name)
                .setPositiveButton("Delete") { _, _ ->
                    if (::wavRecorder.isInitialized) wavRecorder.deleteRecording(file)
                    loadRecordings()
                    toast("Recording deleted")
                }
                .setNegativeButton("Cancel", null).show()
        }
        binding.rvRecordings.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = recordingsAdapter
        }
    }

    private fun setupButtonListeners() {
        binding.rgInputSource.setOnCheckedChangeListener { _, id ->
            if (::audioEngine.isInitialized && audioEngine.isStreaming) applyInputSource(id)
        }
        binding.rgOutputDevice.setOnCheckedChangeListener { _, id ->
            if (::btHandler.isInitialized) applyOutputDevice(id)
        }
        binding.btnStream.setOnClickListener {
            if (!::audioEngine.isInitialized) { checkAndRequestPermissions(); return@setOnClickListener }
            if (audioEngine.isStreaming) stopStreaming() else startStreaming()
        }
        binding.btnRecord.setOnClickListener {
            if (!::audioEngine.isInitialized) return@setOnClickListener
            if (audioEngine.isRecording) stopRecording() else startRecording()
        }
        binding.switchPushToTalk.setOnCheckedChangeListener { _, checked -> configurePushToTalk(checked) }
        binding.btnRefresh.setOnClickListener { loadRecordings() }
    }

    // ── Streaming ─────────────────────────────────────────────────────────

    private fun startStreaming() {
        applyInputSource(binding.rgInputSource.checkedRadioButtonId)
        applyOutputDevice(binding.rgOutputDevice.checkedRadioButtonId)
        val ok = audioEngine.startStreaming()
        if (ok) {
            binding.btnStream.text = "⏹  Stop"
            binding.btnStream.setBackgroundColor(getColor(R.color.accent_red))
            binding.btnRecord.isEnabled = true
            setStatus("🎙️ Live monitoring active")
        } else {
            setStatus("Failed to start — check microphone")
        }
    }

    private fun stopStreaming() {
        if (audioEngine.isRecording) stopRecording()
        audioEngine.stopStreaming()
        btHandler.stopBluetoothSco()
        binding.btnStream.text = "▶  Start"
        binding.btnStream.setBackgroundColor(getColor(R.color.accent_blue))
        binding.btnRecord.apply {
            isEnabled = false; text = "⏺  Record"
            setBackgroundColor(getColor(R.color.accent_coral))
        }
        setStatus("Idle")
    }

    // ── Recording ─────────────────────────────────────────────────────────

    private fun startRecording() {
        val pcmFile = wavRecorder.createTempPcmFile()
        if (audioEngine.startRecording(pcmFile)) {
            binding.btnRecord.text = "⏹  Stop Rec"
            binding.btnRecord.setBackgroundColor(getColor(R.color.accent_red))
            setStatus("⏺ Recording in progress…")
        }
    }

    private fun stopRecording() {
        val pcmFile = audioEngine.stopRecording()
        binding.btnRecord.text = "⏺  Record"
        binding.btnRecord.setBackgroundColor(getColor(R.color.accent_coral))

        if (pcmFile != null && pcmFile.exists() && pcmFile.length() > 0) {
            setStatus("Saving recording…")
            wavRecorder.convertToWav(pcmFile, AudioEngine.SAMPLE_RATE, channels = 1) { saved ->
                runOnUiThread {
                    if (saved != null) {
                        toast("💾 Saved: ${saved.name}")
                        setStatus("Recording saved ✓")
                        loadRecordings()
                    } else {
                        showError("Failed to save recording")
                    }
                }
            }
        } else {
            setStatus("Recording too short — try again")
        }
    }

    // ── Routing ───────────────────────────────────────────────────────────

    private fun applyInputSource(radioId: Int) {
        when (radioId) {
            R.id.rbMic -> { btHandler.stopBluetoothSco(); sysAudio.mode = AudioManager.MODE_IN_COMMUNICATION }
            R.id.rbBluetooth -> {
                if (btHandler.isBluetoothHeadsetConnected()) btHandler.startBluetoothSco()
                else { showError("No Bluetooth headset connected"); binding.rgInputSource.check(R.id.rbMic) }
            }
            R.id.rbWired -> { btHandler.stopBluetoothSco(); sysAudio.mode = AudioManager.MODE_IN_COMMUNICATION }
        }
    }

    private fun applyOutputDevice(radioId: Int) {
        when (radioId) {
            R.id.rbSpeaker  -> btHandler.routeToSpeaker()
            R.id.rbEarpiece -> btHandler.routeToEarpiece()
            R.id.rbBtOut    -> {
                if (btHandler.isBluetoothHeadsetConnected()) btHandler.startBluetoothSco()
                else { showError("No Bluetooth headset connected"); binding.rgOutputDevice.check(R.id.rbSpeaker) }
            }
        }
    }

    // ── Push-to-Talk ──────────────────────────────────────────────────────

    @Suppress("ClickableViewAccessibility")
    private fun configurePushToTalk(enable: Boolean) {
        if (enable) {
            binding.btnStream.text = "🎙️  Hold to Talk"
            binding.btnStream.setBackgroundColor(getColor(R.color.accent_purple))
            binding.btnStream.setOnTouchListener { _, event ->
                if (!::audioEngine.isInitialized) return@setOnTouchListener false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> if (!audioEngine.isStreaming) startStreaming()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        if (audioEngine.isStreaming) stopStreaming()
                }
                true
            }
        } else {
            binding.btnStream.setOnTouchListener(null)
            binding.btnStream.text = if (::audioEngine.isInitialized && audioEngine.isStreaming) "⏹  Stop" else "▶  Start"
            binding.btnStream.setBackgroundColor(
                if (::audioEngine.isInitialized && audioEngine.isStreaming)
                    getColor(R.color.accent_red) else getColor(R.color.accent_blue)
            )
            binding.btnStream.setOnClickListener {
                if (!::audioEngine.isInitialized) { checkAndRequestPermissions(); return@setOnClickListener }
                if (audioEngine.isStreaming) stopStreaming() else startStreaming()
            }
        }
    }

    // ── Recordings List ───────────────────────────────────────────────────

    private fun loadRecordings() {
        val list = if (::wavRecorder.isInitialized) wavRecorder.getRecordings() else emptyList()
        recordingsAdapter.updateData(list)
        binding.tvNoRecordings.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.rvRecordings.visibility   = if (list.isEmpty()) View.GONE   else View.VISIBLE
    }

    // ── UI Helpers ────────────────────────────────────────────────────────

    private fun setStatus(msg: String) { binding.tvStatus.text = msg }
    private fun showError(msg: String) { toast(msg); setStatus("⚠ $msg") }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
