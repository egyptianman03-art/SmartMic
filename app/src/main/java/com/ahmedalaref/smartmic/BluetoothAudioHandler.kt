package com.ahmedalaref.smartmic

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build

/**
 * BluetoothAudioHandler
 *
 * Manages Bluetooth SCO audio routing for Smart Mic.
 * – Tracks headset connection state via BluetoothProfile.ServiceListener
 * – Monitors SCO audio state via broadcast receiver
 * – Provides clean start / stop helpers that wrap AudioManager SCO calls
 *
 * Usage:
 *   val bth = BluetoothAudioHandler(context)
 *   bth.initialize()
 *   bth.startBluetoothSco()   // routes mic + speaker through BT headset
 *   bth.stopBluetoothSco()    // reverts to built-in mic / speaker
 *   bth.cleanup()             // call from onDestroy
 */
class BluetoothAudioHandler(private val context: Context) {

    // ─── Android system services ──────────────────────────────────────────────
    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    // ─── Bluetooth headset profile proxy ─────────────────────────────────────
    private var bluetoothHeadset: BluetoothHeadset? = null

    // ─── Public callbacks (dispatched on the calling thread) ─────────────────
    /**
     * Invoked when SCO audio actually connects (true) or disconnects (false).
     * Update your UI here.
     */
    var onScoStateChanged: ((connected: Boolean) -> Unit)? = null

    // ─── SCO broadcast receiver ───────────────────────────────────────────────
    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
            val state = intent.getIntExtra(
                AudioManager.EXTRA_SCO_AUDIO_STATE,
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED
            )
            when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    audioManager.isBluetoothScoOn = true
                    onScoStateChanged?.invoke(true)
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    audioManager.isBluetoothScoOn = false
                    onScoStateChanged?.invoke(false)
                }
            }
        }
    }

    // ─── Headset profile listener ─────────────────────────────────────────────
    private val headsetListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HEADSET) {
                bluetoothHeadset = proxy as BluetoothHeadset
            }
        }
        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HEADSET) bluetoothHeadset = null
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    /** Call once from Activity.onCreate() (after permissions are granted). */
    fun initialize() {
        context.registerReceiver(
            scoReceiver,
            IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        )
        bluetoothAdapter?.getProfileProxy(context, headsetListener, BluetoothProfile.HEADSET)
    }

    /** Call from Activity.onDestroy(). */
    fun cleanup() {
        runCatching { context.unregisterReceiver(scoReceiver) }
        bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, bluetoothHeadset)
        bluetoothHeadset = null
    }

    // ─── Routing helpers ──────────────────────────────────────────────────────

    /**
     * Start SCO connection → routes both mic input and speaker output through
     * the connected Bluetooth headset.
     */
    fun startBluetoothSco() {
        if (bluetoothAdapter == null || !isBluetoothHeadsetConnected()) return
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true
    }

    /** Disconnect SCO and reset audio mode to normal communication. */
    fun stopBluetoothSco() {
        audioManager.stopBluetoothSco()
        audioManager.isBluetoothScoOn = false
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    /** Route output to the loud speaker (mic remains at default / wired). */
    fun routeToSpeaker() {
        audioManager.mode                = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn    = true
        audioManager.isBluetoothScoOn   = false
    }

    /** Route output to the earpiece (near-ear speaker). */
    fun routeToEarpiece() {
        audioManager.mode                = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn    = false
        audioManager.isBluetoothScoOn   = false
    }

    // ─── State queries ────────────────────────────────────────────────────────

    /** True when at least one paired Bluetooth headset is connected. */
    fun isBluetoothHeadsetConnected(): Boolean =
        bluetoothHeadset?.connectedDevices?.isNotEmpty() == true

    /** True when SCO audio channel is currently active. */
    fun isScoActive(): Boolean = audioManager.isBluetoothScoOn

    /** True when the device has a Bluetooth adapter at all. */
    fun isBluetoothAvailable(): Boolean = bluetoothAdapter != null
}
