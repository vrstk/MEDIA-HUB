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

    @Test fun breadcrumbsDecodeNamesWithoutTouchingEncodedServerPaths() {
        val crumbs = DavPath.breadcrumbs("/Images/%D0%9C%D0%BE%D0%B4%D0%B5%D0%BB%D0%B8%20A/")
        assertEquals(listOf("Облако", "Images", "Модели A"), crumbs.map { it.title })
        assertEquals("/Images/%D0%9C%D0%BE%D0%B4%D0%B5%D0%BB%D0%B8%20A/", crumbs.last().path)
        assertEquals("/", DavPath.breadcrumbs("/").single().path)
    }

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

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDoctype() {
        DavXmlParser.parse("<!DOCTYPE x [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><x>&x;</x>", "/")
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsEntityDeclarations() {
        DavXmlParser.parse("<!ENTITY secret SYSTEM 'file:///etc/passwd'><d:multistatus xmlns:d='DAV:'/>", "/")
    }

    @Test
    fun parsesEscapedNamesAndValidDavNamespace() {
        val xml = """
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>/My%20Files/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
              </d:response>
              <d:response><d:href>/My%20Files/A%26B.jpg</d:href>
                <d:propstat><d:prop><d:displayname>A&amp;B.jpg</d:displayname>
                  <d:getcontentlength>42</d:getcontentlength>
                </d:prop></d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
        val result = DavXmlParser.parse(xml, "/My%20Files/")
        assertEquals(1, result.size)
        assertEquals("A&B.jpg", result[0].name)
        assertEquals(42L, result[0].size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedResponseBeforeParse() {
        DavXmlParser.parse("x".repeat(8_000_001), "/")
    }
}
