package me.iacn.biliroaming.hook

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import me.iacn.biliroaming.BiliBiliPackage
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.utils.Log
import me.iacn.biliroaming.utils.findClassOrNull
import me.iacn.biliroaming.utils.hookAllMethods
import me.iacn.biliroaming.utils.hookMethod
import me.iacn.biliroaming.utils.packageName
import me.iacn.biliroaming.utils.sPrefs
import me.iacn.biliroaming.utils.toJSONObject
import kotlin.math.floor

class StartActivityHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    private val splashAdActivities = setOf(
        "tv.danmaku.bili.ui.splash.ad.page.HotSplashActivity",
        "tv.danmaku.bili.splash.ad.page.HotSplashActivity",
        "tv.danmaku.bili.ui.splash.ad.landingpage.SplashImmersiveVideoLandingActivityV2",
        "tv.danmaku.bili.splash.ad.page.landingpage.SplashImmersiveVideoLandingActivityV3",
        "tv.danmaku.bili.splash.shell.SplashShellActivity"
    )
    private val splashAdRoutes = setOf(
        "bilibili://main/hot-splash"
    )

    private fun fixIntentUri(original: Uri): Uri {
        val fixedUri = Uri.parse(original.toString().replace("bilibili://story/", "bilibili://united_video/")).buildUpon()
            .clearQuery()
            .appendQueryParameter("from_spmid", original.getQueryParameter("from_spmid"))
            .appendQueryParameter("aid", original.path?.split("/")?.last() ?: "")
            .appendQueryParameter("bvid", "")
            .build()
        return fixedUri
    }

    private fun shouldPurifySplash() =
        sPrefs.getBoolean("purify_splash", false) && sPrefs.getBoolean("hidden", false)

    private fun matchesSplashAdActivity(className: String?): Boolean {
        if (className.isNullOrEmpty()) return false
        return splashAdActivities.any { target ->
            className == target || className.endsWith(target.removePrefix("tv.danmaku.bili"))
        }
    }

    private fun isSplashAdIntent(intent: Intent): Boolean {
        if (matchesSplashAdActivity(intent.component?.className)) {
            return true
        }
        val dataString = intent.dataString ?: return false
        return splashAdRoutes.any(dataString::startsWith)
    }

    private fun finishSplashActivity(activity: Activity, reason: String) {
        if (activity.isFinishing) return
        Log.d("$reason: ${activity.javaClass.name}, data=${activity.intent?.dataString}")
        activity.overridePendingTransition(0, 0)
        activity.finish()
    }

    override fun startHook() {
        "tv.danmaku.bili.ui.intent.IntentHandlerActivity".hookMethod(mClassLoader, "onCreate", Bundle::class.java) { chain ->
            val a = chain.thisObject as Activity
            val data = a.intent.data ?: return@hookMethod chain.proceed()
            a.intent.data = data.buildUpon().encodedQuery(data.encodedQuery?.replace("&-Arouter=story", "")?.replace("&-Atype=story", "")).build()
            chain.proceed()
        }
        splashAdActivities.asSequence()
            .mapNotNull { it.findClassOrNull(mClassLoader) }
            .distinct()
            .forEach { splashAdActivity ->
                splashAdActivity.hookAllMethods("onCreate") { chain ->
                    val activity = chain.thisObject as Activity
                    val result = chain.proceed()
                    if (shouldPurifySplash()) {
                        finishSplashActivity(activity, "finish splash ad activity")
                    }
                    result
                }
            }
        Instrumentation::class.java.hookAllMethods("execStartActivity") { chain ->
            val intent = chain.args[4] as? Intent ?: return@hookAllMethods chain.proceed()
            if (shouldPurifySplash() && isSplashAdIntent(intent)) {
                (chain.args.getOrNull(3) as? Activity)
                    ?.takeIf { matchesSplashAdActivity(it.javaClass.name) }
                    ?.let { finishSplashActivity(it, "finish splash caller activity") }
                Log.d(
                    "block splash ad launch: component=${intent.component?.className}, " +
                        "data=${intent.dataString}, caller=${chain.args.getOrNull(3)?.javaClass?.name}"
                )
                return@hookAllMethods null
            }

            val uri = intent.dataString
            if (uri != null && sPrefs.getBoolean(
                    "replace_story_video",
                    false
                ) && uri.startsWith("bilibili://story/")
            ) {
                if (instance.hasUnitedVideoActivity) {
                    intent.data?.let {
                        try {
                            val cid = intent.data?.getQueryParameter("player_preload").toJSONObject().getLong("cid")
                            intent.data = fixIntentUri(Uri.parse(intent.dataString))
                            // fix extra
                            val pre = Uri.parse(intent.dataString).buildUpon().clearQuery().build().toString()
                            val aid = pre.split("/").last().toLong()
                            intent.removeExtra("player_preload")
                            intent.putExtra("player_preload", floor(Math.random()*1000000000).toInt().toString())
                            intent.putExtra("blrouter.targeturl", pre)
                            intent.putExtra("blrouter.pagename", "bilibili://united_video/")
                            intent.putExtra("jumpFrom", 7)
                            intent.putExtra("", aid)
                            intent.putExtra("aid", aid)
                            intent.putExtra("cid", cid)
                            intent.putExtra("bvid", "")
                            intent.putExtra("from", 7)
                            intent.putExtra("blrouter.targeturl", pre)
                            intent.putExtra("blrouter.matchrule", "bilibili://united_video/")
                            // fix component
                            intent.component = ComponentName(
                                intent.component?.packageName ?: packageName,
                                "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity"
                            )
                        } catch (e: Exception) {
                            Log.e("replaceStoryVideo fix intent failed!!!")
                            Log.e(e)
                        }
                    }
                    return@hookAllMethods chain.proceed()
                }
                // 兼容旧版
                intent.component = ComponentName(
                    intent.component?.packageName ?: packageName,
                    "com.bilibili.video.videodetail.VideoDetailsActivity"
                )
                intent.data = Uri.parse(uri.replace("bilibili://story/", "bilibili://video/"))
            }
            if (sPrefs.getBoolean("force_browser", false)) {
                if (intent.component?.className?.endsWith("MWebActivity") == true &&
                        intent.data?.authority?.matches(whileListDomain) == false) {
                    Log.d("force_browser ${intent.data?.authority}")
                    val args = chain.args.toTypedArray()
                    args[4] = Intent(Intent.ACTION_VIEW).apply {
                        data = intent.data
                    }
                    return@hookAllMethods chain.proceed(args)
                }
            }
            chain.proceed()
        }
    }
    companion object {
        val whileListDomain = Regex(""".*bilibili\.com|.*b23\.tv""")
    }
}
