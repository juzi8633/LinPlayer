# 插件 UI 扩展模型横评

> 研究用同一份描述在 Windows/Linux(Avalonia) 和 Android(Compose) 原生 UI 上都长出界面的可行方案。

## Adaptive Cards（Microsoft）

**来源**: https://learn.microsoft.com/en-us/adaptive-cards/ | https://adaptivecards.io/

### 描述格式

JSON 格式，根对象为 `AdaptiveCard`：

```json
{
  "type": "AdaptiveCard",
  "version": "1.4",
  "body": [
    {
      "type": "TextBlock",
      "text": "Hello World",
      "weight": "bolder",
      "size": "large"
    },
    {
      "type": "Image",
      "url": "https://example.com/image.png",
      "size": "stretch"
    },
    {
      "type": "Input.Text",
      "id": "email",
      "placeholder": "Enter email"
    }
  ],
  "actions": [
    {
      "type": "Action.Submit",
      "title": "Send",
      "data": { "action": "send" }
    }
  ]
}
```

支持元素类型：TextBlock、Image、Container、ColumnSet、FactSet、ImageSet、Input.Text、Input.Date、Input.Number、Input.ChoiceSet、Input.Toggle。

### 表达力上限

**能做的**：列表（输入集合）、网格（ColumnSet 模拟）、图片、文本、各类输入框、按钮。
**不能做的**：自定义绘制、视频播放、复杂动画。
**响应事件**：提交时收集 Input 数据，无局部刷新机制（整张卡片重新发送）。

### 在 Avalonia 和 Compose 的实现工作量

**现状**：
- Avalonia: 无官方渲染器，需自建。参考 TeamDev 的 DotNetBrowser 示例，约 2000~3000 LOC。
- Android: 官方提供 `microsoft/adaptivecards-android`（Java），移到 Compose 需包装，约 1500~2000 LOC。

**现成库**：无。两端都要自己实现解析和渲染逻辑。

### TV 遥控器焦点友好度

**问题**：
- Adaptive Cards 本身无焦点管理规范，由渲染器实现。
- 在 TV 上需自建焦点链和遍历逻辑，标准化程度低。

**结论**：不友好。需要额外实现焦点导航。

### 性能与观感

**原生渲染**：如果实现到位，可达原生体验。
**性能**：解析 JSON + 递归渲染，小卡片（<100 KB）无压力；大卡片（动态加载列表）需注意虚拟滚动。
**观感**：依赖渲染器实现质量，容易出现「卡片风格」感（统一字体、颜色、间距）。

### 安全面

**风险**：
- Action.OpenUrl 可导致钓鱼（插件伪造 URL 按钮跳向恶意站点）。
- 无 sandbox 隔离。
- 可在 Input 中混入大量隐藏字段，提交时偷数据。

**防护**：需白名单 URL、限制 Input 字段数量、对用户行为提示（如"插件将向服务器发送：xxx"）。

### 对 LinPlayer 的建议

**适合位置**：
- ✓ 详情页插件区块（电影/剧集详情下方）
- ✓ 首页可选栏目（需要表单配置的源）
- ✓ 播放页右侧栏（设置弹幕、字幕等）

**不适合位置**：
- ✗ 播放页覆盖层（性能和焦点导航问题）
- ✗ 全屏模式

**成本估算**：搭建 Avalonia 和 Compose 渲染器各约 2 周，之后维护成本中等。


## Slack Block Kit

**来源**: https://docs.slack.dev/block-kit/ | https://api.slack.com/block-kit/building

### 描述格式

JSON 格式，包含 blocks 数组和可选的 view metadata：

```json
{
  "blocks": [
    {
      "type": "section",
      "text": {
        "type": "mrkdwn",
        "text": "*Bold* and _italic_ text"
      }
    },
    {
      "type": "section",
      "text": { "type": "plain_text", "text": "Pick an option" },
      "accessory": {
        "type": "button",
        "text": { "type": "plain_text", "text": "Click me" },
        "action_id": "button_click"
      }
    },
    {
      "type": "input",
      "element": {
        "type": "plain_text_input",
        "action_id": "plain_text_input-action"
      },
      "label": { "type": "plain_text", "text": "Label" }
    }
  ]
}
```

支持的 Block 类型：section、divider、image、actions、context、input、header。
支持的 Element 类型：button、select menus、datepicker、timepicker、plain-text input、checkbox groups、radio button groups。

### 表达力上限

**能做的**：文本（带 Markdown）、图片、按钮、菜单、输入框、日期选择、多选。
**不能做的**：网格布局（无 ColumnSet 等）、自定义绘制、嵌套 block。
**响应事件**：用户交互触发 interaction payload，其中包含 action_id 和输入值；支持即时响应（modal 打开）或异步回调。

### 在 Avalonia 和 Compose 的实现工作量

**现状**：
- Avalonia: 无现成渲染器。参考 Slack Web 客户端逻辑，约 2500~3500 LOC。
- Android: 官方库 `slack-android-layout-kit`（如果公开）或从 Slack 安卓应用逆向参考，约 2000~2500 LOC。

**现成库**：无官方跨平台库。

### TV 遥控器焦点友好度

**问题**：
- 与 Adaptive Cards 类似，无焦点规范。
- actions block 本身是水平布局（多个按钮并排），TV 上遍历时需自定义焦点循环。

**结论**：中等。actions block 的水平布局在 TV 上稍有优势（遥控左右键自然对应）。

### 性能与观感

**观感**：Slack 风格较强（圆角、统一字体、深灰背景）；在播放器 UI 中可能显得突兀。
**性能**：Block 数量与性能线性关系，100+ blocks 时需虚拟滚动。

### 安全面

**风险**：
- 无直接的 URL 伪造风险（Block Kit 聚焦于数据收集，不直接支持导航）。
- 可能通过 button action 触发宿主命令，需严格白名单。

**防护**：限制可触发的 action_id、对用户操作日志记录。

### 对 LinPlayer 的建议

**适合位置**：
- ✓ 首页栏目配置（输入框 + 选择菜单）
- ✓ 设置页（较为通用）

**不适合位置**：
- ✗ 详情页（风格不搭）
- ✗ 播放页任何位置

**成本估算**：1.5~2 周per 端。


## VS Code 贡献点模型（Contribution Points）

**来源**: https://code.visualstudio.com/api/references/contribution-points | https://code.visualstudio.com/api/references/extension-manifest

### 描述格式

在 `package.json` 的 `contributes` 字段中声明：

```json
{
  "contributes": {
    "commands": [
      {
        "command": "myext.hello",
        "title": "Say Hello"
      }
    ],
    "menus": {
      "editor/context": [
        {
          "command": "myext.hello",
          "when": "editorTextFocus"
        }
      ]
    },
    "views": {
      "custom-sidebar": [
        {
          "id": "myview",
          "name": "My View"
        }
      ]
    },
    "configuration": {
      "title": "My Extension",
      "properties": {
        "myext.enabled": {
          "type": "boolean",
          "default": true,
          "description": "Enable my extension"
        }
      }
    }
  }
}
```

贡献点类型：commands、keybindings、menus、views、viewsContainers、configuration、languages、grammars、themes、colors 等。

### 表达力上限

**能做的**：声明菜单位置、命令、侧栏视图、配置项。
**不能做的**：直接描述 UI 布局（需要实现 webview 或原生面板）。
**响应事件**：命令触发、设置变更回调、视图焦点事件。

### 在 Avalonia 和 Compose 的实现工作量

**不适用**。Contribution Points 是声明性的 metadata，本身不涉及渲染。
需要配套实现 webview 或原生 panel 来承载实际 UI。

### TV 遥控器焦点友好度

**未确认**。VS Code 的 contribution model 主要面向桌面，未验证在 TV 上表现。

### 性能与观感

**观感**：高度一致（所有命令、菜单、配置都遵循宿主 UI 规范）。
**性能**：注册 contribution 本身无性能开销，但 webview 面板加载会有启动延迟。

### 安全面

**优势**：
- 命令严格白名单化。
- 菜单位置、配置字段都由宿主控制，插件无法伪造。

**风险**：
- Webview 内的内容仍需沙箱隔离。

### 对 LinPlayer 的建议

**适合位置**：
- ✓ 命令注册（快捷键操作）
- ✓ 设置页字段（在既有设置面板中声明新的配置项）
- ✓ 菜单项（右键菜单、工具栏）

**不适合位置**：
- ✗ 动态 UI 内容（卡片、详情页块）

**启示**：LinPlayer 可借鉴 VS Code 的"声明先行"模式：
- 插件先声明支持的 hook 点（详情页、播放页、首页、设置页）
- 宿主决定每类 hook 的 UI 位置和样式
- 插件通过 API 调用返回内容，而不是定义样式

**成本估算**：架构成本 1 周，后续扩展点维护 0 成本（就是改 JSON schema）。


## WebView 嵌入（Avalonia + Android）

**来源**: https://docs.avaloniaui.net/docs/app-development/embedding-web-content | https://medium.com/@thanhnh98/remote-compose-on-android-what-it-is-and-when-to-use-it-for-remote-surfaces-cd4e4881d99c

### 描述格式

插件返回 HTML + JavaScript，由宿主在 WebView 容器中加载：

```json
{
  "type": "webview",
  "html": "<div id='root'></div>",
  "script": "document.getElementById('root').textContent = 'Hello';",
  "style": "body { font-family: system-ui; }"
}
```

或者直接返回 URL 让 WebView 加载远程页面。

### 表达力上限

**能做的**：任何 Web 技术可做的（React、Vue 组件、Canvas 绘制、实时更新）。
**不能做的**：无。完全自由度。
**响应事件**：JavaScript 与宿主通过 postMessage 双向通信。

### 在 Avalonia 和 Compose 的实现工作量

**Avalonia**:
- 官方 NativeWebView API（基于 WebView2 on Windows、WebKit on macOS、WebKitGTK on Linux）。
- 集成成本：低。已有官方 API，只需封装成插件容器。
- 跨平台：WebView2 需要在 Windows 10+ 系统已安装；Linux/macOS 依赖系统库。

**Android/Compose**:
- AndroidView Composable 包装原生 WebView。
- 集成成本：低。官方支持，约 200 LOC。
- 问题：WebView 在 Compose 内焦点管理复杂。

**总工作量**：1 周 per 端。

### TV 遥控器焦点友好度

**严重问题**：
- WebView 内的焦点（链接、表单）与宿主焦点体系脱离。
- 遥控器 D-Pad 在 WebView 内表现为滚动，无法聚焦 Web 元素。
- 需在 WebView 与宿主间建立焦点桥接，工作量 1.5~2 周。

**结论**：不友好。TV 场景不可用（除非插件是全屏 WebView）。

### 性能与观感

**性能**：
- WebView 是重量级容器，启动时间 200~500ms（第一次加载）。
- 内存占用 30~80 MB per WebView 实例。
- 如果宿主侧有多个 WebView（多个插件同时显示），内存压力大。

**观感**：
- 与宿主原生 UI 风格脱离，容易显得突兀。
- 在移动 / TV 屏幕上，Web UI 响应延迟感明显。

### 安全面

**重大风险**：
- 插件可任意执行 JavaScript，突破宿主 sandbox（读取配置、修改历史、篡改账号）。
- 无法有效隔离。

**防护**：
- 必须严格限制 JavaScript API（只暴露必要的 postMessage 接口）。
- 内容安全策略（CSP）限制外部资源加载。
- 不允许插件访问宿主内部状态。

### 对 LinPlayer 的建议

**适合位置**：
- ✓ 全屏播放页替代（如果插件需要完全自定义播放 UI）
- ✓ 首页整个栏目（如果插件是富媒体展示）

**不适合位置**：
- ✗ 设置页（单个字段）
- ✗ Android TV（焦点问题）
- ✗ 詃页区块（风格不搭、性能问题）

**成本估算**：集成成本 1 周，安全加固 1~2 周。

**警告**：WebView 方案适合插件完全掌控界面的场景（如个人播放器皮肤）；不适合与宿主深度交互的场景。


## mpv OSD / Overlay（视频覆盖层）

**来源**: https://mpv.io/manual/master/ | https://github.com/mpv-player/mpv/discussions/17432

### 描述格式

mpv 的 osd-overlay 命令接受 ASS（Advanced SubStation Alpha）格式的绘制指令：

```lua
-- 在视频上画文字
mp.osd_overlay(mp.get_osd_size(), 'default', [[
{\pos(50,50)}Hello World
{\c&HFF0000&}Red text
{\fscx150}Scaled text
]])

-- 或通过 overlay-add 命令叠加位图
mp.commandv('overlay-add', 'id', 'x', 'y', 'file.png', '0')
```

支持 ASS 绘图命令：位置、颜色、字体、动画（fade）。

### 表达力上限

**能做的**：文字、简单几何（通过 ASS 矢量指令）、位图图片、动画。
**不能做的**：复杂交互（按钮、输入框）。
**响应事件**：通过 mouse 和 key 事件，插件可响应点击、按键。

### 在 Avalonia 和 Compose 的实现工作量

**Avalonia**：
- 无直接对应物。需实现 overlay 层的自定义绘制（using SharpDX / SkiaSharp）。
- 工作量：1.5 周。

**Compose**：
- 使用 Canvas Composable 自定义绘制。
- 工作量：1 周。

### TV 遥控器焦点友好度

**问题**：
- 覆盖层本身无焦点概念。
- 如果插件需要响应遥控，需自建焦点指示（如高亮某个元素）并响应 D-Pad 事件。

**结论**：可接受。焦点自建成本不高（相比 WebView）。

### 性能与观感

**性能**：
- 原生绘制，性能优异。
- ASS 渲染（mpv 内实现）与宿主 UI 渲染无竞争。

**观感**：
- 完全掌控视觉表现，可与宿主风格高度一致。
- 典型用例：字幕、进度条、按钮覆盖。

### 安全面

**优势**：
- 覆盖层的绘制内容与宿主渲染分离，无法直接修改宿主 UI。
- 插件仅能在覆盖层上绘制，不能突破边界。

**风险**：
- 插件可伪造"暂停"、"播放"、"设置"等按钮，误导用户。
- 但用户点击时，事件路由由宿主控制，伪造的按钮无实际功能。

**防护**：
- 在覆盖层显示插件来源（如"弹幕来自 DanDanPlay"）。
- 限制覆盖层透明度和面积（防止覆盖整个屏幕）。

### 对 LinPlayer 的建议

**完美适合位置**：
- ✓ 播放页覆盖层（弹幕、字幕、进度提示、快捷菜单）
- ✓ 字幕、音轨切换面板
- ✓ 快捷设置浮窗

**不适合位置**：
- ✗ 首页、详情页（不在播放窗口上下文）

**成本估算**：2 周（实现基础覆盖层 API）。

**强烈建议**：覆盖层是 LinPlayer 插件系统的杀手级特性，投资重点应在这里。


## JSON Schema 表单生成（react-jsonschema-form 思路）

**来源**: https://react-jsonschema-form.readthedocs.io/ | https://jsonforms.io/

### 描述格式

插件返回 JSON Schema + UI Schema：

```json
{
  "schema": {
    "type": "object",
    "properties": {
      "name": { "type": "string", "title": "Name" },
      "email": { "type": "string", "format": "email", "title": "Email" },
      "age": { "type": "integer", "minimum": 0, "maximum": 120 },
      "subscribed": { "type": "boolean", "title": "Subscribe" }
    },
    "required": ["name", "email"]
  },
  "uiSchema": {
    "age": { "ui:help": "Must be between 0 and 120" },
    "subscribed": { "ui:widget": "checkbox" }
  }
}
```

宿主根据 schema 生成原生表单。

### 表达力上限

**能做的**：文本输入、数字、日期、选择、复选框、单选框、嵌套对象（带展开/折叠）、数组（动态添加/删除）。
**不能做的**：自定义绘制、复杂校验（logic 必须在 schema 中表达）。
**响应事件**：字段值变化、表单提交。

### 在 Avalonia 和 Compose 的实现工作量

**Avalonia**：
- 需实现 JSON Schema 解析器 + 表单生成器。
- 参考 react-jsonschema-form 思路，约 1.5~2 周。

**Compose**：
- 类似，约 1.5~2 周。

**现成库**：
- Compose：无完全实现的库；可参考 Jetpack Material 3 的现成输入组件二次包装。
- Avalonia：无。

### TV 遥控器焦点友好度

**优势**：
- 原生表单，焦点导航开箱即用。
- 选择菜单等可自动适配 TV 焦点行为。

**结论**：友好。最适合 TV 场景的方案。

### 性能与观感

**性能**：快速。表单生成即时，无异步加载。
**观感**：统一的宿主风格，与既有设置面板一致。

### 安全面

**风险**：
- 插件通过复杂 schema 强制大量输入，延长用户操作时间（DoS 向）。
- Schema 中的 `pattern` 正则可能造成 ReDoS（正则表达式拒绝服务）。

**防护**：
- 限制 schema 大小（如 $ref 深度 ≤ 10）。
- 正则表达式白名单校验。

### 对 LinPlayer 的建议

**完美适合位置**：
- ✓ 设置页（新增插件配置选项）
- ✓ 首页栏目配置（数据源地址、API key 等）
- ✓ 详情页输入型插件

**不适合位置**：
- ✗ 播放页
- ✗ 需要富媒体展示的场景

**成本估算**：
- 架构：1 周（JSON Schema 解析 + 渲染规则定义）
- Avalonia 实现：1.5 周
- Compose 实现：1.5 周

**强烈推荐**：这是设置页 / 配置表单的最优方案。


## Home Assistant 配置流（Config Flow）

**来源**: https://developers.home-assistant.io/docs/core/integration/yaml_configuration/ | https://aarongodfrey.dev/home%20automation/building_a_home_assistant_custom_component_part_3/

### 描述格式

插件定义配置步骤的 Python 类，返回的数据结构定义了表单结构：

```python
class ConfigFlow(config_entries.ConfigFlow):
    async def async_step_user(self, user_input=None):
        if user_input is not None:
            return self.async_create_entry(title=user_input["name"], data=user_input)
        
        schema = vol.Schema({
            vol.Required("name"): str,
            vol.Required("api_key"): str,
            vol.Optional("timeout", default=30): int,
        })
        
        return self.async_show_form(
            step_id="user",
            data_schema=schema,
            errors={}
        )
```

宿主框架负责将 schema 转换为 UI 表单。

### 表达力上限

**能做的**：文本、数字、选择、多选、嵌套步骤（多步向导）。
**不能做的**：自定义绘制、实时验证反馈（需要整步骤重新填）。
**响应事件**：用户提交每一步，插件返回下一步 schema 或完成。

### 在 Avalonia 和 Compose 的实现工作量

**架构层面**：
- 需定义 schema 格式（可复用 JSON Schema）。
- 需实现配置流管理（状态机：当前步骤、已填字段、验证错误）。

**Avalonia**：
- 配置流框架：1 周
- 表单渲染器：参考 JSON Schema 方案，1 周

**Compose**：
- 配置流框架：1 周
- 表单渲染器：1 周

### TV 遥控器焦点友好度

**优势**：
- 与 JSON Schema 方案相同，原生表单焦点管理开箱即用。

**结论**：友好。

### 性能与观感

**性能**：快速。无异步加载。
**观感**：与设置页一致。多步流程可引导用户逐步配置（比一张大表单友好）。

### 安全面

**风险**：
- 同 JSON Schema 方案。插件可强制大量输入。

**防护**：
- 限制步骤数（如 ≤ 10 步）。
- 限制每步字段数（如 ≤ 20 个）。

### 对 LinPlayer 的建议

**适合位置**：
- ✓ 首次添加数据源的向导（服务器地址 → 账号 → 高级选项）
- ✓ 插件配置初始化

**与 JSON Schema 的区别**：
- JSON Schema：单个表单，一次提交。
- Config Flow：多步向导，灵活但复杂度高。

**成本估算**：2~3 周（包括流程管理和验证）。


## DivKit（Yandex SDUI 框架）

**来源**: https://divkit.tech/en/ | https://github.com/divkit/divkit

### 描述格式

JSON 格式，定义完整的 UI 树（容器、文本、按钮、输入等）：

```json
{
  "type": "container",
  "orientation": "vertical",
  "items": [
    {
      "type": "text",
      "text": "Welcome"
    },
    {
      "type": "button",
      "title": "Click me",
      "actions": [
        {
          "type": "send_log",
          "label": "click"
        }
      ]
    }
  ]
}
```

官方提供 Android、iOS、Web、Flutter 的渲染器。

### 表达力上限

**能做的**：容器、文本、按钮、图片、输入框、列表、网格、动画、条件渲染（基于变量）。
**不能做的**：自定义绘制、复杂交互逻辑（仅支持简单事件）。
**响应事件**：按钮点击、输入变化，触发 action（发日志、打开 URL、调用 API）。

### 在 Avalonia 和 Compose 的实现工作量

**现状**：
- Avalonia：无现成渲染器。参考 DivKit 官方实现，约 3~4 周。
- Android：DivKit 官方库存在，可直接集成，但与 Compose 兼容性需验证。约 1~2 周。

**总工作量**：4~6 周。

### TV 遥控器焦点友好度

**问题**：
- DivKit 的官方库主要面向移动端，TV 焦点支持未确认。
- 需要额外开发焦点导航层。

**结论**：不确定。建议先原型验证。

### 性能与观感

**性能**：
- JSON 解析 + 递归渲染，与 Adaptive Cards 类似。
- DivKit 官方强调性能优化（虚拟滚动、增量更新）。

**观感**：
- 设计系统一致性强（DivKit 强制统一的样式语言）。
- 但与 LinPlayer 既有 UI 风格可能不搭。

### 安全面

**风险**：
- 与 Adaptive Cards 类似。action 可能导致恶意 URL 跳转或命令注入。

**防护**：
- action 白名单。
- URL 和命令参数严格校验。

### 对 LinPlayer 的建议

**评价**：
- 功能齐全，但工作量大（Avalonia 端）。
- 通用 SDUI 框架，不如针对性方案（JSON Schema、WebView）高效。

**建议**：
- 仅在多端统一度要求极高、投入充足的情况下考虑。
- 如选择，应先验证 Compose 端的集成成本。

**成本估算**：4~6 周总投入，维护成本中等。


## Remote Compose（Google Jetpack 远程组合）

**来源**: https://developer.android.com/jetpack/androidx/releases/compose-remote | https://medium.com/@thanhnh98/remote-compose-on-android-what-it-is-and-when-to-use-it-for-remote-surfaces-cd4e4881d99c

### 描述格式

一个二进制协议（非 JSON），宿主通过 API 创建 Remote Compose 文档，序列化后发送给客户端：

```kotlin
// 创建端（通常是服务器或插件 Go 核心层）
val root = column {
    text("Hello Remote Compose")
    button(
        onClick = { sendAction("button_clicked") },
        modifier = Modifier.padding(16.dp)
    ) {
        text("Click me")
    }
}

// 序列化成二进制文档，发送给客户端

// 客户端（Jetpack Compose）
RemoteCompositionContainer(
    document = remoteDocument,
    onAction = { action -> /* handle action */ }
)
```

**备注**：目前仅适用于 Jetpack Compose（Android），无跨平台方案。

### 表达力上限

**能做的**：Jetpack Compose 能做的任何事（文本、按钮、列表、动画、自定义 Composable）。
**不能做的**：无限制（完全的 Compose 能力，但需要宿主预先注册可用的 Composable）。
**响应事件**：点击、输入变化，通过 callback 传回宿主。

### 在 Avalonia 和 Compose 的实现工作量

**Avalonia**：
- 无对应物。Remote Compose 是 Jetpack 专有。

**Compose**：
- Google 官方库（alpha），开箱支持。无额外工作量（仅需集成依赖）。

**跨平台问题**：
- Remote Compose 仅限 Android。Avalonia 和其它平台无对应方案。
- **这是致命局限**。

### TV 遥控器焦点友好度

**优势**：
- Jetpack Compose TV 库对焦点有原生支持。
- Remote Compose 继承这一优势。

**结论**：友好。但仅限 Android TV。

### 性能与观感

**性能**：
- 二进制序列化，效率高。
- 运行时直接执行 Compose，性能优异。

**观感**：
- 原生 Compose UI，与 Android 应用风格一致。

### 安全面

**风险**：
- Composable 有能力修改宿主 UI（如果暴露了宿主组件）。

**防护**：
- 仅注册安全的 Composable（如 Text、Button、Image）。
- 不暴露 LaunchedEffect、State 等高危 API。

### 对 LinPlayer 的建议

**评价**：
- **仅限 Android**，无法跨平台。
- 在 Android 侧表达力最高、性能最好。

**建议**：
- 不考虑。理由：Avalonia（Windows/Linux/macOS）无对应方案，无法统一设计。
- 如果 Android 性能成为瓶颈，可考虑在 Android 侧单独采用 Remote Compose，与 Avalonia 侧用其它方案搭配。

**成本估算**：Android 侧 2~3 周集成，但无法跨平台。


## 横向对比表

| 方案 | 描述格式 | 表达力 | 实现工作量 | TV焦点 | 性能 | 安全 | 风格一致性 | 跨平台 |
|------|--------|--------|-----------|--------|------|------|----------|--------|
| **Adaptive Cards** | JSON | 中等（卡片式） | Avalonia: 2周 / Compose: 1.5周 | 差 | 中等 | 需防护 | 差（卡片感） | 有渲染器的平台 |
| **Slack Block Kit** | JSON | 中等（数据收集） | Avalonia: 2.5周 / Compose: 2周 | 中等 | 中等 | 需防护 | 差（Slack风格） | 无跨平台库 |
| **VS Code Contrib Points** | JSON | 低（声明式） | 不适用（仅元数据） | 未验证 | 优秀 | 优秀 | 优秀 | 概念通用 |
| **WebView 嵌入** | HTML+JS | 极高（完全自由） | Avalonia: 1周 / Compose: 1周 | 差（需特殊处理） | 差（重量级） | 差（沙箱难） | 差（Web风格） | 有 |
| **mpv OSD/Overlay** | ASS+Lua | 中等（覆盖层） | Avalonia: 1.5周 / Compose: 1周 | 可接受 | 优秀 | 优秀（隔离） | 优秀（宿主风格） | 适用于播放场景 |
| **JSON Schema Form** | JSON Schema | 高（通用表单） | Avalonia: 1.5周 / Compose: 1.5周 | 优秀 | 优秀 | 中等 | 优秀 | 有（独立实现） |
| **Home Assistant Config Flow** | Python (schema) | 高（多步向导） | Avalonia: 2周 / Compose: 2周 | 优秀 | 优秀 | 中等 | 优秀 | 需各端实现 |
| **DivKit** | JSON | 高（通用UI） | Avalonia: 3.5周 / Compose: 1.5周 | 未确认 | 中等 | 需防护 | 中等 | 有官方支持 |
| **Remote Compose** | 二进制 | 极高（Compose完全能力） | Compose: 0周 / Avalonia: 无 | 优秀（仅Android） | 优秀 | 中等 | 优秀（仅Android） | **仅Android** |

## 建议：分层开放方案

### 策略概述

LinPlayer 的插件 UI 应采取**分层 hook 点**模式，根据场景选用不同方案：

```
┌─────────────────────────────────────────────────────┐
│           LinPlayer 插件系统架构                      │
├─────────────────────────────────────────────────────┤
│  [首页]  [详情页]  [播放页]  [设置页]  [弹幕/字幕]  │
├─────────────────────────────────────────────────────┤
│ JSON Schema  Adaptive  WebView  JSON Schema  OSD    │
│              Cards             Form      Overlay    │
└─────────────────────────────────────────────────────┘
```

### 第一优先级：立即投入（推荐）

#### 1. **JSON Schema 表单生成**（设置页 + 首页栏目配置）

**场景**：
- 插件初始配置（API key、服务器地址、用户偏好）
- 首页数据源栏目的参数输入
- 任何需要收集用户数据的地方

**方案**：
- 定义统一的 JSON Schema（参考 react-jsonschema-form）
- Avalonia 和 Compose 各实现一套表单渲染器
- 复用现有 UI 组件库（按钮、输入框、选择等）

**工作量**：
- 架构设计：3~5 天
- Avalonia 实现：1 周
- Compose 实现：1 周
- **总计：2.5 周**

**收益**：
- 最常用的交互场景覆盖（配置）
- 原生体验，TV 友好
- 代码重用率高（两端都是表单）

#### 2. **mpv OSD/Overlay 覆盖层**（播放页快捷菜单、弹幕、字幕）

**场景**：
- 弹幕、字幕等播放时需要的信息和交互
- 快捷键菜单（调速、音量、字号）
- 统计数据（进度、缓冲）
- 自定义 OSD（节目表、记录）

**方案**：
- 抽象 Overlay API（绘制文字、矩形、图片）
- 支持基础 ASS 指令（位置、颜色、字体）
- 响应点击和按键事件

**工作量**：
- Avalonia：1.5 周
- Compose（基础 Canvas 绘制）：1 周
- **总计：2.5 周**

**收益**：
- 播放页的"杀手级"扩展点
- 性能优秀、风格统一
- TV 上有焦点管理
- 弹幕/字幕插件的黄金场景

---

### 第二优先级：有余力可投入

#### 3. **Adaptive Cards**（详情页区块）

**场景**：
- 电影/剧集详情下方的插件信息展示
- 剧情介绍、评分、推荐等

**方案**：
- 依赖 Adaptive Cards JSON schema
- 两端各搭一套渲染器

**工作量**：
- Avalonia：2 周
- Compose：1.5 周
- **总计：3.5 周**

**收益**：
- 详情页的内容扩展
- 表达力比 JSON Schema 强（支持网格、复杂布局）
- 但风格一致性需要调优

**风险**：
- 两套渲染器维护成本较高
- TV 焦点需要额外实现

#### 4. **Home Assistant Config Flow 概念（多步配置向导）**

**场景**：
- 第一次添加数据源，引导用户完成配置
- 例：选择服务器 → 登录 → 选择媒体库 → 高级选项

**方案**：
- 参考 Home Assistant 的多步流程
- 插件返回 schema list，宿主按步渲染

**工作量**：
- Avalonia + Compose：2.5~3 周

**收益**：
- 提升新用户体验
- 可以在步骤间做条件判断（例：登录失败重试）

---

### 第三优先级：不推荐

#### ❌ **WebView 嵌入**

**理由**：
- TV 上焦点管理几乎不可能。
- 内存开销大（多个 WebView 并存）。
- 安全隔离难以完全实现。
- 与原生 UI 风格脱离。

**仅在以下情况考虑**：
- 插件需要完全自定义播放 UI（替代 mpv 播放器）。
- 确实无法用其它方案表达。

#### ❌ **DivKit**

**理由**：
- Avalonia 端工作量大（3.5 周）。
- 通用 SDUI 框架，不如专门方案高效。
- TV 焦点支持未验证。
- 与既有 LinPlayer UI 风格冲突。

**仅在以下情况考虑**：
- Yandex 或 VK 等公司贡献的插件。
- 已经在用 DivKit 的外部生态。

#### ❌ **Remote Compose**

**理由**：
- 仅限 Android，无跨平台能力。
- Avalonia 无对应方案。
- 与统一设计相悖。

---

### 实施路线图（建议时间线）

#### **Phase 1：基础框架（4 周）**

1. 定义 hook 点 schema（JSON）
   - `hook.settings_form`: JSON Schema for settings
   - `hook.homepage_section`: Adaptive Cards for display
   - `hook.playback_overlay`: OSD overlay definition
   - `hook.details_block`: Adaptive Cards for content

2. 设计插件→宿主的通信协议
   - 插件返回：`{ "type": "hook_point_id", "payload": {...} }`
   - 宿主反馈：`{ "action": "...", "data": {...} }`

3. 搭建两端的 hook 管理器
   - 注册、加载、销毁 hook
   - 版本兼容性处理

#### **Phase 2：JSON Schema 表单渲染（2.5 周）**

1. Avalonia 端（1 周）
   - 实现 JSON Schema parser
   - 为每个字段类型映射 Avalonia 控件
   - 表单验证、错误显示

2. Compose 端（1.5 周）
   - 同上，用 Jetpack Compose 控件

3. 集成（0.5 周）
   - 设置页改造，使用新的表单生成器
   - 首页栏目配置改造

#### **Phase 3：mpv OSD Overlay（2.5 周）**

1. Avalonia 端（1.5 周）
   - Overlay layer 的绘制引擎（基础形状、文字）
   - 事件路由（点击、按键）

2. Compose 端（1 周）
   - Canvas Composable 实现

3. 集成（0.5 周）
   - 播放页集成 overlay API
   - 弹幕插件示例

---

### 开发优先顺序（按价值排序）

1. **JSON Schema** - 最基础、用途广、TV 友好
2. **mpv OSD** - 最高价值、性能最好、玩法最多
3. **Adaptive Cards**（如有时间）- 详情页扩展
4. **Config Flow**（如有时间）- 提升新用户体验

---

### 安全考虑（贯穿所有方案）

对所有方案，实现以下守卫：

1. **资源限制**
   - JSON payload 大小 ≤ 5 MB
   - 数组元素 ≤ 1000
   - 深度 ≤ 20

2. **命令白名单**
   - 插件仅能触发已明确声明的命令
   - 命令参数类型和值范围严格校验

3. **隐私隔离**
   - 不暴露宿主内部配置（如已添加的所有源）
   - 不暴露历史记录、观看进度等用户数据
   - 插件间的数据隔离（沙箱）

4. **UI 欺骗防护**
   - Overlay 禁止盖住整个屏幕 >70%
   - 标记"插件内容"的视觉指示
   - 弹幕等覆盖层限制透明度

---

### 技术债清单

1. 两端 UI 框架（Avalonia / Compose）的统一化
   - 建立公共组件库（按钮样式、间距、颜色 token）
   - 确保插件 UI 在两端表现一致

2. 焦点管理框架（特别是 TV）
   - Overlay 焦点导航
   - WebView（如果支持）的焦点桥接

3. 渲染器的性能优化
   - 大列表虚拟滚动
   - 动画帧率管理（不干扰播放视频）

4. 测试基础设施
   - 插件 UI 单元测试框架
   - 跨端兼容性测试（自动化截图对比）

---

## 最后的话

**核心结论**：

- **JSON Schema 表单** + **mpv OSD Overlay** 组合覆盖了 80% 的常见插件需求。
- 投入 5~6 周，可构建一个稳定、高效、跨平台的插件 UI 系统。
- 避免一开始就选 WebView 或 DivKit 等重方案，而应采用**轻量级、分层式**的设计。

**最高优先级的投资**是 **mpv OSD Overlay**，因为：
- 性能无敌
- 集成成本最低
- 开放弹幕、字幕、快捷菜单等高价值玩法
- 原生体验，所有终端都适用

其次是 **JSON Schema 表单**，因为：
- 配置是最常见的交互
- TV 上焦点管理完美
- 代码简洁、易维护

