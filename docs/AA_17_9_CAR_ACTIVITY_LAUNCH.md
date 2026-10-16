# Android Auto 17.9+：通过 CarActivity 控制器启动投影 Activity

本文记录 AADisplay 对 Android Auto 17.9 启动链路的适配方法，供其它 Xposed 模块移植。适配目标是让 Android Auto 自己的投影控制器启动目标 Activity，而不是从模块 Context 绕过 AA 直接启动。

## 问题与边界

旧版 AA 可以通过 `CarSystemUiControllerService` 的静态 `start(Intent)` 入口打开投影 Activity。AA 17.9 中该 Service 已不存在，旧入口因此解析为 `null`。如果启动逻辑在此处直接 `return`，后续的新版本路径永远不会执行；普通 `startService()` 也不是 AA 投影 Activity 的等价启动方式。

此实现针对 AA 17.9 的实测结构。DexKit 字符串、方法参数、控制器 getter 或 options 构造方式都可能在之后的 AA 版本变化；移植时必须对目标 AA APK 重新确认，不能把混淆类名当稳定 API。

## 启动链路

```text
gearhead :projection
  -> 先解析旧版 CarSystemUiControllerService.start(Intent)
  -> 若旧入口不存在，用 AA APK 的 DexKit 搜索 CarActivity 启动方法
  -> 校验方法并解析当前进程的控制器 getter
  -> 每次启动时取得当前控制器，创建新的 options，再调用 AA 方法
  -> AA 按自己的投影生命周期显示目标 Activity
  -> 收到目标项目的“已显示”回执后停止重试
```

Auto Open 位于 AA 的 `:projection` 进程。AADisplay 的 AA UI 钩子同时支持 `:projection` 和 `:car`，但自动启动只在 `:projection` 安装；不要因两个进程都加载了钩子，就在两个进程同时发起启动。

## 动态定位

AA 17.9 的启动方法包含格式字符串：

```text
startCarActivity(intent:%s, activityOptions:%s)
```

实现用该字符串作为 DexKit 特征，在 AA 的 DEX 中定位方法，不依赖 `mxp` 等混淆类名或方法名：

```kotlin
val matches = bridge.findMethod {
    matcher { usingStrings(START_LOG) }
}
require(matches.size == 1) {
    "Expected one AA CarActivity start method, found ${matches.size}"
}
val method = matches.single().getMethodInstance(aaClassLoader)
val launchTarget = CarActivityLaunchTarget.resolve(method)
```

定位后要验证结构，不能只凭字符串命中就反射调用。当前适配检查：

- 启动方法是实例方法，返回 `void`。
- 参数正好两个；第一个是 `Intent`，第二个是非 primitive 类型。
- 第二个参数类型有无参构造器，用于创建本次启动的 options。
- 方法声明类上恰好有一个 `static`、无参数、返回该声明类本身的方法，作为控制器 getter。

这些约束用于拒绝不唯一或结构不符的候选。若目标 AA 更改了 options 形态，应重新分析并调整校验，不要放宽到任意反射候选。

## 调用控制器

只缓存反射坐标（`Method` / DexKit `MethodRef`），不缓存控制器实例。控制器属于当前 AA 连接会话，重连后可能变化；每次真正启动时都重新取实例，并创建全新的 options：

```kotlin
fun launch(intent: Intent) {
    val controller = checkNotNull(controllerGetter.invoke(null)) {
        "AA projection controller is not ready"
    }
    method.invoke(controller, intent, optionsConstructor.newInstance())
}
```

`intent` 必须是目标项目实际注册、且 AA 能识别的投影 Activity 启动 Intent。移植时应替换 AADisplay 的组件、category、flags 和 extras；不要照搬其它项目的 Intent，也不要把 `startActivity()` 或 `startService()` 当失败兜底。

## 旧版兼容与失败处理

保留旧版入口，并在启动处明确分流：

```kotlin
when {
    legacyStartMethod != null ->
        legacyStartMethod.invoke(null, intent)
    carActivityLaunchTarget != null ->
        carActivityLaunchTarget.launch(intent)
    else ->
        log("AutoOpen unavailable: no AA launch target")
}
```

新入口解析失败时应记录原因，但不能因此中止同一个 AA 钩子中无关的布局、触控或投影处理。当前实现把控制器定位视为可选目标；AA 的其它钩子仍按项目现有的逐钩子隔离机制安装。

重试应沿用目标项目自己的连接时序，并有明确成功回执。AADisplay 收到 `ACTION_AA_DISPLAY_SHOWN` 后停止 Auto Open 重试；独立项目必须换成自己的“Activity 已显示”信号。单纯 `Method.invoke()` 未抛异常只说明调用已发出，不代表车机画面已显示。

## DexKit 缓存

扫描成功后通过现有 DexKit 缓存层保存方法坐标，不保存运行时的 `Method` 或控制器对象。缓存指纹应至少覆盖 AA 版本 / 更新时间、进程和模块版本；命中缓存后仍按当前进程的 class loader 解析，并再次执行结构校验。

新增缓存 key 后，旧缓存缺少该 key 时必须让当前钩子返回 cache miss，触发一次 live DexKit scan，再保存新坐标。若缓存解析或结构校验失败，也应重新扫描，而不是把失效坐标当成有效启动目标。

## 移植验证

1. 对目标 AA APK 做静态检查，确认旧 Service 是否存在，并找到新启动方法中的稳定字符串或其它稳定结构特征。
2. 在 AA 的 `:projection` 进程用其 `ClassLoader` 创建 DexKit bridge；搜索结果必须唯一，再校验方法、getter 和 options 构造器。
3. 冷缓存启动时确认能 live scan 并保存坐标；热缓存启动时确认解析与校验通过。
4. 分别验证旧版走 legacy 分支、新版走 CarActivity 控制器分支；目标 Activity 真正显示后才停止重试。
5. 验证无命中、多命中、方法签名变化和控制器暂未就绪时会有可读日志，且不会拖垮同进程其它钩子。

在 AADisplay 中可用以下日志定位链路：

- `CarActivity start=<class>#<method> (DexKit)` 或 `(cache)`：目标已定位。
- `AutoOpen requested via controller ...`：已请求 AA 控制器启动。
- `ACTION_AA_DISPLAY_SHOWN` 对应的 receiver / stop-retries 日志：目标 Activity 已回执，重试结束。
- `CarActivity start resolve failed`、`AutoOpen unavailable`：检查目标 AA APK 的特征字符串、方法结构及 class loader。

完整 AADisplay 功能还依赖项目自己的 LSPosed scope、启动 Intent、Activity 服务和 AA UI 钩子。移植本启动适配并不自动带入这些依赖，也不代表所有 AA 新版本都兼容。

## AADisplay 参考实现

| 职责 | 源文件 |
|------|--------|
| AA 17.9 控制器目标校验与调用 | `aa-display/src/main/java/io/github/nitsuya/aa/display/xposed/hook/aa/coolwalk/CarActivityLaunchTarget.kt` |
| DexKit 扫描、缓存应用与进程分发 | `aa-display/src/main/java/io/github/nitsuya/aa/display/xposed/hook/aa/AaUiHook.kt` |
| 旧版 Service 入口解析 | `aa-display/src/main/java/io/github/nitsuya/aa/display/xposed/hook/aa/coolwalk/CoolwalkHookEnv.kt` |
| legacy / controller 分流及成功回执 | `aa-display/src/main/java/io/github/nitsuya/aa/display/xposed/hook/aa/coolwalk/AaCoolwalkAutoOpenHook.kt` |
