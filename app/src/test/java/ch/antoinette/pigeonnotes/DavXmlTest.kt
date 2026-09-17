package ch.antoinette.pigeonnotes

import ch.antoinette.pigeonnotes.data.remote.DavXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DavXmlTest {
    private val root = "/remote.php/dav/files/me/Notes"

    private val xml = """
        <?xml version="1.0"?>
        <d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
          <d:response>
            <d:href>/remote.php/dav/files/me/Notes/</d:href>
            <d:propstat>
              <d:prop>
                <d:getetag>&quot;68c9a1b2c3d4e&quot;</d:getetag>
                <d:getlastmodified>Wed, 17 Sep 2026 10:00:00 GMT</d:getlastmodified>
                <d:resourcetype><d:collection/></d:resourcetype>
                <oc:fileid>123</oc:fileid>
              </d:prop>
              <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat>
            <d:propstat>
              <d:prop><d:getcontentlength/><d:getcontenttype/></d:prop>
              <d:status>HTTP/1.1 404 Not Found</d:status>
            </d:propstat>
          </d:response>
          <d:response>
            <d:href>/remote.php/dav/files/me/Notes/ETH/AI%20Paper%20Club.md</d:href>
            <d:propstat>
              <d:prop>
                <d:getetag>"abc123"</d:getetag>
                <d:getlastmodified>Wed, 17 Sep 2026 10:05:00 GMT</d:getlastmodified>
                <d:getcontentlength>42</d:getcontentlength>
                <d:getcontenttype>text/markdown</d:getcontenttype>
                <d:resourcetype/>
              </d:prop>
              <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat>
          </d:response>
          <d:response>
            <d:href>/remote.php/dav/files/me/Notes/_archive/%23%20World%20Cup%202026%20%E2%80%94%20Predictions.md</d:href>
            <d:propstat>
              <d:prop><d:getetag>"z"</d:getetag><d:resourcetype/></d:prop>
              <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat>
          </d:response>
        </d:multistatus>
    """.trimIndent()

    @Test
    fun `parses entries relative to root`() {
        val entries = DavXml.parseMultistatus(xml, root)
        assertEquals(3, entries.size)
        val dir = entries[0]
        assertEquals("", dir.path)
        assertTrue(dir.isDir)
        assertEquals("68c9a1b2c3d4e", dir.etag)

        val file = entries[1]
        assertEquals("ETH/AI Paper Club.md", file.path)
        assertFalse(file.isDir)
        assertEquals("abc123", file.etag)
        assertEquals(42L, file.size)
        assertEquals("text/markdown", file.contentType)
        assertTrue(file.modified > 0)

        assertEquals("_archive/# World Cup 2026 — Predictions.md", entries[2].path)
    }

    @Test
    fun `etag normalisation`() {
        assertEquals("abc", DavXml.normalizeEtag("\"abc\""))
        assertEquals("abc", DavXml.normalizeEtag("W/\"abc\""))
        assertEquals("abc", DavXml.normalizeEtag("abc"))
    }
}
