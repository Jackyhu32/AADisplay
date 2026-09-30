package io.github.nitsuya.aa.display.xposed

import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.utils.Log.logexIfThrow
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.xposed.hook.AaDisplayProcessHook
import io.github.nitsuya.aa.display.xposed.hook.AndroidAutoHook
import io.github.nitsuya.aa.display.xposed.hook.AndroidHook
import io.github.nitsuya.aa.display.xposed.hook.BaseHook
import io.github.nitsuya.aa.display.xposed.util.log

class XposedInit : IXposedHookZygoteInit, IXposedHookLoadPackage{
    companion object {
        const val TAG = "AADisplay_XposedInit"
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        EzXHelperInit.initZygote(startupParam)
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName

        // 【新增】：针对高德地图和百度地图，强制将其音频输出伪装为“导航+人声”
        if (packageName == "com.autonavi.minimap" || packageName == "com.baidu.BaiduMap") {
            de.robv.android.xposed.XposedHelpers.findAndHookMethod(
                "android.media.AudioAttributes\$Builder",
                lpparam.classLoader,
                "build",
                object : de.robv.android.xposed.XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            // 12 = USAGE_ASSISTANCE_NAVIGATION_GUIDANCE (导航)
                            de.robv.android.xposed.XposedHelpers.callMethod(param.thisObject, "setUsage", 12)
                            // 1 = CONTENT_TYPE_SPEECH (语音人声)
                            de.robv.android.xposed.XposedHelpers.callMethod(param.thisObject, "setContentType", 1)
                        } catch (e: Throwable) {
                            de.robv.android.xposed.XposedBridge.log("AADisplay Audio Hook Error: " + e.message)
                        }
                    }
                }
            )
        }

        // 下面是原作者的路由逻辑，保持完全不变
        when {
            packageName == "android" && lpparam.appInfo == null -> arrayOf(AndroidHook)
            packageName == "com.google.android.projection.gearhead" -> arrayOf(AndroidAutoHook)
            packageName == "io.github.nitsuya.aa.display" -> arrayOf(AaDisplayProcessHook)
            else -> null
        }?.also {
            initHooks(lpparam, *it)
        }
    }

    private fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam, vararg hook: BaseHook) {
        EzXHelperInit.initHandleLoadPackage(lpparam)
        hook.forEach {
            runCatching {
                if (it.isInit) return@forEach
                it.init(lpparam)
                it.isInit = true
                log(TAG, "Inited hook: ${it.tagName}")
            }.logexIfThrow("Failed init hook: ${it.tagName}")
        }
    }
}
