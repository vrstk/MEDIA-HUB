package com.mediahub.mobile.domain

import java.io.IOException
import java.util.concurrent.CancellationException

data class FolderCopyProgress(
    val currentName: String,
    val completedFiles: Int,
    val totalFiles: Int,
    val completedBytes: Long,
    val totalBytes: Long,
    val phase: CopyPhase?
)

data class FolderCopyResult(val copiedFiles: Int, val copiedBytes: Long, val createdFolders: Int)

/**
 * Preflight scans the entire source tree before creating anything. Copying is
 * non-destructive and non-overwriting. On an interrupted transfer, partial
 * destination folders remain for manual inspection; the operation never
 * automatically restarts and never deletes originals.
 */
object CloudFolderCopy {
    private const val MAX_DEPTH = 32
    private const val MAX_ITEMS = 10_000

    private sealed interface Entry {
        val node: CloudNode
        data class File(override val node: CloudNode) : Entry
        data class Folder(override val node: CloudNode, val entries: List<Entry>) : Entry
    }

    private data class Plan(val folder: Entry.Folder, val files: Int, val bytes: Long)

    fun run(
        source: CloudProvider,
        folder: CloudNode,
        target: CloudProvider,
        destination: CloudNode,
        checkpoint: () -> Unit = {},
        report: (FolderCopyProgress) -> Unit = {}
    ): FolderCopyResult {
        require(folder.isFolder && destination.isFolder) { "Нужны две папки." }
        val plan = inspect(source, folder, checkpoint)
        checkpoint()
        var completedFiles = 0
        var completedBytes = 0L
        var createdFolders = 0
        var activePath = folder.name

        fun copyRecursively(tree: Entry.Folder, targetParent: CloudNode) {
            checkpoint()
            activePath = tree.node.name
            val newFolder = target.createFolder(targetParent.id, tree.node.name)
            createdFolders++
            for (child in tree.entries) {
                checkpoint()
                activePath = child.node.name
                when (child) {
                    is Entry.Folder -> copyRecursively(child, newFolder)
                    is Entry.File -> {
                        val bytes = CloudFileCopy.run(source, child.node, target, newFolder, checkpoint) { progress ->
                            report(FolderCopyProgress(
                                currentName = child.node.name,
                                completedFiles = completedFiles,
                                totalFiles = plan.files,
                                completedBytes = completedBytes + progress.done.coerceAtMost(
                                    child.node.sizeBytes ?: progress.total
                                ),
                                totalBytes = plan.bytes,
                                phase = progress.phase
                            ))
                        }
                        completedFiles++
                        completedBytes += bytes
                        report(FolderCopyProgress(
                            child.node.name, completedFiles, plan.files,
                            completedBytes, plan.bytes, null
                        ))
                    }
                }
            }
        }

        try {
            copyRecursively(plan.folder, destination)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            throw IOException(
                "Копирование папки остановлено на «$activePath». Проверено файлов: " +
                    "$completedFiles из ${plan.files}. В папке назначения может " +
                    "остаться неполная копия. Ничего не удалено.",
                error
            )
        }
        return FolderCopyResult(completedFiles, completedBytes, createdFolders)
    }

    private fun inspect(
        source: CloudProvider,
        start: CloudNode,
        checkpoint: () -> Unit
    ): Plan {
        val visited = HashSet<String>()
        var count = 0
        var size = 0L

        fun checkName(name: String) {
            require(name.isNotBlank() && name != "." && name != ".." &&
                '/' !in name && '\\' !in name && '\n' !in name && '\r' !in name
            ) { "Некорректное имя объекта в исходной папке." }
        }

        fun scan(folder: CloudNode, depth: Int): Entry.Folder {
            checkpoint()
            require(depth <= MAX_DEPTH) { "Слишком глубокая структура папок." }
            require(visited.add(folder.id)) { "Обнаружена циклическая ссылка на папку." }
            val current = source.stat(folder.id)
            require(current.isFolder && current.name == folder.name) {
                "Исходная папка изменилась. Обновите список."
            }
            checkName(current.name)
            val children = mutableListOf<Entry>()
            val names = HashSet<String>()
            val seenPageTokens = HashSet<String>()
            var token: String? = null
            do {
                checkpoint()
                val page = source.list(folder.id, token)
                for (node in page.nodes) {
                    checkpoint()
                    checkName(node.name)
                    require(names.add(node.name)) {
                        "В исходной папке есть повторяющиеся имена объектов."
                    }
                    count++
                    require(count <= MAX_ITEMS) {
                        "Слишком много объектов для одного переноса (более $MAX_ITEMS)."
                    }
                    if (node.isFolder) {
                        children.add(scan(node, depth + 1))
                    } else {
                        require(node.canReadBytes && node.sizeBytes != null && node.sizeBytes >= 0) {
                            "Нельзя автоматически скопировать «${node.name}»: " +
                                "нет доступа к содержимому или неизвестен размер."
                        }
                        require(Long.MAX_VALUE - size >= node.sizeBytes) {
                            "Суммарный размер файлов слишком велик."
                        }
                        size += node.sizeBytes
                        children.add(Entry.File(node))
                    }
                }
                token = page.nextPageToken
                require(token == null || seenPageTokens.add(token)) {
                    "Провайдер повторил страницу при сканировании папки."
                }
            } while (token != null)
            return Entry.Folder(current, children)
        }

        val planned = scan(start, 0)
        val files = countEntries(planned)
        return Plan(planned, files, size)
    }

    private fun countEntries(folder: Entry.Folder): Int =
        folder.entries.sumOf {
            when (it) {
                is Entry.File -> 1
                is Entry.Folder -> countEntries(it)
            }
        }
}
