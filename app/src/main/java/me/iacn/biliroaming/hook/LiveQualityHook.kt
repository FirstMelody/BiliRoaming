package me.iacn.biliroaming.hook

import android.net.Uri
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.BuildConfig
import me.iacn.biliroaming.utils.*
import org.json.JSONArray
import org.json.JSONObject

class LiveQualityHook(classLoader: ClassLoader) : BaseHook(classLoader) {

    companion object {
        private const val TAG = "LiveQualityHook"
        private val LIVE_PLAY_INFO_URL_PREFIXES = arrayOf(
            "https://api.live.bilibili.com/xlive/app-room/v2/index/getRoomPlayInfo?",
            "https://api.live.bilibili.com/xlive/play-gateway/master/url?"
        )
        private val LIVE_QUALITY_QUERY_KEYS = listOf(
            "qn",
            "current_qn",
            "current_quality",
            "expected_qn"
        )
        private val LIVE_CODEC_QUALITY_QUERY_KEYS = listOf(
            "h265_current_qn",
            "av1_current_qn"
        )
        private val LIVE_SELECTOR_QUALITY_QUERY_KEYS =
            LIVE_QUALITY_QUERY_KEYS + LIVE_CODEC_QUALITY_QUERY_KEYS
    }

    @Volatile
    private var newQn: String = ""

    private fun debug(msg: () -> String) {
        if (BuildConfig.DEBUG) {
            Log.d("[$TAG] - ${msg()}")
        }
    }

    override fun startHook() {
        val liveQuality = sPrefs.getString("live_quality", "0")?.toIntOrNull() ?: 0
        if (liveQuality <= 0) {
            return
        }

        val canSwitchLiveRoom = !sPrefs.getBoolean("forbid_switch_live_room", false)

        instance.defaultRequestInterceptClass?.hookAllMethods(instance.interceptMethod()) { chain ->
            val args = chain.args.toTypedArray()
            val request = args[0]!!
            val httpUrl = request.getObjectField(instance.urlField())
            val url = httpUrl.toString()
            if (!url.isLivePlayInfoUrl()) {
                return@hookAllMethods chain.proceed()
            }

            debug { "oldHttpUrl: $url" }

            val uri = Uri.parse(httpUrl.toString())
            val expectQn = newQn.ifEmpty { liveQuality.toString() }
            if (uri.getQueryParameter("qn") != expectQn) {
                val newHttpUrl = instance.httpUrlClass?.callStaticMethod(
                    instance.httpUrlParseMethod(),
                    uri.withLiveQuality(expectQn).toString()
                )
                debug { "newHttpUrl: $newHttpUrl" }
                request.setObjectField(instance.urlField(), newHttpUrl)
            }
            chain.proceed(args)
        }

        instance.retrofitResponseClass?.hookAllConstructors { chain ->
            val args = chain.args.toTypedArray()
            val url = getRetrofitUrl(args[0]!!) ?: return@hookAllConstructors chain.proceed()
            val body = args[1] ?: return@hookAllConstructors chain.proceed()

            when {
                instance.generalResponseClass?.isInstance(body) != true -> Unit
                // 处理上下滑动切换直播间
                url.startsWith("https://api.live.bilibili.com/xlive/app-interface/v2/room/recList?") && canSwitchLiveRoom -> {
                    val data = body.getObjectField("data") ?: return@hookAllConstructors chain.proceed()
                    val info = JSONObject(
                        instance.fastJsonClass?.callStaticMethod("toJSONString", data).toString()
                    )
                    if (fixLiveRoomFeedInfo(info, liveQuality)) {
                        body.setObjectField(
                            "data",
                            instance.fastJsonClass?.callStaticMethod(
                                instance.fastJsonParse(),
                                info.toString(),
                                data.javaClass
                            )
                        )
                    }
                }

                BuildConfig.DEBUG && url.startsWith("https://api.live.bilibili.com/xlive/app-room/v2/index/getRoomPlayInfo?") -> {
                    val data = body.getObjectField("data") ?: return@hookAllConstructors chain.proceed()
                    val info = JSONObject(
                        instance.fastJsonClass?.callStaticMethod("toJSONString", data).toString()
                    )
                    printCodec(info)
                }
            }
            chain.proceed(args)
        }

        instance.liveRTCSourceServiceImplClass?.hookAllMethods(instance.switchAutoMethod()) { chain ->
            val mode = chain.args[0] ?: return@hookAllMethods chain.proceed()
            val enumMode = mode as? Enum<*>
            if (enumMode?.ordinal == 2 || mode.toString().contains("AUTO", ignoreCase = true)) {
                return@hookAllMethods null
            }
            chain.proceed()
        }

        instance.livePlayUrlSelectUtilClass?.hookMethod(
            instance.buildSelectorDataMethod(),
            Uri::class.java
        ) { chain ->
            val originalUri = chain.args[0] as Uri
            if (!originalUri.isLive()) {
                return@hookMethod chain.proceed()
            }

            debug { "originalLiveUrl: $originalUri" }

            if (originalUri.getQueryParameter("no_playurl") == "1") {
                newQn = originalUri.firstLiveQualityParameter() ?: liveQuality.toString()
            } else {
                newQn = findQualityOrDefault(
                    originalUri.getQueryParameter("accept_quality"),
                    liveQuality
                ).toString()

                val append = mutableMapOf<String, Any>(
                    "no_playurl" to "1",
                    "qn" to newQn,
                    "current_qn" to newQn,
                    "current_quality" to newQn,
                    "expected_qn" to newQn
                )
                LIVE_CODEC_QUALITY_QUERY_KEYS.forEach { name ->
                    if (originalUri.getQueryParameter(name) != null) {
                        append[name] = newQn
                    }
                }

                val args = chain.args.toTypedArray()
                args[0] = originalUri.modified(
                    removeIf = { name ->
                        name.startsWith("playurl")
                                || name == "backup_urls"
                                || name in LIVE_SELECTOR_QUALITY_QUERY_KEYS
                    },
                    append = append,
                    transform = { name, value ->
                        if (name == "master_url") {
                            value.rewriteLivePlayInfoUrl(newQn)
                        } else {
                            value
                        }
                    }
                ).also {
                    debug { "newLiveUrl: $it" }
                }

                debug { "newQn: $newQn" }
                return@hookMethod chain.proceed(args)
            }

            debug { "newQn: $newQn" }
            chain.proceed()
        }
    }

    private fun String.isLivePlayInfoUrl(): Boolean {
        return LIVE_PLAY_INFO_URL_PREFIXES.any { startsWith(it) }
    }

    private fun Uri.isLive(): Boolean {
        return scheme in arrayOf("http", "https")
                && host == "live.bilibili.com"
                && pathSegments.firstOrNull()?.all { it.isDigit() } == true
    }

    private fun Uri.firstLiveQualityParameter(): String? {
        return LIVE_SELECTOR_QUALITY_QUERY_KEYS.asSequence()
            .mapNotNull { name ->
                getQueryParameter(name)?.takeIf { it.isNotBlank() && it != "0" }
            }
            .firstOrNull()
    }

    private fun findQuality(acceptQuality: JSONArray, expectQuality: Int): Int {
        val acceptQnList = acceptQuality.asSequence<Int>().filter { it > 0 }.sorted().toList()
        if (acceptQnList.isEmpty()) {
            return expectQuality
        }
        val max = acceptQnList.max()
        val min = acceptQnList.min()
        return when {
            expectQuality > max -> max
            expectQuality < min -> min
            else -> acceptQnList.first { it >= expectQuality }
        }
    }

    private fun findQualityOrDefault(acceptQuality: String?, expectQuality: Int): Int {
        if (acceptQuality.isNullOrBlank()) {
            return expectQuality
        }
        return runCatching {
            findQuality(JSONArray(acceptQuality), expectQuality)
        }.getOrElse {
            debug { "invalid accept_quality: $acceptQuality" }
            expectQuality
        }
    }

    private fun findQualityOrDefault(acceptQuality: JSONArray?, expectQuality: Int): Int {
        if (acceptQuality == null || acceptQuality.length() == 0) {
            return expectQuality
        }
        return runCatching {
            findQuality(acceptQuality, expectQuality)
        }.getOrElse {
            debug { "invalid accept_quality: $acceptQuality" }
            expectQuality
        }
    }

    private fun Uri.withLiveQuality(qn: String): Uri {
        val newBuilder = buildUpon().clearQuery()
        var hasQn = false
        for (name in queryParameterNames) {
            if (name == "qn") {
                hasQn = true
            }
            val value = if (name in LIVE_QUALITY_QUERY_KEYS) qn else getQueryParameter(name)
            newBuilder.appendQueryParameter(name, value)
        }
        if (!hasQn) {
            newBuilder.appendQueryParameter("qn", qn)
        }
        return newBuilder.build()
    }

    private fun String.rewriteLivePlayInfoUrl(qn: String): String {
        return if (isLivePlayInfoUrl()) {
            Uri.parse(this).withLiveQuality(qn).toString()
        } else {
            this
        }
    }

    private fun Uri.modified(
        removeIf: (String) -> Boolean,
        append: Map<String, Any>,
        transform: (String, String) -> String = { _, value -> value }
    ): Uri {
        val newBuilder = buildUpon().clearQuery()
        for (name in queryParameterNames) {
            val value = getQueryParameter(name) ?: ""
            if (!removeIf(name)) {
                newBuilder.appendQueryParameter(name, transform(name, value))
            }
        }
        append.forEach { (k, v) ->
            newBuilder.appendQueryParameter(k, v.toString())
        }
        return newBuilder.build()
    }

    private fun fixLiveRoomFeedInfo(info: JSONObject, expectQuality: Int): Boolean {
        val feedList = info.optJSONArray("list") ?: return false
        feedList.iterator().forEach { feedData ->
            debug { "oldFeedData: ${feedData.toString(2)}" }
            val newQuality = findQualityOrDefault(feedData.optJSONArray("accept_quality"), expectQuality)
            feedData.apply {
                put("current_qn", newQuality)
                put("current_quality", newQuality)
                put("play_url", "")
                put("play_url_h265", "")
                put("playurl_infos", JSONArray())
            }
            debug { "newFeedData: ${feedData.toString(2)}" }
        }
        return true
    }

    private fun printCodec(info: JSONObject) {
        val playUrlInfo = info.optJSONObject("playurl_info") ?: return
        val playUrlObj = playUrlInfo.getJSONObject("playurl")
        debug { "printCodec >>>>>>>>>>>>>>" }
        playUrlObj.getJSONArray("stream").iterator().forEach { stream ->
            stream.getJSONArray("format").iterator().forEach { format ->
                format.getJSONArray("codec").iterator().forEach {
                    debug { "codec: ${it.toString(2)}" }
                }
            }
        }
        debug { "printCodec <<<<<<<<<<<<<<" }
    }
}
