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
        var readbackFailuresRemaining = 0
        var readbackFailureName: String? = null
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
        override fun openRead(file: CloudNode): CloudRead {
            if (readbackFailureName == file.name && readbackFailuresRemaining > 0) {
                readbackFailuresRemaining--
                throw IOException("Provider has not made this uploaded object readable yet.")
            }
            return CloudRead(
                ByteArrayInputStream(files.getValue(file.id)),
                files.getValue(file.id).size.toLong()
            )
        }
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


    @Test fun lastFileBecomesReadableAfterDelayedProviderSync() {
        val source = MemoryCloud()
        val originals = source.folder("root", "gallery", "Gallery")
        for (index in 1..15) {
            source.file("gallery", "original-$index", "image-$index.jpg", byteArrayOf(index.toByte()))
        }
        val destination = MemoryCloud().apply {
            readbackFailureName = "image-15.jpg"
            readbackFailuresRemaining = 2
        }
        val result = CloudFolderCopy.run(source, originals, destination, destination.root)
        assertEquals(15, result.copiedFiles)
        assertEquals(15, destination.uploads)
        assertEquals(0, destination.readbackFailuresRemaining)
        assertEquals(15, source.files.size)
    }

    @Test fun failedReadbackNeverReuploadsTheLastObject() {
        val source = MemoryCloud()
        val originals = source.folder("root", "gallery", "Gallery")
        for (index in 1..3) {
            source.file("gallery", "original-$index", "image-$index.jpg", byteArrayOf(index.toByte()))
        }
        val destination = MemoryCloud().apply {
            readbackFailureName = "image-3.jpg"
            readbackFailuresRemaining = 3
        }
        val pending = assertThrows(FolderVerificationPendingException::class.java) {
            CloudFolderCopy.run(source, originals, destination, destination.root)
        }
        assertEquals(2, pending.verifiedFiles)
        assertEquals(3, pending.totalFiles)
        assertEquals("image-3.jpg", pending.pendingFile)
        assertEquals(3, destination.uploads)
        assertEquals(3, destination.files.size)
    }


    @Test fun reconcileExistingGalleryWithoutReuploadingVerifiedFiles() {
        val source = MemoryCloud()
        val original = source.folder("root", "gallery", "Gallery")
        source.file("gallery", "x1", "1.jpg", byteArrayOf(1, 2))
        source.file("gallery", "x2", "2.jpg", byteArrayOf(3, 4))
        source.file("gallery", "x3", "3.jpg", byteArrayOf(5, 6))
        val destination = MemoryCloud()
        val existing = destination.folder("root", "old", "Gallery")
        destination.file("old", "old1", "1.jpg", byteArrayOf(1, 2))
        destination.file("old", "old2", "2.jpg", byteArrayOf(3, 4))
        val result = CloudFolderCopy.run(source, original, destination, destination.root,
            reconcileExisting = true)
        assertEquals(3, result.copiedFiles)
        assertEquals(2, result.reusedFiles)
        assertEquals(1, destination.uploads)
        assertEquals(0, destination.creates)
        assertEquals(3, destination.children.getValue(existing.id).size)
    }

    @Test fun mismatchBlocksUploadWithoutReplacingAnything() {
        val source = MemoryCloud()
        val original = source.folder("root", "gallery", "Gallery")
        source.file("gallery", "one", "1.jpg", byteArrayOf(1))
        source.file("gallery", "two", "2.jpg", byteArrayOf(2))
        val destination = MemoryCloud()
        destination.folder("root", "gallery2", "Gallery")
        destination.file("gallery2", "wrong", "1.jpg", byteArrayOf(9))
        assertThrows(IOException::class.java) {
            CloudFolderCopy.run(source, original, destination, destination.root,
                reconcileExisting = true)
        }
        assertEquals(0, destination.uploads)
        assertArrayEquals(byteArrayOf(9), destination.files.getValue("wrong"))
    }

    @Test fun missingReconcileFolderDoesNotCreateIt() {
        val source = MemoryCloud()
        val folder = source.folder("root", "gallery", "Gallery")
        source.file("gallery", "one", "1.jpg", byteArrayOf(1))
        val destination = MemoryCloud()
        assertThrows(IOException::class.java) {
            CloudFolderCopy.run(source, folder, destination, destination.root,
                reconcileExisting = true)
        }
        assertEquals(0, destination.creates)
        assertEquals(0, destination.uploads)
    }

    @Test fun wrongDestinationObjectTypeStopsBeforeWrite() {
        val source = MemoryCloud()
        val folder = source.folder("root", "gallery", "Gallery")
        source.file("gallery", "one", "1.jpg", byteArrayOf(1))
        val destination = MemoryCloud()
        destination.file("root", "clash", "Gallery", byteArrayOf(1))
        assertThrows(IOException::class.java) {
            CloudFolderCopy.run(source, folder, destination, destination.root,
                reconcileExisting = true)
        }
        assertEquals(0, destination.uploads)
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
