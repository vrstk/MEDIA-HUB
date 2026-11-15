package com.mediahub.mobile.catalog

import com.mediahub.mobile.domain.CloudNode
import com.mediahub.mobile.domain.CloudPage
import com.mediahub.mobile.domain.CloudProvider
import com.mediahub.mobile.domain.CloudRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.InputStream

class ProfileFolderTest {
    @Test fun webDavLinkRequiresTheOriginalAccountAndAValidFolderPath() {
        val root = directory("/", "Облако Mail")
        val parent = directory("/%D0%A4%D0%BE%D1%82%D0%BE/", "Фото")
        val child = directory("/%D0%A4%D0%BE%D1%82%D0%BE/2026%20%26%20A/", "2026 & A")
        val provider = FolderRoot(root)
        val access = ProfileFolderAccess.mail("Owner@Example.test", provider)
        val link = access.link("profile", listOf(root, parent, child))
        assertEquals("Облако Mail / Фото / 2026 & A", link.displayPath())
        assertEquals(listOf(root, parent, child), ProfileFolderAccess.mail("owner@example.test", provider).resolve(link))
        assertNull(ProfileFolderAccess.mail("other@example.test", provider).resolve(link))
        assertNull(access.resolve(link.copy(folderId = parent.id)))
        assertNull(access.resolve(link.copy(trailJson = "invalid")))
        assertThrows(IllegalArgumentException::class.java) { access.link("profile", listOf(root, child)) }
    }

    @Test fun androidFolderLinkUsesTheSelectedTreeEvenWhenFolderIdsCoincide() {
        val root = directory("root", "Мои файлы")
        val target = directory("opaque/id & nested", "Галерея")
        val provider = FolderRoot(root)
        val original = ProfileFolderAccess.androidDrive("content://drive/tree/one", provider)
        val link = original.link("profile", listOf(root, target))
        assertEquals(listOf(root, target), original.resolve(link))
        assertEquals(listOf(root), original.resolve(original.link("profile", listOf(root))))
        assertNull(ProfileFolderAccess.androidDrive("content://drive/tree/two", provider).resolve(link))
        assertNull(ProfileFolderAccess.androidDrive("content://drive/tree/one", FolderRoot(directory("other", "Другое"))).resolve(link))
        assertThrows(IllegalArgumentException::class.java) { original.link("profile", listOf(root, target, root)) }
        assertThrows(IllegalArgumentException::class.java) {
            original.link("profile", listOf(root, target.copy(isFolder = false)))
        }
    }

    private fun directory(id: String, title: String) = CloudNode(id, title, true, null)

    private class FolderRoot(override val root: CloudNode) : CloudProvider {
        override fun list(folderId: String, pageToken: String?): CloudPage = error("I/O not required")
        override fun stat(nodeId: String): CloudNode = error("I/O not required")
        override fun createFolder(parentId: String, name: String): CloudNode = error("I/O not required")
        override fun openRead(file: CloudNode): CloudRead = error("I/O not required")
        override fun uploadNew(parentId: String, file: CloudNode, size: Long, source: InputStream): CloudNode =
            error("I/O not required")
    }
}
