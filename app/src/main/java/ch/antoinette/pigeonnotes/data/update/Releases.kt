package ch.antoinette.pigeonnotes.data.update

import org.json.JSONArray
import java.time.Instant

/** The APK attached to a release. [sha256] is GitHub's digest of it, when GitHub reports one. */
data class ReleaseAsset(val name: String, val url: String, val size: Long, val sha256: String?)

/**
 * A build published on the repo's Releases page: a release (`build-<n>`, from `main`) or a test
 * build of a branch (`test-<branch>`, a pre-release). See `.github/workflows/build.yml`.
 */
data class Release(
    val tag: String,
    val title: String,
    val prerelease: Boolean,
    /** Epoch seconds. */
    val publishedAt: Long,
    /** The commit it was built from (CI creates every release with `--target <sha>`). */
    val commit: String,
    val notes: String,
    val apk: ReleaseAsset,
    /** From the APK's name: test builds carry the version of the `main` commit they are based on. */
    val versionCode: Int,
    val versionName: String,
)

/** The running app, as far as updates are concerned. */
data class InstalledBuild(
    val versionCode: Int,
    val versionName: String,
    val commit: String,
    /** Epoch seconds of [commit]. */
    val commitTime: Long,
)

/** Reading GitHub's release list and choosing the build to offer. Pure, so it can be unit-tested. */
object Releases {
    /** `pigeon-notes-0.1.31.apk` (a release) or `pigeon-notes-0.1.31-test.3.apk` (a test build). */
    private val APK_NAME = Regex("""pigeon-notes-(0\.1\.(\d+)(?:-test\.\d+)?)\.apk""")

    /**
     * The releases in the JSON of `GET /repos/{owner}/{repo}/releases`. Drafts and releases
     * without an APK named like CI names them are left out.
     */
    fun parse(json: String): List<Release> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { i ->
            val r = array.getJSONObject(i)
            if (r.optBoolean("draft")) return@mapNotNull null
            val published = r.optString("published_at").takeIf { it.isNotEmpty() && it != "null" } ?: return@mapNotNull null
            val assets = r.optJSONArray("assets") ?: return@mapNotNull null
            val (asset, match) = (0 until assets.length()).asSequence()
                .map { assets.getJSONObject(it) }
                .mapNotNull { a -> APK_NAME.matchEntire(a.optString("name"))?.let { a to it } }
                .firstOrNull() ?: return@mapNotNull null
            Release(
                tag = r.optString("tag_name"),
                title = r.optString("name").takeIf { it.isNotEmpty() && it != "null" } ?: r.optString("tag_name"),
                prerelease = r.optBoolean("prerelease"),
                publishedAt = runCatching { Instant.parse(published).epochSecond }.getOrNull() ?: return@mapNotNull null,
                commit = r.optString("target_commitish"),
                notes = r.optString("body").takeIf { it != "null" }.orEmpty().trim(),
                apk = ReleaseAsset(
                    name = asset.optString("name"),
                    url = asset.optString("browser_download_url"),
                    size = asset.optLong("size"),
                    sha256 = asset.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase(),
                ),
                versionCode = match.groupValues[2].toInt(),
                versionName = match.groupValues[1],
            )
        }
    }

    /**
     * The build to offer instead of [installed], or null when it is up to date.
     *
     * A release counts when its version is higher than the installed one. With [includeTests],
     * so does a test build that Android would install (its version is not lower) and that was
     * published after the installed build was made: test builds share the version of the release
     * they are based on, so only time tells a newer one from an older one. The installed build
     * itself (same commit) never counts. Of what is left, the highest version wins, then the
     * latest published, so a test build never displaces a newer release.
     */
    fun pick(releases: List<Release>, installed: InstalledBuild, includeTests: Boolean): Release? =
        releases
            .filter { it.commit != installed.commit }
            .filter { r ->
                if (!r.prerelease) r.versionCode > installed.versionCode
                else includeTests && r.versionCode >= installed.versionCode && r.publishedAt > installed.commitTime
            }
            .maxWithOrNull(compareBy<Release>({ it.versionCode }, { it.publishedAt }))
}
