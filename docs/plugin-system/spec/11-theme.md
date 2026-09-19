# 11. 主题与壁纸

## 11.1 主题分端(D72 D210 D216 D512)

- 一个主题只服务**一个端**:桌面(Windows/Linux)、手机(android)、TV(android_tv)各是独立的包,作者自己命名区分,官方不做跨端映射,也不做分类。
- 三套 token 分开做、分开适配;某端没有主题的包在该端**不可选**,列表里显示「不支持此设备」。
- 一个插件包的贡献点可以任意组合(如「动漫模式」= 主题 + 首页接管 + 壁纸),但带主题时仍一包一端,多端就发多个包。
- `platforms` 字段照常用于市场过滤(D138)。

## 11.2 主题能改什么(D21 D211~D213 D522 D70 D71)

| 能改 | 说明 |
|---|---|
| 全部设计 token | 颜色、圆角、间距、字号、阴影、玻璃模糊…(桌面对应 `Theme/Tokens.axaml` 那一套 key,手机/TV 对应 `ui/theme/` 那一套) |
| 官方组件的样式定义 | 组件长什么样(各状态:常态/悬停/按下/聚焦/禁用/选中);行为与结构不变 |
| 字号刻度与密度 | 紧凑 / 宽松 |
| 海报尺寸与网格列数 | — |
| 侧栏/底栏样式与位置 | 如桌面侧栏改顶栏 |
| 页面转场动画 | — |
| 任意图标 | 按官方图标的稳定名替换(SVG),没给的用官方 |
| 全局背景 | 静态图或视频(壁纸,见 11.5) |
| 动效 | 任意关键帧动画,不限于调参数 |
| 字体 | 字体文件打进包 |
| 明暗 | 可只提供一种;只有深色的主题在系统切到浅色时保持深色 |

官方图标稳定名、token 名、组件样式键清单随 SDK 发布(附录 20.3 同批维护)。

## 11.3 桌面主题:Avalonia `.axaml`(D214)

- 主题 = 一个或多个 Avalonia 样式文件,与官方 `Theme/Controls.axaml` 同一套写法,运行时用 `AvaloniaXamlLoader` 加载,叠在官方样式之后。
- 已知且接受:`.axaml` 能实例化任意控件,等于跑原生 UI 代码(完全信任,D5)。
- 加载失败(语法错、引用不存在的资源)→ 回退官方主题启动,Toast「xx 主题加载失败,已改用官方主题 [复制错误]」,计入连崩判定(D372)。

## 11.4 手机/TV 主题:JSON(D215)

Compose 没法运行时加载任意代码,主题只能是宿主解释的数据。格式见 `api/theme-json.schema.json`,结构:

```jsonc
{
  "schema": 1,
  "modes": ["dark"],                          // 只提供深色时写 ["dark"]
  "tokens": { "dark": { "color.accent": "#7C9CFFFF", "radius.card": 10, … } },
  "components": {                             // 官方组件 × 状态的样式属性
    "PosterCard": { "default": { "radius": "token:radius.card" }, "focused": { "scale": 1.06, "borderColor": "token:color.accent" } }
  },
  "layout": { "density": "comfortable", "posterColumns": { "compact": 3, "expanded": 6 }, "navPosition": "bottom" },
  "motion": { "pageTransition": "fadeThrough", "keyframes": { "pulse": [ … ] } },
  "icons": { "play": "icons/play.svg" },
  "fonts": [ { "family": "Custom", "file": "fonts/a.ttf" } ],
  "wallpaper": { "image": "bg.webp" }          // 或 { "video": "bg.mp4" }
}
```

加载失败同 11.3 回退官方主题。桌面开发者模式有「手机/TV 主题预览」页,用官方组件样品即时渲染 JSON;真机上 `lp dev` 推送后重启生效(D369)。

## 11.5 壁纸(D212 D441~D443)

- **壁纸是独立接管位**,叠在任意主题上:主题自带的壁纸是默认,壁纸插件可覆盖;切换壁纸**不用重启**。
- 壁纸插件实现 `definePlugin({ wallpaper })` 给初始内容(图片 / 视频 / Canvas 区块 / 着色器),运行中 `wallpaper.set()` 切换。
- 壁纸插件能力:动态切换(按时间/每天/随当前页面或正在看的片变)、在线壁纸源、Canvas/着色器程序生成的动态壁纸、视频壁纸。
- 用户可调壁纸模糊度与压暗,保证文字可读(官方设置项,对任何壁纸生效)。
- **视频壁纸**:进播放页、应用到后台、系统省电时暂停;低分辨率播放,不占播放用的 mpv 实例。各端实现方式(第二个轻量 libmpv 实例或平台原生播放器)先 spike 再定。

## 11.6 切换与生效(D69 D441)

- 主题切换**重启生效**;开发模式热重载对主题不生效。
- 壁纸切换即时生效。
- 主题选择在插件页「接管位」标签(D222);选择不跨设备同步(D527)。
