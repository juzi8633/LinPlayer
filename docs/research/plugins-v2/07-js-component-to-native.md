# JS 组件 → 原生控件渲染:成熟做法调研

> 插件用 JS/TS 写,运行在 Go 核心层嵌入的 JS 引擎里;UI 原生化:Avalonia(Windows/Linux)+ Compose(Android)。
> 目标:插件能自己写组件、改整页 UI、改全局主题;官方提供基础组件方便扩展。

---

## 1. React Native 新架构(Fabric + JSI)

### 架构概述

React Native 用 JavaScript Interface(JSI)替代 JSON Bridge,直接同步/异步调用原生代码,零序列化开销。

- **Fabric**:新渲染引擎,直接访问原生组件(View、Text、Image),支持并发渲染
- **JSI**:C++ 胶水层,JS 侧能直接调原生,原生侧能直接访问 JS 对象,无中间 Bridge

来源:[React Native New Architecture 2025: Fabric & TurboModules Guide](https://isitdev.com/react-native-new-architecture-fabric-turbomodules-2025-2/)

### 自定义原生组件 vs 纯 JS 组件

| 项 | 原生组件 | 纯 JS 组件 |
|---|---|---|
| 编写语言 | Objective-C++/Java/Kotlin/C++ | JavaScript/TypeScript |
| 编译方式 | 代码生成(Codegen) + 平台编译 | 零编译 |
| 平台能力 | 直接访问低层 API | 受限于内置 View |
| 部署难度 | 高(需各平台构建) | 低(跨平台一致) |

来源:[Fabric Native Components · GitHub](https://github.com/reactwg/react-native-new-architecture/blob/main/docs/fabric-native-components.md)

### 消息格式与开销

- Fabric 组件树通过 C++ Props 结构体传递,序列化时直接内存复制
- 事件从原生回传 JS:原生侧调 `callFunction()`,触发 JS 事件监听器
- **桥接开销**:JSI 是同步的,无 JSON 往返,性能接近原生直接调用

来源:[React Native's New Architecture Explained](https://medium.com/@ripenapps-technologies/react-natives-new-architecture-explained-fabric-jsi-turbomodules-f8ec495e8d7c)

---

## 2. Raycast 扩展(原生限制设计)

### 可用组件

Raycast 以 React 写扩展,渲染成原生 macOS AppKit,限制组件为:

- **List**:单列表,多行项
- **Grid**:网格,项带图片
- **Detail**:详情页
- **Form**:表单
- **ActionPanel**:操作菜单(快捷键绑定)

来源:[Raycast API - User Interface](https://developers.raycast.com/api-reference/user-interface)

### 为什么这么限制

**核心理由**:保证用户体验一致性、键盘优先工作流。Raycast 自己构建了每个组件——响应速度、快捷键映射、焦点行为——都是从 power-user 角度设计,而不是通用控件。

开放自定义组件会导致:
- 破坏全局快捷键体系
- UI 风格参差不齐
- 键盘导航混乱

来源:[How Raycast standardises native Mac design of plugins](https://www.stijnbakker.com/how-raycast-standardises-native-mac-design-of-plugins)

### 渲染机制

Raycast 用自己的 React reconciler(非标准 DOM),将 React 组件树转成 Raycast 的渲染树,最后转成 AppKit 视图树。

来源:[How the Raycast API and extensions work](https://www.raycast.com/blog/how-raycast-api-extensions-work)

---

## 3. 字节/腾讯跨端方案

### Lynx(字节)

**特点**:
- 2025 年 3 月发布,Rust 编写,内置 PrimJS(自定义 JS 引擎)
- 双线程架构:UI 线程渲染 + 背景线程计算,减少卡顿
- 开发者写 React + 真实 CSS(不是 RN 的伪 CSS),通过 ReactLynx 框架

**原生渲染**:
- 每平台一套自适应渲染引擎
- 在 TikTok Studio / Shop 内部验证过

来源:[Lynx by ByteDance vs React Native](https://appwrite.io/blog/post/bytedance-lynx-vs-react-native)、[LynxJS: ByteDance's New Cross-Platform Development Framework](https://afangi.com/blogs/457/LynxJS-ByteDance-s-New-Cross-Platform-Development-Framework-Explained)

### Hippy(腾讯)

**特点**:
- JS 引擎绑定通信为核心
- 支持 React/Vue 写法,统一 API 跨 iOS/Android/Web
- 原生组件与模块分离,各平台独立实现但暴露同一 API

来源:[Hippy/README.md](https://github.com/Tencent/Hippy/blob/main/README.md)

### Weex(阿里)

**特点**:
- 原生组件直接渲染,支持 Vue/Rax
- 原生组件与模块都可扩展,多平台差异通过抽象化
- 编译到原生代码,高性能

来源:[Use Vue.js in Weex](https://weexapp.com/guide/use-vue-in-weex/)

---

## 4. 自定义 Reconciler(自造渲染器)

### React Reconciler 包

React 官方提供 `react-reconciler` npm 包,允许第三方创建自己的渲染器。核心是提供一个 Host Config 对象,定义"如何在宿主环境实现渲染"。

来源:[react-reconciler - npm](https://www.npmjs.com/package/react-reconciler)、[Building a Custom React Renderer](https://blog.openreplay.com/building-a-custom-react-renderer/)

### 成功案例

**Ink**(终端 UI):
- React 组件 → Terminal 输出
- 用 Yoga(跨平台布局引擎,和 React Native 同一个)做 flexbox 布局
- 流程:React commit → mutate 终端宿主节点 → Yoga 布局 → 屏幕缓冲 diff → 终端 patch
- 性能:React 处理状态/生命周期,Renderer 控制布局/绘制/写入成本

来源:[How Claude Code Uses React in the Terminal](https://dev.to/vilvaathibanpb/how-claude-code-uses-react-in-the-terminal-2f3b)、[Ink - React for CLI](https://github.com/vadimdemedes/ink)

**react-three-fiber**:
- React 组件 → WebGL(Three.js)
- 无开销:组件在 React 外渲染,只受 GPU 限制
- `<mesh />` 变成 `new THREE.Mesh()`,Three.js 对象都能绑定 React 事件与生命周期

来源:[React-three-fiber: 3D rendering in the browser](https://blog.logrocket.com/3d-rendering-in-the-browser-with-react-three-fiber/)

### 复杂度评估

- **编写 Reconciler**:3~5 千行 TypeScript
  - Fiber 理解(虚拟树、work 队列、优先级)
  - 40+ 个 Host Config 方法要实现
  - Yoga 布局集成(如有需要)

- **Preact 版本**: `preact-reconciler` <1KB,证明核心可以很轻

来源:[CodyJasonBennett/preact-reconciler](https://github.com/CodyJasonBennett/preact-reconciler)

### 轻框架对比(无 DOM 引擎运行时)

| 框架 | 包体积 | goja/QuickJS 兼容性 | 自定义 Renderer | 弱点 |
|---|---|---|---|---|
| **React** | ~40KB(min) | 是(JSX 需编译) | react-reconciler(官方支持) | 体积大,Fiber 复杂 |
| **Preact** | ~4KB | 是 | preact-reconciler | 更轻,生态小 |
| **Solid** | ~8KB | 是 | createRenderer(自己构) | 响应式编译时,自定义难 |
| **Vue 3** | ~35KB | 是(需 SFC 编译) | createRenderer(官方) | 体积较大 |

来源:[react-reconciler](https://www.npmjs.com/package/react-reconciler)、[preact-reconciler](https://github.com/CodyJasonBennett/preact-reconciler)、[React Three Fiber - Introduction](https://r3f.docs.pmnd.rs/)


---

## 5. 纯数据驱动 UI + 增量更新协议

### 设计思路

宿主不直接运行 React/Preact,而是:
1. JS 侧:维护 React/Preact 组件树 + 状态,导出**当前 UI 树的 JSON 描述**
2. JS ↔ 宿主:发送 **JSON diff/patch**,宿主增量应用
3. 宿主侧:JSON → 原生 View 树,只对有变化的部分重新布局/绘制

这样 JS 引擎可以任意(goja/QuickJS/V8),无需 reconciler 胶水层。

### 消息格式例

**完整 UI 树**:
```json
{
  "type": "VStack",
  "props": { "spacing": 10 },
  "children": [
    { "type": "Text", "props": { "text": "计数", "size": 16 } },
    { "type": "Button", "props": { "label": "点我", "id": "btn1" } }
  ]
}
```

**增量 diff**(jsondiffpatch 格式):
```json
{
  "children": {
    "0": {
      "props": {
        "text": ["旧计数", "新计数"]
      }
    }
  }
}
```

来源:[JsonDiffPatch](https://github.com/benjamine/jsondiffpatch)

### 优点与缺点

| 优点 | 缺点 |
|---|---|
| JS 引擎完全独立,可任意 | 每帧都要 JSON 序列化 |
| Patch 协议简单易扩展 | 列表虚拟化需特殊支持(指定 item key) |
| 宿主可以完全控制渲染 | 事件回传也是 JSON(有延迟) |
| 支持 Hot Reload(替换整个 JSON 再 diff) | 复杂交互(drag-drop、手势)需协议扩展 |

---

## 6. 自定义绘制:Canvas API(逃生口)

### 设计思路

不是所有 UI 都能用预设组件描述。需要提供"自绘"能力:

- 插件定义 `<CustomPaint id="chart1" />`
- 宿主侧创建一个画布,分配给这个 ID
- JS 回调:获得 Canvas API,直接发绘制指令序列

### Flutter CustomPainter 范例

```dart
class ChartPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    // canvas.drawPath(...), drawText(...), drawImage(...)
    // 支持:变换(translate/rotate/scale)、渐变、裁剪
  }

  @override
  bool shouldRepaint(ChartPainter old) => /* 是否重绘 */;
}
```

来源:[Flutter CustomPainter Guide](https://dev.to/kanta13jp1/flutter-custompainter-guide-drawing-custom-ui-with-the-canvas-api-4dgf)、[Flutter Canvas API: Getting Started](https://www.kodeco.com/26483389-flutter-canvas-api-getting-started)

### Skia 指令流

Flutter 底层是 Skia(Google 2D 图形库)。Canvas API 调用被编译成 Skia 指令序列,最终 GPU 执行。Avalonia/Compose 也类似。

**性能**:静态图形可用 `PictureRecorder` 缓存,避免每帧重计算。

### 用于 LinPlayer

提供 `<CustomCanvas id="..." width="..." height="..." onDraw={...} />`,回调参数是 Canvas 接口:
- `drawRect(x, y, w, h, color, stroke)`
- `drawPath(path, color, fill)`
- `drawText(text, x, y, font, size, color)`
- `drawImage(imageId, x, y, w, h)`

来源:[Definitive Flutter Painting Guide](https://getstream.io/blog/definitive-flutter-painting-guide/)

---

## 7. 主题系统:动态 Token 替换

### Avalonia(C# + XAML)

**机制**:ResourceDictionary + DynamicResource

```xml
<!-- 定义 token -->
<SolidColorBrush x:Key="PrimaryColor">#FF6200EE</SolidColorBrush>

<!-- 使用(必须 DynamicResource) -->
<Button Background="{DynamicResource PrimaryColor}" />
```

**运行时替换**:
```csharp
// 代码修改资源
Application.Current.Resources["PrimaryColor"] = new SolidColorBrush(Colors.Red);
// 或替换整个 Dictionary
Application.Current.Resources.MergedDictionaries.Add(newThemeDictionary);
```

DynamicResource 会自动监听资源变化,界面立即更新。

来源:[How to: Switch between light and dark themes](https://docs.avaloniaui.net/docs/how-to/theme-switching-how-to)、[Resources overview](https://docs.avaloniaui.net/docs/app-development/resources)、[ThemeDictionary + ThemeVariantScope PR](https://github.com/AvaloniaUI/Avalonia/pull/8166)

### Jetpack Compose(Kotlin)

**机制**:CompositionLocal + MaterialTheme

```kotlin
val LocalPrimaryColor = compositionLocalOf<Color> { Color.Blue }

@Composable
fun MyApp() {
  CompositionLocalProvider(
    LocalPrimaryColor provides Color.Red
  ) {
    Button(colors = ButtonDefaults.buttonColors(
      containerColor = LocalPrimaryColor.current
    ))
  }
}
```

**运行时替换**:
```kotlin
var currentTheme by remember { mutableStateOf(lightTheme) }
CompositionLocalProvider(
  LocalPrimaryColor provides currentTheme.primary,
  LocalSecondaryColor provides currentTheme.secondary,
) { /* 内容重组 */ }
```

Compose 会自动响应 CompositionLocal 变化,涉及的 @Composable 重新执行。

来源:[Material Theming with Jetpack Compose](https://developer.android.com/codelabs/basic-android-kotlin-compose-material-theming)、[Jetpack Compose Theme with CompositionLocal](https://medium.com/@kerry.bisset/jetpack-compose-theme-with-composition-local-spacing-shaping-and-status-colors-a00890724f9c)、[Mastering Color Theming in Jetpack Compose](https://proandroiddev.com/mastering-color-theming-in-jetpack-compose-a4ac53b9b7b5)

### 跨平台 Token 设计

两端都支持以下 token:
- **颜色**: primaryColor, secondaryColor, backgroundColor, ...
- **间距**: spacing(4, 8, 12, 16, 24, ...)
- **圆角**: radius(0, 6, 10, 999px)
- **字体**: family, size, weight

宿主导出一个 JSON token 文件,插件可以:
1. **读取全局 token**:`getTheme()` → JSON
2. **覆盖 token**:`setThemeToken("primaryColor", "#FF0000")`
3. **监听变化**:`onThemeChange()` 回调

来源:[Setting Up Material Theme Color Schemes in Jetpack Compose](https://medium.com/@rowaido.game/setting-up-material-theme-color-schemes-in-jetpack-compose-39140ea2e66a)


---

## 8. 官方组件"可被改写":插槽与替换注册表

### 设计思路

让插件替换官方详情页的某个区块,或改写一个组件。两种模式:

#### 模式 A:插槽(Slots)

官方组件预留多个**名字插槽**,插件可以提供自己的内容:

```javascript
// 官方的详情页骨架
function DetailPage({ mediaId }) {
  return (
    <VStack>
      <Header title="标题" />
      <Slot name="after-header" />  // 插件可在这里插入内容
      <Content description="..." />
      <Slot name="before-actions" />  // 又一个插槽
      <ActionPanel>...</ActionPanel>
    </VStack>
  );
}

// 插件侧
pluginHost.registerSlot("after-header", () => (
  <CustomBanner message="这是插件的横幅" />
));
```

Jetpack Compose 原生支持:**Composable lambda 参数**:

```kotlin
@Composable
fun DetailPage(
  mediaId: String,
  afterHeaderContent: @Composable () -> Unit = {},  // 插槽
) {
  VStack {
    Header("标题")
    afterHeaderContent()  // 执行插槽
    Content("...")
  }
}

// 调用时传入
DetailPage(
  mediaId = "123",
  afterHeaderContent = { CustomBanner("插件内容") }
)
```

来源:[Custom Composable Design Patterns](https://dev.to/myougatheaxo/custom-composable-design-patterns-reusable-ui-components-in-jetpack-compose-3n97)、[API Guidelines](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-component-api-guidelines.md)

#### 模式 B:组件替换注册表(WordPress Filter 模式)

官方在关键点调用一个"过滤器":

```javascript
// 官方核心
function renderListItem(item) {
  let component = item.type === "series" 
    ? defaultSeriesItem 
    : defaultMovieItem;
  
  // 关键点:允许插件替换
  component = applyFilter("render_list_item", component, item);
  
  return component(item);
}

// 插件侧注册替换
pluginHost.addFilter("render_list_item", (defaultComp, item) => {
  if (item.hasCustomBanner) {
    return (props) => (
      <>
        {defaultComp(props)}
        <CustomBanner />
      </>
    );
  }
  return defaultComp;
});
```

来源:[WordPress Filter Hooks](https://learn.wordpress.org/tutorial/wordpress-filter-hooks/)、[apply_filters()](https://developer.wordpress.org/reference/functions/apply_filters/)

### 两种模式优劣

| 项 | 插槽 | Filter 注册表 |
|---|---|---|
| 实现复杂度 | 低(预定义位置) | 中(需枚举过滤点) |
| 灵活性 | 受限(只能在预设位置) | 高(任意组件可被替换) |
| 插件冲突 | 多个插件竞争同一插槽 | 多插件链式调用(按优先级) |
| 学习曲线 | 低(看官方文档就知) | 中(需理解 filter 机制) |

**建议**:都支持。简单场景用插槽,高级场景用 Filter。

---

## 推荐方案

综合上述分析,针对 LinPlayer 插件系统的推荐架构:

### 1. JS 侧组件写法

**选择:Preact + 自定义 reconciler**

- **为什么**:
  - Preact 4KB vs React 40KB,在 Go 嵌入 JS 引擎里体积敏感
  - preact-reconciler 官方有支持
  - 生态成熟,JSX 支持完整

- **怎么写**:
  ```typescript
  import { h, Fragment } from "preact";
  import { useState } from "preact/hooks";
  
  export function MyDetailPage() {
    const [count, setCount] = useState(0);
    return (
      <VStack>
        <Text>{count}</Text>
        <Button onPress={() => setCount(count+1)}>+</Button>
      </VStack>
    );
  }
  ```

### 2. 宿主协议(Host Protocol)

**三层协议**:

#### Layer 1:组件树描述(JSON)

```typescript
interface UITree {
  id: string;
  type: string;  // "VStack" | "Text" | "Button" | "List" | "CustomCanvas"
  props: Record<string, any>;
  children?: UITree[];
}
```

**例**:
```json
{
  "id": "root",
  "type": "VStack",
  "props": { "spacing": 10, "padding": 8 },
  "children": [
    {
      "id": "title",
      "type": "Text",
      "props": { "text": "詳細", "size": 18, "weight": "bold" }
    },
    {
      "id": "btn1",
      "type": "Button",
      "props": { "label": "播放", "onPress": "btn_pressed" }
    }
  ]
}
```

#### Layer 2:增量更新(JSON Patch)

```json
{
  "updates": [
    { "id": "title", "props": { "text": "新标题" } },
    { "id": "counter", "props": { "text": "5" } }
  ]
}
```

#### Layer 3:事件回传

```json
{
  "event": "press",
  "componentId": "btn1",
  "timestamp": 1234567890
}
```

### 3. 原生侧基础组件初版清单

| 组件 | 参数 | 事件 | 说明 |
|---|---|---|---|
| VStack | spacing, padding, alignment | - | 竖排容器 |
| HStack | spacing, padding, alignment | - | 横排容器 |
| Text | text, size, weight, color, maxLines | - | 文本 |
| Button | label, color, onPress | press | 按钮,TV 需焦点 |
| TextField | value, placeholder, onChanged | changed, focus | 输入框 |
| Toggle | value, onChanged | changed | 开关 |
| List | items[], itemRenderer, onSelect | select, scroll | 列表,支持虚拟化 |
| Grid | items[], itemRenderer, columns, onSelect | select | 网格 |
| Image | source(URL), width, height | - | 图片 |
| ScrollView | - | scroll | 可滚动容器 |
| CustomCanvas | width, height, onDraw | - | 自绘画布 |

**TV 焦点处理**:每个交互组件分配唯一 focusId,宿主维护当前焦点,响应遥控器上下左右。

### 4. 主题 Token 开放

**官方导出 JSON token 文件**:

```json
{
  "colors": {
    "primary": "#6200EE",
    "secondary": "#03DAC6",
    "background": "#FFFFFF"
  },
  "spacing": {
    "xs": 4, "sm": 8, "md": 12, "lg": 16, "xl": 24
  },
  "radius": {
    "none": 0, "small": 6, "medium": 10, "full": 999
  }
}
```

**插件调用**:
```typescript
const theme = getTheme();
setThemeToken("colors.primary", "#FF0000");  // 自动刷新界面
```

**实现**:
- Avalonia:ResourceDictionary + DynamicResource
- Compose:CompositionLocal + CompositionLocalProvider

### 5. 插槽与 Filter

**官方组件预留插槽**:

```typescript
function DetailPage({ mediaId, afterHeaderSlot: AfterHeader }) {
  return (
    <VStack>
      <Header title="..." />
      {AfterHeader && <AfterHeader />}
      <Content>...</Content>
    </VStack>
  );
}
```

**Filter 过滤点**(进阶,允许多插件链式调用):

```typescript
// 官方核心
const ItemRenderer = applyFilters("list_item_renderer", defaultItem, { item });

// 插件注册
pluginHost.addFilter("list_item_renderer", (Default, { item }) => (props) => (
  <>
    <Default {...props} />
    {item.hasFlag && <CustomFlag />}
  </>
));
```

---

## 性能考量

### 列表虚拟化

宿主侧原生处理,JS 侧提供 `key`:

```json
{
  "items": [
    { "key": "ep1", "title": "第1集" },
    { "key": "ep2", "title": "第2集" }
  ]
}
```

### TV 遥控焦点

宿主维护焦点,JS 侧声明 focusId 和焦点关系:

```json
{
  "focusId": "btn_play",
  "nextFocusDown": "btn_pause",
  "nextFocusRight": "btn_info"
}
```

Compose 原生支持,Avalonia 需自己拦截方向键。

---

## 参考资源

- React Native Fabric: https://isitdev.com/react-native-new-architecture-fabric-turbomodules-2025-2/
- Raycast API: https://developers.raycast.com/api-reference/user-interface
- Lynx: https://appwrite.io/blog/post/bytedance-lynx-vs-react-native
- Hippy: https://github.com/Tencent/Hippy
- react-reconciler: https://www.npmjs.com/package/react-reconciler
- Ink: https://github.com/vadimdemedes/ink
- Flutter Canvas: https://www.kodeco.com/26483389-flutter-canvas-api-getting-started
- Avalonia Theming: https://docs.avaloniaui.net/docs/how-to/theme-switching-how-to
- Compose Theming: https://developer.android.com/jetpack/compose/designsystems/material
- WordPress Filters: https://developer.wordpress.org/reference/functions/apply_filters/
- JsonDiffPatch: https://github.com/benjamine/jsondiffpatch

