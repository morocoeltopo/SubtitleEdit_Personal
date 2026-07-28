package com.subtitleedit.model

import com.subtitleedit.util.ArchiveManager

data class ArchivePreviewItem(
    val name: String,
    val path: String,
    val size: Long,
    val isDirectory: Boolean
)

class ArchivePreviewBrowser(entries: List<ArchiveManager.EntryInfo>) {
    private val normalizedEntries = entries.mapNotNull { entry ->
        val path = entry.name.replace('\\', '/').trim('/')
        if (path.isEmpty()) null else entry.copy(name = path)
    }

    fun itemsAt(directory: String): List<ArchivePreviewItem> {
        val normalizedDirectory = directory.trim('/')
        val prefix = if (normalizedDirectory.isEmpty()) "" else "$normalizedDirectory/"
        val children = linkedMapOf<String, ArchivePreviewItem>()

        normalizedEntries.forEach { entry ->
            if (!entry.name.startsWith(prefix)) return@forEach
            val relativePath = entry.name.removePrefix(prefix)
            if (relativePath.isEmpty()) return@forEach

            val childName = relativePath.substringBefore('/')
            val hasDescendants = '/' in relativePath
            val childPath = prefix + childName
            val candidate = ArchivePreviewItem(
                name = childName,
                path = childPath,
                size = if (hasDescendants) -1L else entry.size,
                isDirectory = hasDescendants || entry.isDirectory
            )
            val existing = children[childName]
            if (existing == null || (!existing.isDirectory && candidate.isDirectory)) {
                children[childName] = candidate
            }
        }

        return children.values.sortedWith(
            compareByDescending<ArchivePreviewItem> { it.isDirectory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )
    }

    companion object {
        fun parentOf(directory: String): String = directory.trim('/').substringBeforeLast('/', "")
    }
}
