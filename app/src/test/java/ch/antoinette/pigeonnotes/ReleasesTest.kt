package ch.antoinette.pigeonnotes

import ch.antoinette.pigeonnotes.data.update.InstalledBuild
import ch.antoinette.pigeonnotes.data.update.Release
import ch.antoinette.pigeonnotes.data.update.ReleaseAsset
import ch.antoinette.pigeonnotes.data.update.Releases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ReleasesTest {

    @Test
    fun `parses releases and test builds as GitHub lists them`() {
        val json = """
            [
              {"tag_name":"test-claude-x","name":"Test: claude/x (0.1.32-test.2)","draft":false,"prerelease":true,
               "published_at":"2026-11-08T15:12:27Z","target_commitish":"bbbb","body":"Test build of branch",
               "assets":[{"name":"pigeon-notes-0.1.32-test.2.apk","size":123,
                 "browser_download_url":"https://github.com/o/r/releases/download/test-claude-x/pigeon-notes-0.1.32-test.2.apk",
                 "digest":"sha256:ABCDEF"}]},
              {"tag_name":"build-32","name":"0.1.32","draft":false,"prerelease":false,
               "published_at":"2026-11-08T14:00:00Z","target_commitish":"aaaa","body":"Merge pull request #6\r\n",
               "assets":[{"name":"pigeon-notes-0.1.32.apk","size":456,"digest":null,
                 "browser_download_url":"https://github.com/o/r/releases/download/build-32/pigeon-notes-0.1.32.apk"}]},
              {"tag_name":"draft","name":"Draft","draft":true,"prerelease":false,"published_at":null,
               "target_commitish":"cccc","body":"","assets":[]},
              {"tag_name":"other","name":"No APK","draft":false,"prerelease":false,
               "published_at":"2026-11-01T00:00:00Z","target_commitish":"dddd","body":"",
               "assets":[{"name":"notes.zip","size":1,"browser_download_url":"https://x"}]}
            ]
        """.trimIndent()
        val releases = Releases.parse(json)
        assertEquals(listOf("test-claude-x", "build-32"), releases.map { it.tag })

        val test = releases[0]
        assertEquals(true, test.prerelease)
        assertEquals(32, test.versionCode)
        assertEquals("0.1.32-test.2", test.versionName)
        assertEquals("bbbb", test.commit)
        assertEquals("abcdef", test.apk.sha256)
        assertEquals(Instant.parse("2026-11-08T15:12:27Z").epochSecond, test.publishedAt)

        val release = releases[1]
        assertEquals(32, release.versionCode)
        assertEquals("0.1.32", release.versionName)
        assertNull(release.apk.sha256)
        assertEquals(456L, release.apk.size)
        assertEquals("Merge pull request #6", release.notes)
    }

    private fun release(code: Int, published: Long, commit: String = "r$code", test: Boolean = false) = Release(
        tag = if (test) "test-$commit" else "build-$code",
        title = "",
        prerelease = test,
        publishedAt = published,
        commit = commit,
        notes = "",
        apk = ReleaseAsset("pigeon-notes.apk", "https://x", 1, null),
        versionCode = code,
        versionName = if (test) "0.1.$code-test.1" else "0.1.$code",
    )

    private fun installed(code: Int, commitTime: Long, commit: String = "r$code") =
        InstalledBuild(code, "0.1.$code", commit, commitTime)

    @Test
    fun `releases with a higher version are offered, the highest first`() {
        val list = listOf(release(31, 100), release(33, 300), release(32, 200))
        assertEquals("r33", Releases.pick(list, installed(31, 90), includeTests = false)?.commit)
        assertNull("up to date", Releases.pick(list, installed(33, 290), includeTests = false))
    }

    @Test
    fun `test builds are only offered when asked for`() {
        val list = listOf(release(31, 100), release(31, 500, "t1", test = true))
        assertNull(Releases.pick(list, installed(31, 90), includeTests = false))
        assertEquals("t1", Releases.pick(list, installed(31, 90), includeTests = true)?.commit)
    }

    @Test
    fun `a test build is offered only when it came after the installed build and would install`() {
        val older = release(31, 150, "old", test = true)
        assertNull("published before the installed build was made", Releases.pick(listOf(older), installed(31, 200), true))
        val lowerVersion = release(30, 500, "low", test = true)
        assertNull("Android would refuse the downgrade", Releases.pick(listOf(lowerVersion), installed(31, 200), true))
        assertNull("the installed build itself", Releases.pick(listOf(release(31, 500, "me", test = true)), installed(31, 200, "me"), true))
    }

    @Test
    fun `a newer release wins over a test build of an older one`() {
        val list = listOf(release(32, 300), release(31, 400, "t", test = true))
        assertEquals("r32", Releases.pick(list, installed(31, 100), includeTests = true)?.commit)
        // ...and the newest test build of that release wins over the release itself.
        val withTest = list + release(32, 500, "t32", test = true)
        assertEquals("t32", Releases.pick(withTest, installed(31, 100), includeTests = true)?.commit)
    }

    @Test
    fun `a test build installed moves on to the release that merges it`() {
        // Test build based on release 31, made at 400; the merge became release 32.
        val list = listOf(release(31, 100), release(31, 410, "t", test = true), release(32, 500))
        assertEquals("r32", Releases.pick(list, installed(31, 400, "t"), includeTests = false)?.commit)
        assertNull(
            "the release it is based on is no update",
            Releases.pick(listOf(release(31, 100)), installed(31, 400, "t"), includeTests = false),
        )
    }
}
