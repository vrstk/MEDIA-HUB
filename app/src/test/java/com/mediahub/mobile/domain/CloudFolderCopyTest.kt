package com.mediahub.mobile.domain

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class CloudFolderCopyTest {
    private class MemoryCloud : CloudProvider {
        val folders = linkedMapOf(
            "root" to CloudNode("root", "Root", true, null)
        )
        val files = linkedMapOf<String, ByteArray>()
        val children = mutableMapOf<String, MutableList<CloudNode>>()
        var creates = 0
        var uploads = 0
        override val root: CloudNode get() = folders.getValue("root")
        fun folder(parent: String, id: String, name: String): CloudNode {
            val node = CloudNode(id, name, true, null)
            folders[id] = node
            children.getOrPut(parent) { mutableListOf() }.add(node)
            return node
        }
        fun file(parent: String, id: String, name: String, bytes: ByteArray): CloudNode {
            val node = CloudNode(id, name, false, bytes.size.toLong())
            files[id] = bytes
            children.getOrPut(parent) { mutableListOf() }.add(node)
            return node
        }
        override fun list(folderId: String, pageToken: String?) =
            CloudPage(children[folderId]?.toList() ?: emptyList())
        override fun stat(nodeId: String) =
            folders[nodeId] ?: children.values.flatten().single { it.id == nodeId }
        override fun createFolder(parentId: String, name: String): CloudNode {
            require(children[parentId].orEmpty().none { it.name == name }) { "Уже существует" }
            creates++
            return folder(parentId, "folder-$creates", name)
        }
        override fun openRead(file: CloudNode): CloudRead =
            CloudRead(ByteArrayInputStream(files.getValue(file.id)), files.getValue(file.id).size.toLong())
        override fun uploadNew(parentId: String, file: CloudNode, size: Long, source: InputStream): CloudNode {
            require(children[parentId].orEmpty().none { it.name == file.name }) { "Уже существует" }
            val bytes = source.readBytes()
            assertEquals(size, bytes.size.toLong())
            uploads++
            return file(parentId, "file-$uploads", file.name, bytes)
        }
    }

    @Test fun recursivelyCopiesAndChecksOriginals() {
        val src = MemoryCloud()
        val a = src.folder("root", "a", "Models")
        val b = src.folder("a", "b", "Alba")
        src.file("b", "photo", "1.jpg", byteArrayOf(1, 2, 3))
        src.file("a", "text", "readme.txt", byteArrayOf(4, 5))
        val dst = MemoryCloud()
        val completed = mutableListOf<Int>()
        val result = CloudFolderCopy.run(src, a, dst, dst.root, report = {
            if (it.phase == null) completed.add(it.completedFiles)
        })
        assertEquals(2, result.copiedFiles)
        assertEquals(5L, result.copiedBytes)
        assertEquals(2, result.createdFolders)
        assertEquals(listOf(1, 2), completed.sorted())
        assertArrayEquals(byteArrayOf(1, 2, 3), src.files.getValue("photo"))
        assertEquals(2, dst.uploads)
    }

    @Test fun rejectsUnreadableFileBeforeAnyWrite() {
        val src = MemoryCloud()
        val folder = src.folder("root", "a", "Models")
        src.children.getOrPut("a") { mutableListOf() }.add(
            CloudNode("bad", "virtual.gdoc", false, null, canReadBytes = false)
        )
        val dst = MemoryCloud()
        assertThrows(IllegalArgumentException::class.java) {
            CloudFolderCopy.run(src, folder, dst, dst.root)
        }
        assertEquals(0, dst.creates)
    }

    @Test fun rejectsCyclesBeforeAnyWrite() {
        val src = MemoryCloud()
        val folder = src.folder("root", "a", "Cycle")
        src.children.getOrPut("a") { mutableListOf() }.add(folder)
        val dst = MemoryCloud()
        assertThrows(IllegalArgumentException::class.java) {
            CloudFolderCopy.run(src, folder, dst, dst.root)
        }
        assertEquals(0, dst.creates)
    }
}
