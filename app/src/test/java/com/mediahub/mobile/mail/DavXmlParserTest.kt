package com.mediahub.mobile.mail

import org.junit.Assert.*
import org.junit.Test

class DavXmlParserTest {
    private val listing = """
      <d:multistatus xmlns:d="DAV:">
        <d:response><d:href>/</d:href>
          <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
        </d:response>
        <d:response><d:href>/Photos/</d:href>
          <d:propstat><d:prop><d:displayname>Photos</d:displayname><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
        </d:response>
        <d:response><d:href>/abc%20def.jpg</d:href>
          <d:propstat><d:prop><d:getcontentlength>123</d:getcontentlength></d:prop></d:propstat>
        </d:response>
      </d:multistatus>
    """.trimIndent()

    @Test fun excludesOwnFolderAndSortsDirectoriesFirst() {
        val files = DavXmlParser.parse(listing, "/")
        assertEquals(2, files.size)
        assertEquals("Photos", files[0].name)
        assertTrue(files[0].isDirectory)
        assertEquals("abc def.jpg", files[1].name)
        assertEquals(123L, files[1].size)
    }

    @Test fun safelyEncodesNamesAndParentPaths() {
        assertEquals("/My%20files/a%23b.jpg",
            DavPath.child("/My%20files/", "a#b.jpg", false))
        assertEquals("/My%20files/", DavPath.parent("/My%20files/a%23b.jpg"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOtherHost() {
        DavPath.normalize("https://evil.example/secret")
    }

    @Test(expected = Exception::class)
    fun rejectsDoctype() {
        DavXmlParser.parse("<!DOCTYPE x [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><x>&x;</x>", "/")
    }
}
