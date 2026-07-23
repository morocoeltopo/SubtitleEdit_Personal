package com.subtitleedit.adapter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.subtitleedit.R
import com.subtitleedit.util.ArchiveManager
import com.subtitleedit.util.FileUtils
import java.io.File
import java.util.concurrent.Executors

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
        val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "tif", "tiff", "avif"
        )
    }

    private var selectionMode = false
    private var selectedPaths: Set<String> = emptySet()
    private val apkIconCache = mutableMapOf<String, Drawable?>()
    private val thumbnailExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val thumbnailCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

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
        private val iconPadding = (8 * itemView.resources.displayMetrics.density + 0.5f).toInt()

        fun bind(file: File) {
            val thumbnailKey = thumbnailKey(file)
            ivFileIcon.tag = thumbnailKey
            ivFileIcon.scaleType = ImageView.ScaleType.FIT_CENTER
            ivFileIcon.setPadding(iconPadding, iconPadding, iconPadding, iconPadding)

            // 设置图标
            if (file.isDirectory) {
                ivFileIcon.setImageResource(R.drawable.ic_folder)
                tvFileSize.visibility = View.GONE
                tvFileExtension.visibility = View.GONE
            } else {
                val extension = file.extension.lowercase()
                if (extension in IMAGE_EXTENSIONS) {
                    bindImageThumbnail(file, thumbnailKey)
                } else if (extension == "apk") {
                    ivFileIcon.setImageDrawable(loadApkIcon(file))
                } else {
                    ivFileIcon.setImageResource(
                        when {
                            FileUtils.isAudioFile(file) -> R.drawable.ic_file_audio
                            extension in VIDEO_EXTENSIONS -> R.drawable.ic_file_video
                            extension in ArchiveManager.recognizedExtensions -> archiveIcon(file)
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

        private fun bindImageThumbnail(file: File, cacheKey: String) {
            ivFileIcon.setPadding(0, 0, 0, 0)
            ivFileIcon.scaleType = ImageView.ScaleType.CENTER_CROP

            thumbnailCache.get(cacheKey)?.let {
                ivFileIcon.setImageBitmap(it)
                return
            }

            ivFileIcon.scaleType = ImageView.ScaleType.FIT_CENTER
            ivFileIcon.setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            ivFileIcon.setImageResource(R.drawable.ic_file)
            val targetSize = (48 * itemView.resources.displayMetrics.density + 0.5f).toInt()
            thumbnailExecutor.execute {
                val bitmap = runCatching { decodeSampledBitmap(file, targetSize) }.getOrNull()
                if (bitmap != null) thumbnailCache.put(cacheKey, bitmap)
                mainHandler.post {
                    if (ivFileIcon.tag != cacheKey) return@post
                    if (bitmap != null) {
                        ivFileIcon.scaleType = ImageView.ScaleType.CENTER_CROP
                        ivFileIcon.setPadding(0, 0, 0, 0)
                        ivFileIcon.setImageBitmap(bitmap)
                    } else {
                        ivFileIcon.scaleType = ImageView.ScaleType.FIT_CENTER
                        ivFileIcon.setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
                        ivFileIcon.setImageResource(R.drawable.ic_file)
                    }
                }
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

        private fun decodeSampledBitmap(file: File, targetSize: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            while (bounds.outWidth / (sampleSize * 2) >= targetSize &&
                bounds.outHeight / (sampleSize * 2) >= targetSize) {
                sampleSize *= 2
            }
            return BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
        }

        private fun thumbnailKey(file: File): String =
            "${file.absolutePath}:${file.length()}:${file.lastModified()}"

        private fun archiveIcon(file: File): Int {
            val name = file.name.lowercase()
            return when {
                name.endsWith(".7z") -> R.drawable.ic_file_archive_7z
                name.endsWith(".rar") -> R.drawable.ic_file_archive_rar
                name.endsWith(".tar") -> R.drawable.ic_file_archive_tar
                name.endsWith(".gz") || name.endsWith(".tgz") -> R.drawable.ic_file_archive_gz
                name.endsWith(".bz") || name.endsWith(".bz2") ||
                    name.endsWith(".tbz") || name.endsWith(".tbz2") -> R.drawable.ic_file_archive_bz2
                name.endsWith(".xz") || name.endsWith(".txz") -> R.drawable.ic_file_archive_xz
                else -> R.drawable.ic_file_archive
            }
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
