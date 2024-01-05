package eu.kanade.tachiyomi.extension.api

import android.content.Context
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import exh.source.BlacklistedSources
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.preference.Preference
import tachiyomi.core.preference.PreferenceStore
import tachiyomi.core.util.lang.withIOContext
import tachiyomi.core.util.system.logcat
import uy.kohesive.injekt.injectLazy
import java.time.Instant
import kotlin.time.Duration.Companion.days

internal class ExtensionGithubApi {

    private val networkService: NetworkHelper by injectLazy()
    private val preferenceStore: PreferenceStore by injectLazy()
    private val sourcePreferences: SourcePreferences by injectLazy()
    private val extensionManager: ExtensionManager by injectLazy()
    private val json: Json by injectLazy()

    private val lastExtCheck: Preference<Long> by lazy {
        preferenceStore.getLong(Preference.appStateKey("last_ext_check"), 0)
    }

    private var requiresFallbackSource = false

    suspend fun findExtensions(): List<Extension.Available> {
        return withIOContext {
            val githubResponse = if (requiresFallbackSource) {
                null
            } else {
                try {
                    networkService.client
                        .newCall(GET("${REPO_URL_PREFIX}index.min.json"))
                        .awaitSuccess()
                } catch (e: Throwable) {
                    logcat(LogPriority.ERROR, e) { "Failed to get extensions from GitHub" }
                    requiresFallbackSource = true
                    null
                }
            }

            val response = githubResponse ?: run {
                networkService.client
                    .newCall(GET("${FALLBACK_REPO_URL_PREFIX}index.min.json"))
                    .awaitSuccess()
            }

            val extensions = with(json) {
                response
                    .parseAs<List<ExtensionJsonObject>>()
                    .toExtensions() + sourcePreferences.extensionRepos()
                    .get()
                    .flatMap { repoPath ->
                        val url = if (requiresFallbackSource) {
                            "$FALLBACK_BASE_URL$repoPath@repo/"
                        } else {
                            "$BASE_URL$repoPath/repo/"
                        }
                        networkService.client
                            .newCall(GET("${url}index.min.json"))
                            .awaitSuccess()
                            .parseAs<List<ExtensionJsonObject>>()
                            .toExtensions(url, repoSource = true)
                    }
            }

            // Sanity check - a small number of extensions probably means something broke
            // with the repo generator
            if (extensions.size < 100) {
                throw Exception()
            }

            extensions
        }
    }

    suspend fun checkForUpdates(
        context: Context,
        fromAvailableExtensionList: Boolean = false,
    ): List<Extension.Installed>? {
        // Limit checks to once a day at most
        if (!fromAvailableExtensionList &&
            Instant.now().toEpochMilli() < lastExtCheck.get() + 1.days.inWholeMilliseconds
        ) {
            return null
        }

        val extensions = if (fromAvailableExtensionList) {
            extensionManager.availableExtensionsFlow.value
        } else {
            findExtensions().also { lastExtCheck.set(Instant.now().toEpochMilli()) }
        }

        // SY -->
        val blacklistEnabled = sourcePreferences.enableSourceBlacklist().get()
        // SY <--

        val installedExtensions = ExtensionLoader.loadExtensions(context)
            .filterIsInstance<LoadResult.Success>()
            .map { it.extension }
            // SY -->
            .filterNot { it.isBlacklisted(blacklistEnabled) }
        // SY <--

        val extensionsWithUpdate = mutableListOf<Extension.Installed>()
        for (installedExt in installedExtensions) {
            val pkgName = installedExt.pkgName
            val availableExt = extensions.find { it.pkgName == pkgName } ?: continue
            val hasUpdatedVer = availableExt.versionCode > installedExt.versionCode
            val hasUpdatedLib = availableExt.libVersion > installedExt.libVersion
            val hasUpdate = installedExt.isUnofficial.not() && (hasUpdatedVer || hasUpdatedLib)
            if (hasUpdate) {
                extensionsWithUpdate.add(installedExt)
            }
        }

        if (extensionsWithUpdate.isNotEmpty()) {
            ExtensionUpdateNotifier(context).promptUpdates(extensionsWithUpdate.map { it.name })
        }

        return extensionsWithUpdate
    }

    private fun List<ExtensionJsonObject>.toExtensions(
        repoUrl: String = getUrlPrefix(),
        repoSource: Boolean = false,
    ): List<Extension.Available> {
        return this
            .filter {
                val libVersion = it.extractLibVersion()
                libVersion >= ExtensionLoader.LIB_VERSION_MIN && libVersion <= ExtensionLoader.LIB_VERSION_MAX
            }
            .map {
                Extension.Available(
                    name = it.name.substringAfter("Tachiyomi: "),
                    pkgName = it.pkg,
                    versionName = it.version,
                    versionCode = it.code,
                    libVersion = it.extractLibVersion(),
                    lang = it.lang,
                    isNsfw = it.nsfw == 1,
                    sources = it.sources?.map(extensionSourceMapper).orEmpty(),
                    apkName = it.apk,
                    iconUrl = "${repoUrl}icon/${it.pkg}.png",
                    repoUrl = repoUrl,
                    isRepoSource = repoSource,
                )
            }
    }

    fun getApkUrl(extension: Extension.Available): String {
        return "${extension.repoUrl}/apk/${extension.apkName}"
    }

    private fun getUrlPrefix(): String {
        return if (requiresFallbackSource) {
            FALLBACK_REPO_URL_PREFIX
        } else {
            REPO_URL_PREFIX
        }
    }

    private fun ExtensionJsonObject.extractLibVersion(): Double {
        return version.substringBeforeLast('.').toDouble()
    }

    // SY -->
    private fun Extension.isBlacklisted(
        blacklistEnabled: Boolean = sourcePreferences.enableSourceBlacklist().get(),
    ): Boolean {
        return pkgName in BlacklistedSources.BLACKLISTED_EXTENSIONS && blacklistEnabled
    }
    // SY <--
}

private const val BASE_URL = "https://raw.githubusercontent.com/"
private const val REPO_URL_PREFIX = "${BASE_URL}tachiyomiorg/tachiyomi-extensions/repo/"
private const val FALLBACK_BASE_URL = "https://gcore.jsdelivr.net/gh/"
private const val FALLBACK_REPO_URL_PREFIX = "${FALLBACK_BASE_URL}tachiyomiorg/tachiyomi-extensions@repo/"

@Serializable
private data class ExtensionJsonObject(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    val code: Long,
    val version: String,
    val nsfw: Int,
    val sources: List<ExtensionSourceJsonObject>?,
)

@Serializable
private data class ExtensionSourceJsonObject(
    val id: Long,
    val lang: String,
    val name: String,
    val baseUrl: String,
)

private val extensionSourceMapper: (ExtensionSourceJsonObject) -> Extension.Available.Source = {
    Extension.Available.Source(
        id = it.id,
        lang = it.lang,
        name = it.name,
        baseUrl = it.baseUrl,
    )
}
