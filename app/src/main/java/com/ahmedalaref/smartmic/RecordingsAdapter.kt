package com.ahmedalaref.smartmic

import android.media.MediaPlayer
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ahmedalaref.smartmic.databinding.ItemRecordingBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RecyclerView adapter for the list of saved MP3 recordings.
 *
 * Each row shows:
 *  • File name                 • Date + size
 *  • ▶ / ⏹ play-toggle button  • 🗑 delete button
 *
 * Only one file plays at a time; tapping ▶ on another row stops
 * the currently-playing one first.
 */
class RecordingsAdapter(
    private val items   : MutableList<File>,
    private val onDelete: (File) -> Unit
) : RecyclerView.Adapter<RecordingsAdapter.VH>() {

    private var player          : MediaPlayer? = null
    private var playingPosition  = -1

    // ─── ViewHolder ───────────────────────────────────────────────────────────

    inner class VH(val b: ItemRecordingBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(file: File, pos: Int) {
            b.tvFileName.text  = file.nameWithoutExtension
            b.tvFileDate.text  = SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault())
                                    .format(Date(file.lastModified()))
            b.tvFileSize.text  = formatSize(file.length())

            val isPlaying = pos == playingPosition
            b.btnPlay.text     = if (isPlaying) "⏹" else "▶"

            b.btnPlay.setOnClickListener {
                if (isPlaying) stopPlayback()
                else           startPlayback(file, pos)
            }
            b.btnDelete.setOnClickListener { onDelete(file) }
        }

        private fun formatSize(bytes: Long): String = when {
            bytes < 1_024          -> "$bytes B"
            bytes < 1_048_576      -> "${bytes / 1_024} KB"
            else                   -> String.format("%.1f MB", bytes / 1_048_576f)
        }
    }

    // ─── Playback ─────────────────────────────────────────────────────────────

    private fun startPlayback(file: File, pos: Int) {
        stopPlayback()
        try {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    val old = playingPosition
                    playingPosition = -1
                    if (old >= 0) notifyItemChanged(old)
                }
            }
            playingPosition = pos
            notifyItemChanged(pos)
        } catch (ex: Exception) {
            player?.release(); player = null
            playingPosition = -1
        }
    }

    fun stopPlayback() {
        val old = playingPosition
        player?.runCatching { stop(); release() }
        player           = null
        playingPosition  = -1
        if (old >= 0) notifyItemChanged(old)
    }

    // ─── Data helpers ─────────────────────────────────────────────────────────

    fun updateData(newItems: List<File>) {
        items.clear(); items.addAll(newItems); notifyDataSetChanged()
    }

    fun cleanup() = stopPlayback()

    // ─── RecyclerView.Adapter ─────────────────────────────────────────────────

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemRecordingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, pos: Int) = holder.bind(items[pos], pos)
    override fun getItemCount() = items.size
}
