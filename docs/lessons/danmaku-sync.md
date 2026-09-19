# 弹幕 / 弹弹Play

**这个领域最容易踩的坑:**
1. **弹弹Play 从不用 HTTP 状态码报错**,一律 200 + body 里的 `errorCode`;不看它就会把「配额 429」显示成「未找到弹幕」。
2. **发行包里的密钥是可提取的**(AES 口令是同一二进制里的硬编码常量),客户端限流拦不住外人;`AppSecret` 还可能是多串换行分隔,整坨拿去签名必 403。

> Bangumi / Trakt 同步、追剧日历、排行榜的宿主实现已于 2026-09-19 删除(改做官方插件),
> 它们的接口实测在 [plugins.md](plugins.md) 末尾。

> 本文件共 **3** 条。每条都标了它的原记忆文件名与类型;正文按原样搬运,未做压缩或改写。

## 本页条目

- 弹弹Play 三个静默失败 — `dandanplay-silent-failures.md`
- 弹弹配额被刷完 — `dandan-quota-drain.md`
- 弹弹多密钥轮换 — `dandan-multi-secret-rotation.md`

---

### 弹弹Play 三个静默失败

> 原记忆:`dandanplay-silent-failures.md` · 类型:`project`

2026-08-01。用户报「弹弹play 搜索不到弹幕、自动匹配不到，需要匹配算法」。
挂真接口(官方 AppId + 真签名,凭据在 `hjbl.env`,已 gitignore)一发就现形 —— **三条全是我们自己的静默失败**：

1. **弹弹Play 系接口从不用 HTTP 状态码报错**，一律 `200` + body 里的 `errorCode`。
   不看它 → `animes` 键不存在 → 解析成空表 → 界面说「未找到匹配的弹幕」。
   实测当天两个 search 端点**全部**回 `{"errorCode":429,"errorMessage":"已达到接口调用配额上限"}`。
   → 界面给的失败原因是假的。这类「界面在撒谎」见 [界面在撒谎:当前版本](ui-desktop.md)、[「待接」多半是谎](methodology.md)。

2. **`/match` 要求 `fileHash` 是形状合法的 32 位 hex**，空串直接 `errorCode:2 参数不符合规则`。
   A/B 实测(同文件名同签名)：空 hash **0 条** / 任意 32 位 hex **25 条且第一条就对**。
   `matchMode` 给不给、给哪个值结果**一模一样** —— 决定成败的只有 hash 的形状。
   → 这条「文件识别」路从接进来那天起没通过过一次。修法=文件名派生的确定性占位 hash。
   **另一个关键实测：`/match` 和 search 的配额是分开的**，search 429 时 /match 照常工作。

3. **前端把 `file_name` 传成 `it.name`**，对剧集就是「第 35 集」。
   而 `/match` 正是**按文件名做跨语种解析**的那条路(实测英文名 `Frieren...S01E03`、
   日文名 `葬送のフリーレン 第3話` 都能正确对到中文条目)。
   喂它条目名整条路白跑 —— 实测「第 35 集」返回的第一名是《NHK特集手塚治虫》。
   真文件名来源：Emby 的 `MediaSource.Name`(不含扩展名的真文件名)/ 网盘下载的 `path` basename。

**匹配算法本体**照 `D:\xiaochengxu\bangumi2anibt` 的 `matcher.c` 重写(归一化折叠 + Levenshtein
比率 + 长度加权包含下限)。旧的字符二元组 Jaccard×0.6 **天花板就是 0.6 而自动挂载门槛是 0.5**：
「葬送的芙莉莲」vs「葬送之芙莉莲」一字之差实测只给 **0.257**。另外它不做任何字形折叠
(全角/大小写/片假名平假名/标点全当不同字符)。
新增两路独立信号：**季号**(剥掉季号后同名条目相似度完全一样，分不开 → 第二季配第一季弹幕)、
**alt_titles**(弹弹Play 条目只有一个标题没有别名表，平行语料只能由我们这边给)。

**查法**：`node` 直接打 `api.dandanplay.net`，签名 `base64(sha256(AppId+ts+path+Secret))`。
别信 HTTP 200 —— 永远先打印 `errorCode`/`errorMessage`。

---

### 弹弹配额被刷完

> 原记忆:`dandan-quota-drain.md` · 类型:`project`

2026-08-02 用户:「我的默认弹幕源弹弹API 有人在刷我的配额,经常用完」。

##### ★ 结论先行:密钥在发行包里是**可提取**的
`crates/core/build.rs` 把 `DANDANPLAY_APP_SECRET` 用 AES-256-CBC 加密后编译进产物,
而**解密口令是同一个二进制里的硬编码常量**(`OBF_KEY`,secrets.rs 和 build.rs 各一份,
必须逐字节一致)。build.rs 自己的注释就写着「混淆级(抬高门槛),非绝对安全」。
AppId 更是明文(它本来就随 `X-AppId` 头发出去)。签名 `base64(sha256(AppId+ts+path+Secret))`
**全在客户端本地算**,没有任何服务端代理(oauth-proxy 只管 Trakt/Bangumi)。

→ 任何人拿到我们的 exe/APK 都能把密钥完整取出来直接用我们的配额。
→ **客户端限流只管得住我们自己的用户,拦不住外人。** 别把限流当成堵漏。

Go 侧同一形状:`core/cmd/sealsecrets` 加密、`core/secrets` 解密,
而 `obfKey` 仍是同一个二进制里的常量(`core/secrets/secrets.go:32`)。**换语言没有改变这件事。**

##### ★★ 2026-09-02:曾经建了服务端代理,又删了 —— 这是一次**取舍**,不是一次修复
唯一能真正堵住的做法是把签名挪到服务端,当时也确实做了(`crates/danmaku-proxy`,
带出站闸门 + 自托管弹幕库)。**用户 2026-09-02 决定删掉它**,理由是:
播放器不能有「弹幕要自己先部署一个服务才有」这种选项 —— 没有选配,只有要和不要。

所以现在的真实状态是:
- 密钥**内置在客户端**,可提取这件事**照旧成立**;
- 被刷爆时**没有闸门兜底**(代理那三样保命手段一起没了:出站闸门让后果退化成
  「弹幕变慢」而配额不掉、自托管库让一百人看同一集只打一次上游、配额烧光时仍有弹幕可发);
- **剩下的唯一手段是轮换 AppSecret 并发版**(弹弹允许一个 AppId 配多个 Secret,
  换行分隔 —— 见本文末尾那条)。

★ 别再提「挪到服务端」当解法了,那是已经被否掉的方向。要重新提,先解决
「用户不部署服务也得有弹幕」这个前提。

##### 我们自己烧掉的三份(都已修,82043159)
1. **`is_anime` 写了却从没有人调过** —— 宿主三处 `danmaku_sources(state, true)` 全写死。
   播欧美剧/综艺/纪录片照样打一整轮(`/match` + 最多 4 次 `/search/episodes`),
   而弹弹Play 根本不收录这些,一条候选都不可能有。
   ★ 判据必须是「**确信不是番**才排除」:`genres` 为空 = 没刮到元数据 = 不知道 → 放行。
   反过来写(空表就排除)会让所有没刮削的库弹幕**静默死掉**,比烧配额严重得多。
2. **桌面端 autoLoad 返回 null 后又原样调一次 danmakuMatch** —— 同入参同判据,
   而 `danmaku_auto_load` 内部刚跑完 `match_all` 并且已用 `MIN_AUTO_SCORE` 筛过,
   那一轮的 `top.score >= 门槛` 恒为 false。每次没匹配上的起播 = 双倍配额、零收益。
3. **主动搜索零频率限制**。现加 5 秒最小间隔,★ 只在会打到官方源时才拦
   (自建源是用户自己的服务器,给它限速纯属添堵);手动「重新匹配」共用同一闸门。
   选**拒绝**不选排队:连按五下会排出 25 秒的队,比报错难受。

##### 顺带抓到的第四个:半失败被吞成「没搜到」
`/search` 和 `/match` 的配额是**分开的**,一路 429、另一路正常回空是**实测常态**
(真机同一入参连打四次,第四次静默变 null)。`match_one` 旧判据是「两路都失败才报错」,
于是这种半失败一路传到界面 → 「未找到匹配的弹幕」。
那正是 2026-08-01 修过的那句谎话,**从另一条岔路长回来了**。
改成「零候选 **且** 任一路失败」就报。参见 [弹弹Play 三个静默失败](danmaku-sync.md)。

##### 护栏怎么钉的
- 「函数写了没人调」纯逻辑单测**照不到**(`is_anime` 自己的单测一直是绿的,
  它就是这么活下来的)。所以**两个宿主各钉一条源码级断言**:
  `danmaku_auto_load` 必须出现 `allow_official_for(&input.genres)`。
  两端各一份,合并成一条的话删掉其中一端不会红。同 [「待接」多半是谎](methodology.md)。
- 半失败那条走**真 HTTP**(本地 TcpListener 扮演 `/search` 回 429、`/match` 回空):
  要真有一路 Err 一路 Ok 才复现得出来。
- `ui/shared/danmaku-quota.check.mjs` 挂真实 exe 走 CDP。
  ★ 判据用**耗时**不用「有没有拿到弹幕」—— 上游不稳,拿它当判据会随机红,
  而随机红的门禁等于没有门禁([测试必须先红](methodology.md))。
  被门控挡掉时核层一个字节都不发(实测 33ms),真出网最快也 200~470ms,差一个数量级。
  这个脚本自己会打真接口,**别放进 CI 循环跑**,那是自己刷自己的配额。

相关:[弹弹多密钥轮换](danmaku-sync.md)(同一套凭据的另一个坑)、[CI 漏传编译期凭据](build-release.md)。

---

### 弹弹多密钥轮换

> 原记忆:`dandan-multi-secret-rotation.md` · 类型:`project`

**弹弹Play 允许一个 AppId 配多个 AppSecret 做配额轮换,换行分隔。**
GitHub Secret `DANDANPLAY_APP_SECRET` 里放的就是两串。
签名只能用**其中一串**,`sha256(appid+ts+path+"S1\nS2")` 必然签出一个谁也认不出的签名 → **HTTP 403**。

2026-07-21 事故:两条路径处理不一致 ——
- 弹幕 `danmaku::auth_parts`:有 `.split('\n').find(非空)` → 正常
- 排行榜 `ranking::fetch_dandan` → `dandan_creds()` → **整坨直接签** → 403 → 整页空白

**表现极具误导性**:"同一个 AppId、同一个密钥、都在 Repository secrets 里,
弹幕好好的,唯独排行榜不行" —— 看起来像弹弹平台不给排行榜权限、或者密钥填错了。
我一度签字说"GH secret 是错的",被用户的反例(弹幕能用)当场推翻。**用户是对的。**

**已修**:拆分下沉到 `secrets::dandan_app_secret()`(调用方只该关心"给我一个能用的密钥");
danmaku 那边的 split 保留,对单串是恒等变换。测试
`multi_secret_rotation_takes_only_the_first` 覆盖 CRLF / 前导空行 / 单串恒等,
反向注入验证过。CI 用真 GH secret 实测:修前 `HTTP 403`,修后 `返回 50 条`。

**定位手法(可复用,且不接触凭据内容)**:
AES-CBC 的**密文长度暴露明文长度**(补齐到 16 的倍数)。
比对两个构建里嵌入的 base64 密文串**长度**即可反推明文有多长 ——
本地 64 字符密文(=32 字符明文,一串)vs CI 产物约 108 字符(≈65 字符明文,两串+换行)。
全程只打印长度,不解密、不打印内容(直接扫描解密所有 base64 blob 会被安全策略拦,也确实不该做)。
定位密文位置的锚点:`OBF_KEY` 字面量,密文串就紧挨在它前面。

配套:[排行榜数据源:弹弹 trending 与 TMDB](plugins.md)、[CI 漏传编译期凭据](build-release.md)、[测试必须先红](methodology.md)。

---

## 连看下一集不该再搜一轮:集号接力(2026-09-10)

弹弹Play 系的 `episodeId` 是 `animeId` 拼四位集号,**同一部作品里连续**。
所以第 2 集的 id 就是第 1 集加一。旧路径每集都跑一整轮三路召回 ——
一部 24 集的番 = 24 轮 × 三次上游请求 × 每个源,而它们问的是同一部作品。
官方源那边是有配额的(见本文件「弹弹配额被刷完」那一段)。

实现在 `core/danmaku/relay.go`。三条判据缺一不可:

- **只认纯数字 id。** 自建源如果发的是 uuid 或带前缀的形状,加一得到的是一个
  不存在的 id,而上游只会回一张空表 —— 那时候宁可不猜。
- **跨度封顶 26 集。** 跳着看的话中间插过 SP 的作品每跳一集就多错一位。
- **判成功的是「取回来的弹幕非空」,不是「算得出来」。** 取到空表就当没猜过,
  回去老老实实搜。接力点也只在**取到东西之后**才记 —— 记一个空集会让后面一路错下去。

接力键优先用 bgmid(稳定主键),没有才退回归一化剧名 + 季号。表在内存里,
进程重启就没了 —— 一次连看的收益已经拿到,持久化不值那份复杂度。

## bgmid:借 Bangumi 的名字表,不是拿它去查弹幕(2026-09-10)

bgmid **不能**直接拿去弹幕源查:弹幕源的 `animeId` 是它自己的主键,和 Bangumi 无关。
能借的是 Bangumi 那份权威名字表 —— `/v0/subjects/{id}` 的 `name`(原名)和 `name_cn`。

媒体库刮的是中文名、弹幕源收的是日文原名时,标题相似度那一路分数恒为 0:
候选明明已经捞回来了,却被自己的评分扔掉。拿原名当主标题去搜,命中率完全不同。

- 入口两个:`MatchInput.bgm_id`(自动匹配,两端从 `emby.itemDetail` 的 `bgm_id` 带上),
  和手动搜索框里直接粘一条 `https://bgm.tv/subject/253` / `bgm:253`。
- **纯数字不算条目号**。「253」多半是片名的一部分,猜错了用户看到的是另一部作品,
  而且不知道为什么。
- 名字换不出来(bgm.tv 打不通)要**明说**:拿 `bgm:253` 这串去搜必然 0 条,
  而用户看到的会是「都没搜到」,以为源坏了。也因此**搜索框的默认词仍是剧名**,
  不预填 `bgm:id`。
- Emby 侧:`ProviderIds` 的键名各家刮削器写法不一(`Bangumi` / `bangumi` / `bgm`),
  **大小写不敏感**地比。分集自己没有这条元数据,要回头问它所属的剧 ——
  按 seriesId 在进程内记一次(`seriesBgmID`),否则连看一部番会多打 24 次 Emby。

## 搜索结果:每源封顶 8 条,组序就是设置里的顺序(2026-09-10)

用户口径:「每个源最多 8 个,方便换源」。搜索面板是**用来换源的** ——
一个源刷出四十条相似作品时,第二个源已经被顶到屏幕外面去了。

同时把 `searchAllGrouped` 里「按结果条数给组重排」那一句删掉:
组序改成**照配置表的顺序**。不然设置页里的「上移 / 下移」就是个摆设,
而摆着不生效的控件比没有更糟。

---

## 跨域交叉引用

这些条目和本领域强相关,但正文放在别的文件里(一条经验只存一份正文):

- [CI 漏传编译期凭据](build-release.md) — 弹幕/排行榜的凭据是编译期注入的,漏传就静默残废
- [本周看板定案+PC视觉自检](methodology.md) — 追剧日历本周看板的版式定案与视觉自检法
- [mpv 字幕属性实测](player-mpv.md) — 弹幕曾占用次字幕位,受 secondary-* 属性限制

---

## 弹幕掉帧的真因是每帧重排版,不是画得多(2026-09-11)

用户原话:「弹幕移动起来很掉帧」。两端的绘制层都栽在同一类错上,但**症状相同、
根因各不相同**:

| 端 | 每帧在干的事 | 代价 |
|---|---|---|
| PC(Avalonia) | 每条弹幕 `new FormattedText` **两次**(正文 + 描边垫底) | 一次 `FormattedText` 是完整的文本整形。屏上 40 条 × 2 × 60Hz = 每秒 4800 次整形,外加同样多的 `SolidColorBrush` 进 GC |
| 安卓(Compose) | 用 `Paint.Style.STROKE` 描边 | 描边文字走**轮廓路径**那条路:逐字取 Path 再填,**绕开字形缓存**,而缓存正是 `drawText` 快的全部原因 |

解法也各不相同:

- PC:把排好的字形按「字号档」缓存在 `DmItem` 上,每帧只把同一组字形挪位置。
  档位号 `_gen` 在字号 / 粗细 / 透明度变化时 +1,旧缓存自然作废。
  **滚出屏幕的要就地丢掉** —— 不丢的话一部番看完攒着上万份排版结果。
- 安卓:描边改成「先画一层深色偏 5% 字高,再压上正文」。两次都吃字形缓存,
  代价接近零,而看上去是同一回事(PC 那层一直就是这么画的)。

判据不是「看着顺不顺」,是**一帧里有没有重新排版**。

## 「弹幕抽帧」两端两个原因(2026-09-12)

用户:「桌面端&移动端 弹幕滚动看起来还是抽帧一样」,并给了 mpv 那边
`uosc_danmaku.conf` 加 `vf_fps=yes` / `fps=60/1.001` 的解法。
**那条不适用**:我们的弹幕不是 mpv 画的(桌面是 Avalonia 自绘层、安卓是 Compose Canvas),
没有 mpv 的滤镜链可加。但它指对了方向 —— 问题在**帧节拍**,不在绘制开销。

**桌面**:`DanmakuLayer` 用的是 `DispatcherTimer { Interval = 16ms }`。
那是自己定的闹钟,和刷新率对不齐,周期性地一帧画两次、一帧不画。
帧率数字是满的,眼睛看到的是抽帧。改 `TopLevel.RequestAnimationFrame`。
**同一个坑滚动那边(`Smooth`)早就填了,它的注释里就写着这句话,而弹幕这层漏了。**

**安卓**:帧循环本身是 `withFrameNanos`(对齐的),但
`LaunchedEffect(position, paused, speed)` 把**轮询回来的播放位置当成了 key** ——
position 一秒变 4~10 次,于是帧循环一秒重启 4~10 次:每次硬把钟拽到轮询值上,
起手那句 `withFrameNanos` 还白丢一帧。改成一条长命循环 + 每帧软对表(`dmTick`):
差超过 1 秒当 seek 硬对,否则每帧只追 8%。

> 通例:**轮询值不能进帧循环的 key。** 它的更新频率是 4~10Hz,
> 而帧循环要 60Hz 连续 —— 拿低频信号去重启高频循环,一定会看到低频那个节拍。

## 上面那条通例,PC 端只改了一半(2026-09-16)

用户四天后又报:「弹幕的滚动还是卡,肉眼可见的卡」。

上一条在桌面端做的是**把 `DispatcherTimer(16ms)` 换成 `RequestAnimationFrame`** ——
那治的是「帧节拍」。但通例的另一半(**软对表**)只在安卓落了地,PC 的 `Sync()` 一直是:

```csharp
_clock = position;   // 每 250ms 硬拽一次
```

而 `position` 就是 mpv 的 `time-pos`,它**只在视频帧边界更新**(24fps 片源 = 41.7ms 一个台阶),
再叠上 FFI 往返的抖动 —— 等于每秒把整屏弹幕拽四下,可前可后,往后拽的那一下就是肉眼看到的顿。

照搬安卓的 `dmTick`:差超过 1 秒当 seek 硬对,否则每帧只追掉 15% 的误差。
注意**先按旧的暂停态算出当前钟,再换新态** —— 反过来的话暂停那一拍会少推一截。

> **为什么会漏掉一半**:上一条经验里「安卓」那一段写的是安卓的修法,
> 而通例写在最后。两端同病时,通例段落要**逐端点名谁做了谁没做**,
> 否则读的人只会去看自己那一端的段落,而那一段里没有另一半。

## 弹幕搬到合成器线程,60Hz → 跟随刷新率(2026-09-16)

`TopLevel.RequestAnimationFrame` 在 Win32 上**不等于 vsync**:`Win32Platform` 绑的是
`new DefaultRenderTimer(60)` —— 写死 60,不问刷新率、不对齐 vblank。
所以上一条改完之后,高刷屏上弹幕每秒**仍然只有 60 个位置**。

### 三条走不通的路(别再试一遍)

替换那个 timer 的三种写法**全部在编译期被引用程序集挡死**,不是运行时问题:

| 写法 | 报错 |
|---|---|
| 自己 `implements IRenderTimer` | `CS0535` —— Avalonia 在接口上放了一个不可访问成员,错误文本直接写 `not implementable by user code` |
| 继承 `DefaultRenderTimer` 重写 `StartCore` | `CS0115 没有找到适合的方法来重写` |
| `new DefaultRenderTimer(hz)` + `AvaloniaLocator` 注入 | `CS0117 AvaloniaLocator 未包含 CurrentMutable`、`CS1729 不包含采用 1 个参数的构造函数` |

☠ **运行时 dll 里这些成员样样都在,引用程序集里一个都没有。**
反射 `build/pack/**/Avalonia.Base.dll` 得到的修饰符**不能替编译期签字** ——
这一条上连栽三次。要验 API 可不可用,写个最小探针让**编译器**回答。

### 通的那条:CompositionCustomVisual

合成器有自己的节拍,不受那个 60Hz timer 管。但**光搬过来还是一顿一顿**,见下一节(合成模式)。

- `CompositionCustomVisualHandler` 可继承,`OnAnimationFrameUpdate` 是**自循环**的 ——
  它自己注册下一帧,但**第一次得有人在外面踢一脚**。漏了这一脚的表现是帧回调 0 次,
  而那和「节拍被卡在 60」长得一模一样,差点据此判定整个方案不可行。
- 时钟用 `CompositionNow`(protected TimeSpan),不是 `DateTime.UtcNow`。
- UI 线程靠 `SendHandlerMessage` / `OnMessage` 往合成器线程送状态,别跨线程碰字段。
- `ImmediateDrawingContext` **没有 `DrawText`**,只有 `DrawGlyphRun`。字形要用 `TextLayout` 排:
  逐个 `TextLine.TextRuns` 里的 `ShapedTextRun` 取 `ShapedBuffer`,按 `line.Baseline` 和累加的 `Size.Width`
  自己建 `GlyphRun` → `TryCreateImmutableGlyphRunReference()`,一条弹幕可能是好几段。
  ☠ **不能直接 `TextShaper.ShapeText`**:它只认给它的那一个字体(默认 Segoe UI),中文 / 日文 / emoji
  全变 glyph 0,屏幕上「一堆口口口」(2026-09-17 用户报)。`TextLayout` 才做逐段字体回退。
  ☠ **`ShapedBuffer` 是池里借的**,`TextLayout` 一 Dispose 就还回去被别人写花:GlyphInfo 要**拷出来**,
  不拷的表现是一半的段落 0 个字形(探针实测 21 个字只剩 6 个),而且不报错。
- ☠ `GlyphRun` 的 `BaselineOrigin` 是**构造时烤进去的**。跟着每帧的 x 去建就是
  「每帧重排版」,正是上一条刚修掉的病。正解:字形固定在原点,位置全靠
  `ctx.PushPreTransform(Matrix.CreateTranslation(...))`,零分配。
- `ElementComposition.GetElementVisual` 在刚挂上树那一刻可能还是 null(实测泵了 500ms 才拿到),
  所以每次要用都试一遍,别只在 `OnAttachedToVisualTree` 里试一次。

**失效条件**:哪天 Avalonia 的引用程序集把 `IRenderTimer` 那条路放开,
或者默认 timer 改成跟随刷新率,这一整套就可以退回普通 `Control.Render`。

## 合成器 180 FPS 照样卡:合成模式不按 vblank 出帧(2026-09-17)

用户:「弹幕滚动还是卡,你根本就没修好」。上一节的探针只数了**总帧数**(180Hz 屏 180 FPS 判绿),
没量**帧间隔**。补上之后(`LP_DMPROBE`,记每次 `OnRender` 的 Stopwatch 时刻):

| 合成模式(`Win32PlatformOptions.CompositionMode`) | 3 秒帧数 | 晚于 1.5 拍的间隔 | 最长间隔 |
|---|---|---|---|
| WinUIComposition(**默认**) | 99 FPS | 255 / 314(81%) | 16ms |
| DirectComposition | 94 FPS | 270 / 295 | 16ms |
| RedirectionSurface | 63 FPS | 196 / 196 | 31ms |
| **LowLatencyDxgiSwapChain** | **180 FPS** | **0 / 567** | 7.5ms |

垫一层真 mpv 画面(`LP_DMPROBE=<24fps 片子>`)再量:默认模式 80 FPS、84% 晚拍;低延迟交换链 179 FPS、0~3 个晚拍。
**视频本身两边一样**:vo_delayed 0、drops 0、出帧间隔 41.6ms 抖 ~3ms。

**为什么肉眼是顿**:位置按「开画那一刻」的时钟算,却要等下一个 vblank 才上屏;
帧间隔在 6~16ms 之间乱跳,「算的时刻」和「上屏时刻」的差也跟着乱跳,匀速滚动就被抖成一顿一顿。
**平均帧率再高也没用** —— 之前那句「179.3 FPS 跟上了 180Hz」就是这么假绿的。

修法:`Program.CompositionModes()` 把 `LowLatencyDxgiSwapChain` 排第一(其余三种按原顺序兜底)。
代价按官方注释是「不能透明」,本程序主窗不透明;全窗 selfcheck 截图、标题栏、播放页 OSD 都正常。
`LP_WINCOMP=WinUIComposition` 回旧模式做 A/B。

**「交给 mpv 画」为什么不选**(用户同时提了):mpv 的 `gpu/video.c` 里字幕的时刻是
`p->osd_pts = p->surfaces[surface_now].pts` —— 开了插帧也是**视频帧的 pts**,ASS 的 `\move`
在 24fps 片子上只有 24 个位置;这正是 2026-09-10 从 osd-overlay 搬出来的原因。

**失效条件**:Avalonia 的默认合成模式改成按 vblank 出帧,或低延迟交换链在某版本不可用
(Avalonia 会静默落到下一种模式 —— 探针的晚拍数会立刻变红)。

## 弹幕搜索框的默认词:异步覆盖赶不上用户回车(2026-09-16)

用户:「搜索栏里面的默认还是集名字,不是该条目的名字」。

2026-09-10 已经改过一次(`SeriesTitle()`:剧集取 `series_name`,电影取 `name`),
判据是对的,但它是**异步**的 —— 面板弹出时先 `box.Text = _title` 上屏,
一次完整的 `emby.itemDetail` 往返回来才覆盖。那几百毫秒里框里摆的正是集名,
**而用户看一眼就回车了**。

两条一起补:① 剧名问过一次就缓存住(起播时 `StartDanmaku` 那条路已经问过);
② 还没问到时用 `FallbackTitle()` 砍掉 `DisplayTitle` 里「剧名 · 集名」的后半截,
别直接上 `_title`。

> 通例:**「异步把它改对」不算改对。** 默认值、占位符、预填内容这类东西,
> 用户是照着**第一帧**做决定的。判据应当是「第一帧就是对的」,
> 而不是「最终会变成对的」。
