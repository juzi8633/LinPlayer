# 11. Android 动态加载 GeckoView(D375)—— spike 结论

> 2026-09-21。到期:阶段 ② 前(逾期)。
> 结论:**不做**。D375 的「下载 GeckoView 动态加载」改成「缺 WebView 就明说不可用,并指路装系统 WebView」。
> 新决定 D560。

## 这条 spike 要回答什么

D375:「Android 系统 WebView 缺失或过旧时,提供可下载的内核组件(GeckoView):
下载到应用私有目录动态加载,不动系统,约 50MB+……安卓动态加载浏览器内核无标准做法,**先 spike**。」

要回答两件事:**能不能加载**、**各盒子兼容不兼容**。

## 已验证的(本仓库里就有证据)

| 事实 | 证据 |
|---|---|
| 从应用私有目录动态加载 **dex** 这条路本仓库已经在走 | `apps/android/.../plugin/SpiderService.kt:84`(`DexClassLoader` 跑 TVBox jar) |
| 这条路的已知坑已经踩过:Android 14 起动态加载的 dex 必须**只读** | 同文件 `:115` |
| 动态代码跑在**独立进程**里,崩了不带倒主进程 | `SpiderService.kt:29`(`:spider` 子进程,D354) |
| 测试用的 Android TV 模拟器上系统 WebView 在位 | `adb shell dumpsys webviewupdate`:`com.google.android.webview 143.0.7499.24`,`Any WebView package installed: true` |
| 缺 WebView 时宿主已经能如实上报不可用 | `apps/android/.../plugin/WebShell.kt:54`(`getDefaultUserAgent` 抛异常 → `available=false`)→ `plugin.setCapabilities` → `app.capabilities.webview === false` |

## 没验证的,以及为什么不继续验

GeckoView 与 jar spider **不是同一类东西**,前面那条已跑通的路接不上它:

1. **它不是一包 dex。** GeckoView 的主体是原生的 `libxul.so`,外加一整套
   assets/resources。dex 那半 `DexClassLoader` 能装,原生那半得 `System.load`
   一个私有目录里的 `.so`,而 GeckoView 自己的加载器是按「我是 APK 的一个构建期依赖」
   写的 —— 它从 `ApplicationInfo.nativeLibraryDir` 和自己的 assets 找东西。
   要让它从别处加载,等于自己维护一份它的加载器补丁,并且**每次它升版都要重做**。
2. **体积按 ABI 翻倍。** 盒子上 armeabi-v7a 与 arm64 都要覆盖,而这类设备恰恰是
   存储最紧张的一档 —— 这个方案的受众和它的代价是同一批人。
3. **「各盒子兼容性」这半本来就验不了。** 手上没有盒子,只有模拟器;
   模拟器上验出来的「能加载」对 AOSP 定制盒子没有签字效力
   (本仓库的老教训:拿一台服务器的结论替另一台签字)。
   而这条 spike 的价值**全部**在这半上 —— 另一半(能不能加载)的答案已经是「要改它的加载器」。

## 结论与替代方案

**不做动态加载 GeckoView。** 缺 WebView / WebView 过旧时:

- `app.capabilities.webview === false`(已实现,`WebShell.kt:54`),插件调 `webview.*` 抛 `unsupported`,自行降级 —— 这条 SPEC 5.10 本来就写着;
- 界面上说人话:「这台设备没有可用的系统 WebView(嗅探、内嵌网页用得到)」,
  并给一条**可操作**的出路:去应用商店装 / 更新 Android System WebView 或 Chrome。
  比一个装不上也修不好的 100MB 组件有用。
- TVBox 那边靠 WebView 的只有**嗅探**一路(D59);jar / drpy / 苹果CMS / type4 四类源都不经过它,
  所以缺 WebView 的盒子不是「用不了」,是「少一类源」。

## 什么时候回头重做这条

同时满足两条才值得再开:

1. 手上**真有**两台以上缺 WebView 的盒子,能验兼容性(不是模拟器);
2. 上游出现**官方支持「作为可下载组件加载」**的内核(GeckoView 现在没有这个模式)。

在那之前,这条记为「已评估,不做」,而不是「待办」。
