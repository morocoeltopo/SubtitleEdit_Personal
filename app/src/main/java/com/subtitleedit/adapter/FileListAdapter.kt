package com.subtitleedit.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.drawable.Drawable
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.subtitleedit.R
import com.subtitleedit.util.FileUtils
import java.io.File

/**
 * 文件列表适配器
 */
class FileListAdapter(
    private val onItemClick: (File) -> Unit,
    private val onItemLongClick: (File) -> Unit
) : ListAdapter<File, FileListAdapter.FileViewHolder>(FileDiffCallback()) {

    private companion object {
        val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v")
        val TEXT_EXTENSIONS = setOf("md", "log", "json", "xml", "csv", "ini", "conf")
    }

    private var selectionMode = false
    private var selectedPaths: Set<String> = emptySet()
    private val apkIconCache = mutableMapOf<String, Drawable?>()

    fun updateSelection(selectionMode: Boolean, selectedPaths: Set<String>) {
        this.selectionMode = selectionMode
        this.selectedPaths = selectedPaths
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivFileIcon: ImageView = itemView.findViewById(R.id.ivFileIcon)
        private val tvFileName: TextView = itemView.findViewById(R.id.tvFileName)
        private val tvFileSize: TextView = itemView.findViewById(R.id.tvFileSize)
        private val tvFileExtension: TextView = itemView.findViewById(R.id.tvFileExtension)
        private val card: MaterialCardView = itemView as MaterialCardView

        fun bind(file: File) {
            // 设置图标
            if (file.isDirectory) {
                ivFileIcon.setImageResource(R.drawable.ic_folder)
                tvFileSize.visibility = View.GONE
                tvFileExtension.visibility = View.GONE
            } else {
                val extension = file.extension.lowercase()
                if (extension == "apk") {
                    ivFileIcon.setImageDrawable(loadApkIcon(file))
                } else {
                    ivFileIcon.setImageResource(
                        when {
                            FileUtils.isAudioFile(file) -> R.drawable.ic_file_audio
                            extension in VIDEO_EXTENSIONS -> R.drawable.ic_file_video
                            FileUtils.isSubtitleFile(file) || extension in TEXT_EXTENSIONS ->
                                R.drawable.ic_file_text
                            else -> R.drawable.ic_file
                        }
                    )
                }
                tvFileSize.text = FileUtils.formatFileSize(file.length())
                tvFileSize.visibility = View.VISIBLE
                tvFileExtension.text = file.extension.uppercase()
                tvFileExtension.visibility = View.VISIBLE
            }
            ivFileIcon.alpha = if (file.name.startsWith(".") && file.name != "..") 0.5f else 1f

            // 设置文件名
            tvFileName.text = file.name
            val isSelected = file.absolutePath in selectedPaths
            card.strokeWidth = if (isSelected) 2 else 0
            card.strokeColor = if (isSelected) {
                androidx.core.content.ContextCompat.getColor(itemView.context, R.color.primary)
            } else {
                android.graphics.Color.TRANSPARENT
            }
            itemView.alpha = if (selectionMode && !isSelected && file.name != "..") 0.72f else 1f

            // 点击事件
            itemView.setOnClickListener {
                onItemClick(file)
            }
            itemView.setOnLongClickListener {
                onItemLongClick(file)
                true
            }
        }

        @Suppress("DEPRECATION")
        private fun loadApkIcon(file: File): Drawable? {
            val cacheKey = "${file.absolutePath}:${file.lastModified()}"
            if (apkIconCache.containsKey(cacheKey)) return apkIconCache[cacheKey]

            val packageManager = itemView.context.packageManager
            val icon = runCatching {
                val packageInfo = packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                packageInfo?.applicationInfo?.let { applicationInfo ->
                    applicationInfo.sourceDir = file.absolutePath
                    applicationInfo.publicSourceDir = file.absolutePath
                    applicationInfo.loadIcon(packageManager)
                }
            }.getOrNull() ?: androidx.core.content.ContextCompat.getDrawable(
                itemView.context,
                R.drawable.ic_file
            )
            apkIconCache[cacheKey] = icon
            return icon
        }
    }

    private class FileDiffCallback : DiffUtil.ItemCallback<File>() {
        override fun areItemsTheSame(oldItem: File, newItem: File): Boolean {
            return oldItem.absolutePath == newItem.absolutePath
        }

        override fun areContentsTheSame(oldItem: File, newItem: File): Boolean {
            return oldItem.name == newItem.name && 
                   oldItem.length() == newItem.length() && 
                   oldItem.isDirectory == newItem.isDirectory
        }
    }
}
