package me.iacn.biliroaming.hook

import android.net.Uri
import android.os.Looper
import android.os.StrictMode
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.utils.Log
import me.iacn.biliroaming.utils.bv2av
import me.iacn.biliroaming.utils.getObjectField
import me.iacn.biliroaming.utils.hookMethod
import me.iacn.biliroaming.utils.sPrefs
import me.iacn.biliroaming.utils.setObjectField
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class ShareHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    private val contentUrlPattern = Regex("""[\s\S]*(https?://(?:bili2233\.cn|b23\.tv)/\S*)$""")
    private val shortUrlCache = ConcurrentHashMap<String, String>()

    private fun String.isShortShareUrl() =
        startsWith("https://bili2233.cn") || startsWith("http://bili2233.cn") ||
            startsWith("https://b23.tv") || startsWith("http://b23.tv")

    private fun String.normalizeUrl() = runCatching {
        Uri.parse(this).buildUpon().query(null).build().toString()
    }.getOrDefault(this)

    private fun String.resolveB23URL(): String {
        val normalized = normalizeUrl()
        if (!normalized.isShortShareUrl()) return normalized
        shortUrlCache[normalized]?.let { return it }

        val oldPolicy = if (Looper.myLooper() == Looper.getMainLooper()) {
            StrictMode.getThreadPolicy()
        } else {
            null
        }
        var conn: HttpURLConnection? = null
        return try {
            if (oldPolicy != null) {
                StrictMode.setThreadPolicy(
                    StrictMode.ThreadPolicy.Builder(oldPolicy)
                        .permitNetwork()
                        .build()
                )
            }
            conn = (URL(normalized).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = 2000
                readTimeout = 2000
                connect()
            }
            val redirected = when (conn.responseCode) {
                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER,
                307,
                308 -> conn.getHeaderField("Location")
                    ?.let { URL(URL(normalized), it).toString().normalizeUrl() }
                else -> null
            } ?: normalized
            shortUrlCache[normalized] = redirected
            redirected
        } catch (e: Throwable) {
            Log.d(e)
            normalized
        } finally {
            conn?.disconnect()
            if (oldPolicy != null) {
                StrictMode.setThreadPolicy(oldPolicy)
            }
        }
    }

    private fun purifyShareUrl(url: String, transformAv: Boolean): String {
        val normalized = url.normalizeUrl()
        val resolved = if (normalized.isShortShareUrl()) normalized.resolveB23URL() else normalized
        return if (resolved.isShortShareUrl()) normalized else transformUrl(resolved, transformAv)
    }

    private fun transformUrl(url: String, transformAv: Boolean): String {
        val target = Uri.parse(url)
        val bv = if (transformAv) {
            target.path?.split("/")?.firstOrNull { it.startsWith("BV") && it.length == 12 }
        } else {
            null
        }
        val av = bv?.let { "av${bv2av(bv)}" }
        val newUrl = target.buildUpon()
        if (av != null) {
            newUrl.path(target.path!!.replace(bv, av))
        }
        val encodedQuery = target.encodedQuery
        if (encodedQuery != null) {
            val query = encodedQuery.split("&").map {
                it.split("=")
            }.filter {
                it.size == 2
            }.mapNotNull {
                when {
                    it[0] == "p" || it[0] == "t" -> "${it[0]}=${it[1]}"
                    it[0] == "start_progress" -> "start_progress=${it[1]}&t=${it[1].toLong() / 1000}"
                    else -> null
                }
            }.joinToString("&", postfix = "&unique_k=2333")
            newUrl.encodedQuery(query)
        } else {
            newUrl.appendQueryParameter("unique_k", "2333")
        }
        return newUrl.build().toString()
    }

    override fun startHook() {
        val miniProgramEnabled = sPrefs.getBoolean("mini_program", false)
        val purifyShareEnabled = sPrefs.getBoolean("purify_share", false)
        if (!miniProgramEnabled && !purifyShareEnabled) return
        Log.d("startHook: ShareHook")
        instance.shareClickResultClass?.apply {
            if (purifyShareEnabled) {
                hookMethod("getLink") { chain ->
                    val result = chain.proceed()
                    (result as? String)?.takeIf {
                        it.isShortShareUrl()
                    }?.let {
                        it.resolveB23URL().also { r -> chain.thisObject.setObjectField("link", r) }
                    } ?: result
                }
                hookMethod("getContent") { chain ->
                    val result = chain.proceed()
                    val content = result as? String
                    val contentUrl = content?.let {
                        contentUrlPattern.matchEntire(it)?.groups?.get(1)?.value
                    }
                    if (contentUrl != null) {
                        val purifiedUrl =
                            (chain.thisObject!!.getObjectField("link")?.let { it as String } ?: contentUrl)
                                .let { purifyShareUrl(it, miniProgramEnabled) }
                        content.replace(contentUrl, purifiedUrl).also { r ->
                            chain.thisObject.setObjectField("content", r)
                        }
                    } else result
                }
            }
            if (!miniProgramEnabled) return@apply
            // ShareMode Definition
            // 1: PARAMS_TYPE_TEXT
            // 2: PARAMS_TYPE_AUDIO
            // 4: PARAMS_TYPE_VIDEO
            // 5: PARAMS_TYPE_IMAGE
            // 6 / 7: PARAMS_TYPE_MIN_PROGRAM
            // 21: PARAMS_TYPE_PURE_IMAGE
            // Others: PARAMS_TYPE_WEB
            hookMethod("getShareMode") { chain ->
                val result = chain.proceed()
                if (result == 6 || result == 7) {
                    chain.thisObject.apply {
                        getObjectField("title")?.takeIf { it == "哔哩哔哩" }?.let { title ->
                            setObjectField("title", getObjectField("content"))
                            setObjectField("content", "由哔哩漫游分享")
                        }
                        getObjectField("content")?.let { it as String }
                            ?.takeIf { it.startsWith("已观看") }?.let { content ->
                                setObjectField("content", "$content\n由哔哩漫游分享")
                            }
                    }
                    0
                } else result
            }
        }
    }
}
