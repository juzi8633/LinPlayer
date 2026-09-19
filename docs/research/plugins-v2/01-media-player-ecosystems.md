# 媒体播放器插件生态横评

调研日期：2026-09-19  
调研对象：Kodi、Jellyfin、Stremio、mpv、IINA、VLC、Plex

---

## 1. Kodi add-ons (Python)

**语言与运行时**

- **语言**：Python（推荐）+ C++（高性能模块）
- **运行时**：Kodi 内置 Python 环境
- **进程**：插件运行在 Kodi 进程内
- **文档**：https://kodi.wiki/view/Add-on_development

**扩展点清单**

Kodi 插件通过 `addon.xml` 中的 `<extension>` 标签声明扩展类型：

- `xbmc.python.pluginsource`：媒体源、列表浏览器
- `xbmc.python.scraper`：元数据抓取（已被服务端体系替代）
- `xbmc.python.service`：后台服务
- `xbmc.python.script`：独立脚本、工具
- `xbmc.ui.music`、`xbmc.ui.screensaver` 等：UI 扩展
- `xbmc.inputstream`：直播/加密流解码器

**最小 addon.xml 例子**

```xml
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<addon id="plugin.video.example" name="Example" version="1.0.0">
  <extension point="xbmc.python.pluginsource">
    <provide>video</provide>
  </extension>
  <extension point="xbmc.addon.metadata">
    <summary>Example Plugin</summary>
  </extension>
</addon>
```

**权限与沙箱**

- **权限**：无显式权限声明机制
- **沙箱**：无沙箱隔离；插件有 Kodi 进程的完整权限（文件 I/O、网络、执行命令）
- **用户控制**：可在设置中禁用不信任的插件

**分发与市场**

- **官方仓库**：https://github.com/xbmc/repo-plugins（审核流程）
- **第三方仓库**：分散的独立仓库（无审核）
- **更新**：手动或自动（取决于仓库配置）
- **签名**：官方仓库有签名验证，第三方无要求

**生态痛点与失败教训**

https://forum.kodi.tv/showthread.php?tid=119117（Kodi 官方盗版政策）

- **盗版与法律风险**：大量第三方插件用于非法流媒体（IPTV 盗版、种子），导致法律诉讼（如 Covenant 插件被关闭）
- **Kodi 项目的声誉受损**：官方被迫在论坛禁讨盗版、封禁相关链接
- **恶意插件**：第三方仓库存在数据采集、广告注入等风险
- **维护困难**：插件作者不活跃导致功能失效；用户需手动排查故障

**对 LinPlayer 的启示**

- **避免**：无沙箱设计会导致信任问题和法律风险；应从一开始就避免与盗版生态关联
- **可学习**：扩展点的分类方式（pluginsource/scraper/service）清晰易懂
- **值得注意**：官方仓库 + 第三方仓库的二元论不足以对抗恶意插件；需更强的审核或沙箱


---

## 2. Jellyfin 插件 (.NET 服务端)

**语言与运行时**

- **语言**：C#（主要）、F#、Visual Basic 或任何编译到 .NET 的语言
- **运行时**：.NET 8.0+
- **进程**：插件运行在 Jellyfin 服务器进程内
- **文档**：https://github.com/jellyfin/jellyfin-plugin-template

**扩展点清单**

Jellyfin 插件通过继承接口实现扩展（C# 反射驱动）：

- `IServerEntryPoint`：服务器启动时运行，常驻内存
- `IPluginConfigurationPage`：仪表盘配置界面（HTML）
- `BaseController`：自定义 REST API 端点（ASP.NET Web API）
- `IProviderBase`：元数据提供者（扩展点不稳定，版本差异大）
- `ITaskTrigger`：计划任务

**最小 csproj 例子**

```xml
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <TargetFramework>net9.0</TargetFramework>
    <RootNamespace>Jellyfin.Plugin.Example</RootNamespace>
  </PropertyGroup>
  <ItemGroup>
    <PackageReference Include="jellyfin-core" Version="10.9.0" />
  </ItemGroup>
</Project>
```

入口类继承 `BaseEntryPoint` 并在 `Load()` 中初始化。

**权限与沙箱**

- **权限**：无显式声明；插件有 `IAdministrationManager` 可获取管理权限
- **沙箱**：无沙箱隔离；插件运行在 Jellyfin 进程，可访问数据库、文件系统、网络
- **安全警告**：官方文档明确指出"管理员级权限不可避免地引入安全风险"；用户需谨慎授权

**分发与市场**

- **官方仓库**：https://repo.jellyfin.org/（社区维护）
- **部署**：`.dll` 手动放入 `plugins` 文件夹或通过仪表盘上传
- **更新**：仪表盘自动检测和更新
- **签名**：无签名验证

**生态痛点与失败教训**

https://github.com/jellyfin/jellyfin/discussions（Jellyfin GitHub Discussion）

- **权限模型不足**：所有插件都有完整的服务器权限（数据库、用户认证数据）
- **自动更新风险**：无签名验证，更新失控可能被恶意代码劫持
- **版本不兼容**：Jellyfin 版本更新频繁，插件适配成本高；许多插件因版本差异无法使用
- **缺乏隔离**：插件可以直接修改数据库、缓存、配置，无法恢复

**对 LinPlayer 的启示**

- **避免**：直接给插件进程权限会导致数据安全问题；必须从架构层面做隔离
- **可学习**：`IServerEntryPoint` 的想法不错（一次初始化），但权限控制须更细粒度
- **值得注意**：自动更新必须配合签名验证，否则是后门


---

## 3. Stremio addon protocol (HTTP + JSON)

**语言与运行时**

- **语言**：任何可提供 HTTP 服务的语言（Node.js、Python、Go、C# 等）
- **运行时**：独立的 HTTP 服务器或 Stremio 客户端中的沙箱
- **进程**：插件以独立服务运行，Stremio 客户端通过 HTTP 调用
- **文档**：https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md

**扩展点清单**

Stremio addon 通过 HTTP 端点提供资源，manifest.json 中声明：

- `catalog`：媒体列表（主页、分类、搜索）
- `meta`：详细元数据（剧情、演员、评分、海报）
- `stream`：播放源（URL、磁力链、直播流）
- `subtitles`：字幕轨道与语言信息

**最小 manifest.json 例子**

```json
{
  "id": "org.example.addon",
  "version": "1.0.0",
  "name": "Example Addon",
  "types": ["movie", "series"],
  "catalogs": [
    {
      "type": "movie",
      "id": "top"
    }
  ],
  "resources": ["catalog", "meta", "stream"]
}
```

客户端请求格式：`/{resource}/{type}/{id}.json` 或 `/{resource}/{type}/{id}/{extra}.json`

**权限与沙箱**

- **权限**：无权限模型；addon 通过 HTTP 通信，客户端拉取数据
- **沙箱**：网络隔离（addon 与 Stremio 不共享进程）；但 addon 内部无沙箱
- **用户控制**：用户需手动添加 addon URL；可禁用或移除

**分发与市场**

- **官方市场**：https://www.stremio.com/addons（轻量级列表，无签名验证）
- **部署**：Addon URL 粘贴，HTTP 端点暴露（公网或 LAN）
- **更新**：客户端定期查询 manifest.json，无版本控制
- **签名**：无签名验证，完全基于信任

**生态痛点与失败教学**

https://dev.to/githubopensource/ditch-the-juggling-act-why-stremios-open-source-addon-ecosystem-is-the-future-of-streaming-831

- **重复内容**：多个 addon 返回同一内容，主屏幕重复率高
- **排序不可预测**：客户端并行查询所有 addon，结果顺序不确定
- **可靠性问题**：单个 addon 故障（API 关闭、超时）影响全局；无超时控制或快速失败
- **无签名验证**：URL 安装流程无任何验证；恶意 URL 可直接注入
- **低端设备性能差**：catalog 加载所有元数据到内存，预算 Android TV 卡顿

**对 LinPlayer 的启示**

- **可学习**：HTTP + 无状态设计的简洁性；独立进程天然隔离
- **避免**：盲目并行查询所有源导致不可控的性能；需排优先级或超时策略
- **值得注意**：URL 安装无验证极其危险；LinPlayer 应实现签名验证或官方库审核


---

## 4. mpv 用户脚本 (Lua / JavaScript)

**语言与运行时**

- **语言**：Lua（推荐）或 JavaScript
- **运行时**：Lua 5.1 / JavaScript 引擎（内置）
- **进程**：脚本运行在 mpv 进程内
- **文档**：https://github.com/mpv-player/mpv/blob/master/DOCS/man/lua.rst

**扩展点清单**

mpv 脚本通过 `mp` 模块访问播放器 API：

- **属性观察** (`mp.observe_property()`)：监听 pause、volume、fullscreen 等变化
- **按键绑定** (`mp.add_key_binding()`)：注册快捷键回调
- **OSD 绘制** (`mp.create_osd_overlay()`)：屏幕显示文本、进度条等
- **命令执行** (`mp.command()`)：执行播放器命令（seek、toggle-pause）
- **事件处理** (`mp.register_script_message()`)：响应来自其他脚本的消息
- **文件 I/O** (`mp.utils`)：读写本地文件、列表处理

**最小脚本例子**

```lua
local function on_pause_change(name, value)
    if value == true then
        mp.osd_message("已暂停")
    end
end
mp.observe_property("pause", "bool", on_pause_change)
mp.add_key_binding("x", "toggle-something", function()
    mp.command("cycle fullscreen")
end)
```

**权限与沙箱**

- **权限**：无权限模型；脚本有完整的 mpv API 访问权
- **沙箱**：无沙箱隔离；脚本可执行系统命令 (`mp.utils.subprocess()`)、读写文件、访问网络
- **用户控制**：脚本从 `~/.config/mpv/scripts/` 自动加载；可删除禁用

**分发与市场**

- **官方库**：无官方市场；社区 GitHub 仓库（如 https://github.com/jonniek/mpv-scripts）
- **部署**：`.lua` / `.js` 文件放入 `scripts/` 目录
- **更新**：手动（git clone 或下载）
- **签名**：无签名验证

**生态痛点与失败教训**

https://github.com/mpv-player/mpv/wiki/User-Scripts

- **无沙箱导致信任问题**：脚本可执行任意命令、访问所有文件
- **不同版本 API 差异**：mpv 更新可能改变 API，旧脚本失效
- **没有官方市场**：社区分散，发现新脚本困难
- **版本号无语义约束**：mpv 和脚本版本匹配靠手工
- **并发问题**：多个脚本同时修改同一属性导致竞态

**对 LinPlayer 的启示**

- **可学习**：属性观察 + 事件驱动的响应式编程模型很优雅
- **避免**：没有沙箱的脚本系统在移动端和多用户场景下是安全灾难
- **值得注意**：mpv 成功的原因之一是用户基数少、都是高级用户；LinPlayer 的用户群更大，沙箱是必须


---

## 5. IINA 插件 (JavaScript)

**语言与运行时**

- **语言**：JavaScript（ES6 子集，版本依赖 macOS 版本）
- **运行时**：JavaScriptCore（Safari 同样引擎）
- **进程**：插件运行在 IINA 进程内（Swift/Objective-C 包装）
- **文档**：https://docs.iina.io/pages/getting-started

**扩展点清单**

IINA 插件通过 `iina` 对象与播放器交互：

- **菜单扩展** (`iina.menu`)：在插件菜单添加菜单项和快捷键
- **OSD 绘制**：创建 webview 覆盖层在视频上方
- **侧边栏标签页** (`iina.sidebar`)：webview 标签页显示内容
- **独立窗口**：webview 弹窗展示复杂 UI
- **mpv API**：完整访问 mpv 属性、命令（IINA 基于 mpv）
- **文件 I/O**：沙箱化的 `~/Library/Application Support/IINA/` 目录

**最小 Info.json 例子**

```json
{
  "identifier": "org.iina.example",
  "version": "1.0.0",
  "name": "Example Plugin",
  "author": "Example",
  "homepage": "https://example.com",
  "pluginVersion": 2
}
```

目录结构：`plugin.iinaplugin/Info.json` + `main.js`（必须）或 `global.js`

**权限与沙箱**

- **权限**：Info.plist 中声明 macOS 沙箱权限（如 `com.apple.security.files.user-selected.read-only`）
- **沙箱**：macOS App Sandbox（每个插件沙箱隔离，跨插件通信受限）
- **用户控制**：从仪表盘启用/禁用插件；记录权限和版本

**分发与市场**

- **官方市场**：https://iina.io/plugins/ （社区上传）
- **部署**：`.iinaplugin` 包双击安装或手动放入 `~/Library/Application Support/IINA/Plugins/`
- **更新**：手动或通过仪表盘检测
- **签名**：官方市场无强制签名（但支持代码签名）

**生态痛点与失败教训**

https://iina.io/release-note/1.4.0.html

- **macOS 版本兼容性**：JavaScriptCore 版本随 macOS 变化，ES6 支持不一致（需用 Babel 转译）
- **多实例架构复杂**：每个 IINA 窗口有独立 mpv + 独立插件实例，状态共享困难
- **沙箱权限繁琐**：开发者需显式声明每项权限（文件读写、网络、进程通信）
- **文档不完整**：API 文档遗漏某些功能；社区插件少

**对 LinPlayer 的启示**

- **可学习**：多实例架构（每个窗口独立插件）防止状态污染；但实现复杂
- **可学习**：沙箱权限显式声明比无沙箱好，但比 Electron 沙箱不够灵活
- **避免**：macOS 版本碎片化的痛苦；如果跨平台必须版本兼容性测试


---

## 6. VLC Lua 扩展 (extensions / playlist / meta)

**语言与运行时**

- **语言**：Lua
- **运行时**：Lua 5.1（内置）
- **进程**：脚本运行在 VLC 进程内
- **文档**：https://github.com/nima64/VLC-3-lua-extension-guide (官方文档已过时)

**扩展点清单**

VLC Lua 脚本有四种类型，通过 `descriptor()` 函数声明：

- **extensions**：主UI扩展，创建新的对话窗口，访问完整 VLC API
- **playlist**：播放列表解析和 URL 转换（如 M3U 转流URL）
- **meta**：元数据读取（从文件或网络获取海报、标题等）
- **interface**：VLC 启动时运行的后台界面（已弃用）

**最小扩展例子**

```lua
function descriptor()
    return {
        title = "My Extension",
        version = "1.0",
        author = "Example",
        url = "https://example.com"
    }
end

function activate()
    -- extension 启动时调用
end

function deactivate()
    -- extension 关闭时调用
end

function trigger_menu(id)
    -- 菜单项被点击
    vlc.dialog.create("Example", "Hello!")
end
```

**权限与沙箱**

- **权限**：无显式权限模型；脚本访问 `vlc` 全局对象的所有可用模块
- **沙箱**：有基础 Lua 沙箱（VLC 源码包含 `share/lua/modules/sandbox.lua`），但实施不完整
- **模块限制**：某些模块（如 `io`、`os`）可能被禁用，但取决于 VLC 构建配置

**分发与市场**

- **官方库**：VLC 官方网站有扩展列表；无强制审核
- **部署**：`.lua` 文件放入 `%APPDATA%\vlc\lua\extensions\` (Windows) 或 `~/.local/share/vlc/lua/extensions/` (Linux)
- **更新**：手动
- **签名**：无签名验证

**生态痛点与失败教训**

https://github.com/nima64/VLC-3-lua-extension-guide

- **文档老化**：官方 Lua 文档已过时（VLC 3+ 多次改版），社区指南填补空白
- **沙箱形同虚设**：Lua sandbox 模块存在但不强制使用；脚本可能绕过限制
- **API 不稳定**：VLC 升级（3.x → 4.x）改变 Lua API，插件失效
- **活跃度低**：VLC 用户多但 Lua 脚本社区小；新手文档缺

**对 LinPlayer 的启示**

- **教训**：沙箱形同虚设等同于没有沙箱；不要假设文档会强制执行
- **避免**：Lua 运行时的碎片化（不同 VLC 版本打包不同 Lua 版本）
- **值得注意**：extension 类型的"创建窗口" API 是强大的，但需细粒度权限控制


---

## 7. Plex 插件与 Agents (Python 已弃用)

**历史背景**

Plex 曾支持两种扩展机制：

1. **Agents**（元数据抓取）：Python 脚本，运行在 Plex 服务器
2. **Plug-Ins**（UI 和功能扩展）：也是 Python，已完全弃用

**已弃用的 Agents 设计**

- **语言**：Python
- **运行时**：Plex 内置 Python 2.7（后期支持 3.x）
- **进程**：运行在 Plex Media Server 进程内
- **扩展点**：元数据查询（影片、剧集、歌曲等）

最小 Agent 包含 `Agent.py`，继承 `MetadataAgent` 类，实现 `search()` 和 `update()` 方法：

```python
class AgentExample(Agent.Movies):
    name = "Example Agent"
    def search(self, results, media, lang="en", manual=False):
        # 搜索逻辑
        pass
    def update(self, metadata, media, lang="en"):
        # 更新元数据
        pass
```

**为什么 Agents 被弃用**

https://www.howtogeek.com/plex-is-overhauling-custom-metadata-providers/

- **技术债**：Python 2.7 生命周期结束；Python 3 迁移复杂
- **维护成本**：社区 Agent 维护困难；API 变更频繁导致插件失效
- **安全风险**：Agent 有完整的 Plex 服务器权限（数据库、用户数据）
- **不灵活**：Python-only 限制了语言选择；无跨语言标准化

**新方向：Custom Metadata Providers (2026)**

Plex 宣布用"自定义元数据提供者"替代 Agents，设计尚未完全公开：

- **开放协议**：不限制语言，任何能提供 HTTP API 的服务都可
- **时间表**：2026 年全面替代（目前仍过渡期）
- **特性**：支持远程服务、无需在 Plex 进程运行

**权限与沙箱**

- **旧 Agents**：无沙箱；有完整数据库和用户数据访问
- **新 Providers**：设计上应该远程隔离，但细节未确认

**生态痛点与失败教训**

https://forums.plex.tv/t/3rd-party-metadata-agents/931920

- **弃用的长尾效应**：用户仍在用过时 Agent；升级 Plex 版本导致功能瘫痪
- **迁移路径不清**：官方没有自动转换工具；开发者需手工迁移
- **社区信任流失**：官方突然弃用导致开发者不再投入
- **元数据错位**：新旧系统并存期间，同一库可能使用不同 Provider

**对 LinPlayer 的启示**

- **教训**：不要承诺向后兼容然后又弃用；会损伤社区信任
- **可学习**：从 Python-only 转向开放协议的决定正确，但执行太晚
- **值得注意**：插件/Agent 的版本生命周期必须清晰且遵守语义版本号；否则用户升级时功能爆炸


---

## 横向对比表

| 维度 | Kodi | Jellyfin | Stremio | mpv | IINA | VLC | Plex (已弃) |
|-----|------|---------|---------|-----|------|-----|-----------|
| **语言** | Python/C++ | C#/.NET | 任意(HTTP) | Lua/JS | JS | Lua | Python |
| **运行时** | Kodi 内置 | .NET 8+ | 独立服务 | mpv 内置 | JavaScriptCore | Lua 5.1 | Plex 内置 |
| **进程** | 同进程 | 同进程 | 独立 | 同进程 | 同进程 | 同进程 | 同进程 |
| **沙箱** | ❌ 无 | ❌ 无 | ✅ 网络隔离 | ❌ 无 | ⚠️ 有限 | ⚠️ 形同虚设 | ❌ 无 |
| **权限模型** | ❌ 无 | ❌ 无 | 🔸 隐式 | ❌ 无 | ✅ 显式声明 | ❌ 无 | ❌ 无 |
| **签名验证** | 部分(官方库) | ❌ 无 | ❌ 无 | ❌ 无 | ❌ 无 | ❌ 无 | ❌ 无 |
| **官方市场** | ✅ 有 | ✅ 有 | ✅ 轻量级 | ❌ 无 | ✅ 有 | ✅ 有 | ✅ 有(已关) |
| **自动更新** | ⚠️ 可选 | ✅ 默认 | ✅ 自动 | 手动 | 手动 | 手动 | ✅ 默认 |
| **文档质量** | ✅ 中等 | ✅ 中等 | ✅ 好 | ✅ 好 | ⚠️ 欠缺 | ❌ 过时 | ✅ 好(已弃) |
| **主要痛点** | 盗版/法律 | 权限过大 | 无排序/重复 | 无沙箱 | 版本碎片 | 文档陈旧 | 信任流失 |

---

## 对 LinPlayer 的启示总结

### 必须做的事（非协商）

1. **沙箱隔离是必须的**
   - mpv 和 VLC 的无沙箱设计只能存活在小众社区
   - LinPlayer 目标是大众用户（移动端、普通家庭），沙箱不可选
   - 参考 IINA（macOS Sandbox）的实施，或用进程隔离 + IPC（如 Stremio 的 HTTP 分离）

2. **权限模型要显式声明**
   - 不要学 Kodi/Jellyfin/VLC（无权限模型）
   - 学 IINA：Info.json 中声明需要的权限（文件、网络、媒体库访问等）
   - 用户安装插件时要弹窗确认，且保留"忽略此插件"的选项

3. **签名验证 + 官方市场**
   - Stremio 的"URL 粘贴无验证"是教科书级反面教材
   - 建立官方市场（Github Actions 自动审核 + 签名）
   - 或学 IINA：仪表盘内置市场列表，用户点击一键安装（经过审核的版本）

4. **版本兼容性承诺要写成约束条款**
   - Jellyfin 的"版本碎片"和 Plex 的"突然弃用"都源于没有版本契约
   - 定义：LinPlayer 主版本号 N 的插件至少支持到 N+1（或声明最低版本）
   - 如果要改 API，给 2 个次版本号的过渡期警告

### 值得学习的设计

1. **Stremio 的 HTTP + 无状态模式**
   - 优点：插件与客户端完全解耦；易部署和扩展
   - 缺点：无本地状态共享；通信延迟
   - 应用场景：远程数据源（弹幕服务、元数据聚合）

2. **Kodi 的扩展点分类**
   - `pluginsource`（媒体源）、`scraper`（元数据）、`service`（后台）的分离很清晰
   - 易于理解和审核（每种扩展点的权限和 API 都不同）

3. **Jellyfin 的仪表盘集成市场**
   - 插件列表、安装、配置都在一个地方
   - 降低用户门槛

### 要避免的坑

1. **不要承诺向后兼容然后又弃用（Plex 的教训）**
   - 开发者会等待数年才发现 API 已死
   - 要么承诺长期支持，要么从一开始就预留改版空间

2. **不要让插件有完整进程权限（Jellyfin/Kodi 的教训）**
   - 即使是"高级用户"也会点击恶意插件
   - 无沙箱 = 一个插件被攻击整个 LinPlayer 崩溃或数据泄露

3. **不要忽视盗版插件生态的法律风险（Kodi 的教训）**
   - 官方需要明确声明：不支持盗版 addon，用户自行承担法律责任
   - 考虑官方市场的上线标准中明确禁止盗版源

### 推荐架构方向

**两层模型：**

```
┌─────────────────────────────────────────┐
│   LinPlayer (Go 核心 + 原生 UI)          │
│   ┌──────────────────────────────────┐  │
│   │ 插件管理器 (Go)                    │  │
│   │ - 权限检查 (签名 + 声明)          │  │
│   │ - 版本控制 (兼容性检查)           │  │
│   └──────────────────────────────────┘  │
│                  ↓                       │
│   ┌──────────────────────────────────┐  │
│   │ 插件通信层 (Go RPC / HTTP)       │  │
│   │ - 进程隔离 / 网络隔离           │  │
│   │ - 许可门控 (权限检查点)         │  │
│   └──────────────────────────────────┘  │
│                  ↓                       │
└─────────────────────────────────────────┘
           ↓              ↓
    ┌────────────┐  ┌──────────────┐
    │ 轻量级插件  │  │ 远程服务插件 │
    │ (Lua/JS)   │  │ (HTTP API)   │
    │ 进程隔离   │  │ 网络隔离     │
    └────────────┘  └──────────────┘
```

**三个插件类型：**

- **Level 1: 轻量脚本** (Lua/JS，进程隔离或沙箱)
  - 用途：自定义快捷键、UI 皮肤、本地 OSD
  - 权限：GUI 绘制、快捷键注册，无文件系统访问
  
- **Level 2: 源和元数据** (Go 或 HTTP)
  - 用途：视频源、弹幕、推荐算法
  - 权限：网络请求、缓存读写、媒体库只读
  
- **Level 3: 扩展功能** (Go + 签名)
  - 用途：媒体库集成、高级搜索、同步功能
  - 权限：需明确审核，授权用户数据库访问

---


## 参考文献

### Kodi
- https://kodi.wiki/view/Add-on_development
- https://forum.kodi.tv/showthread.php?tid=119117 (官方盗版政策)
- https://kodi.wiki/view/Official:Forum_rules/Banned_add-ons

### Jellyfin
- https://github.com/jellyfin/jellyfin-plugin-template
- https://jellyfin.org/docs/general/server/plugins/
- https://github.com/jellyfin/jellyfin/discussions

### Stremio
- https://github.com/Stremio/stremio-addon-sdk
- https://stremio.github.io/stremio-addon-sdk/protocol.html
- https://dev.to/githubopensource/ditch-the-juggling-act-why-stremios-open-source-addon-ecosystem-is-the-future-of-streaming-831

### mpv
- https://github.com/mpv-player/mpv/blob/master/DOCS/man/lua.rst
- https://github.com/mpv-player/mpv/wiki/User-Scripts
- https://github.com/jonniek/mpv-scripts

### IINA
- https://iina.io/plugins/
- https://docs.iina.io/pages/getting-started
- https://iina.io/release-note/1.4.0.html

### VLC
- https://github.com/nima64/VLC-3-lua-extension-guide
- https://github.com/videolan/vlc/blob/master/share/lua/modules/sandbox.lua
- https://github.com/verghost/vlc-lua-docs

### Plex
- https://www.howtogeek.com/plex-is-overhauling-custom-metadata-providers/
- https://forums.plex.tv/t/3rd-party-metadata-agents/931920
- https://support.plex.tv/articles/200241558-agents/

