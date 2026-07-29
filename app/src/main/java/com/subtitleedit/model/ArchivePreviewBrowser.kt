package com.subtitleedit.model

import com.subtitleedit.util.ArchiveManager

data class ArchivePreviewItem(
    val name: String,
    val path: String,
    val size: Long,
    val isDirectory: Boolean,
    val modifiedTimeMillis: Long,
    val itemCount: Int
)

class ArchivePreviewBrowser(entries: List<ArchiveManager.EntryInfo>) {
    private val normalizedEntries = entries.mapNotNull { entry ->
        val path = entry.name.replace('\\', '/').trim('/')
        if (path.isEmpty()) null else entry.copy(name = path)
    }
    private val childPathsByDirectory = buildMap<String, Set<String>> {
        val mutableChildren = linkedMapOf<String, MutableSet<String>>()
        normalizedEntries.forEach { entry ->
            var parent = ""
            entry.name.split('/').forEach { segment ->
                val childPath = if (parent.isEmpty()) segment else "$parent/$segment"
                mutableChildren.getOrPut(parent) { linkedSetOf() }.add(childPath)
                parent = childPath
            }
        }
        mutableChildren.forEach { (directory, children) -> put(directory, children) }
    }
    private val descendantModifiedTimeByDirectory = buildMap<String, Long> {
        normalizedEntries.forEach { entry ->
            if (entry.modifiedTimeMillis <= 0L) return@forEach
            val segments = entry.name.split('/')
            for (depth in 1 until segments.size) {
                val directoryPath = segments.take(depth).joinToString("/")
                val current = this[directoryPath] ?: 0L
                if (entry.modifiedTimeMillis > current) {
                    put(directoryPath, entry.modifiedTimeMillis)
                }
            }
        }
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
                isDirectory = hasDescendants || entry.isDirectory,
                modifiedTimeMillis = if (hasDescendants) {
                    descendantModifiedTimeByDirectory[childPath] ?: 0L
                } else {
                    entry.modifiedTimeMillis
                },
                itemCount = childPathsByDirectory[childPath]?.size ?: 0
            )
            val existing = children[childName]
            if (existing == null || (!existing.isDirectory && candidate.isDirectory)) {
                children[childName] = candidate
            } else if (existing.isDirectory && candidate.isDirectory &&
                !hasDescendants && entry.isDirectory && candidate.modifiedTimeMillis > 0L
            ) {
                children[childName] = existing.copy(modifiedTimeMillis = candidate.modifiedTimeMillis)
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
