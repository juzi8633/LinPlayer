# 插件系统:运行时 / 宿主契约 / 分发

> 旧插件系统(QuickJS → goja,`core/plugin/`)已整体删除,新系统从零重做,
> 设计正本见 [`docs/plugin-system/SPEC.md`](../plugin-system/SPEC.md)。
> 本页只留**新系统照样会踩**的坑;只和旧设计(权限弹窗、`httpAllowedHosts` 白名单、
> 旧 `ctx.*` 形状、四类贡献点、`.ipk` / `registry.json`)绑定的条目已删。
> 2026-09-19 宿主的排行榜 / 付费追剧日历 / 字幕翻译 / Trakt·Bangumi 同步整体删除(改做官方插件),
> 它们的接口实测搬到本页末尾五条。

**这个领域最容易踩的坑:**
1. 调用超时必须是**空转看门狗**,不是总墙钟 —— 等用户填表 / 等网络不能计时。
2. JS 运行时**不是并发安全的**:异步结果必须投回运行时所在的 goroutine 再造值、再 resolve。
3. 插件类改动**只有真机端到端才现形**,编译绿 + 单测绿是常态。
4. 新加的宿主能力 = 旧版 App 装新插件必然退化,不能只更新插件。
5. 清单 / 索引里的键名是硬契约:拼错一个字母,整条插件**静默消失**且零报错。

## 本页条目

- 运行时:超时、goroutine、内存、UA
- 宿主契约:静态声明、数据源动词、UI 树
- 打包与分发
- 苹果CMS 采集接口实测
- UHD 求片站 / 测速接口实测
- UHD 测试账号
- 数据源页面的四个坑
- Bangumi 接口实测
- Trakt 接口实测
- 付费解锁(爱发电订单号):用户口径与架构
- 排行榜数据源:弹弹 trending 与 TMDB
- 字幕翻译与 Whisper 转写:引擎与链路实测
- TVBox 兼容:真实配置实测
- 安卓壳:WebView 与 jar spider

---

### 运行时:超时、goroutine、内存、UA

**超时 = 空转看门狗,不是总墙钟**(SPEC D12「单次调用超时中断」照此实现)

- Flutter 时代 `callTimeout` 30s 用 `future.timeout(30s)` 包住整个 handler Promise,
  **等用户填表 / 等网络也在计时** → 交互式多步流程必被 30s 杀 + 插件自动禁用
  (日志 `PluginTimeoutError 调用超时 30000ms`)。改法:宿主记「在途宿主调用数」+「最后一次活动时间」,
  只有**既无在途宿主调用、又超 30s 无交互**(纯 JS 死循环)才判失控。
- goja 版同一思路:`vm.Interrupt` + 墙钟 30s,**每次触碰宿主都把 deadline 往后推**。
- quickjs-go spike(2026-08-31)实测的另一半:中断处理器只在 JS **正在执行**时被调用,
  `await` 期间不触发;但 `await` 恢复后插件还要接着干活,**deadline 若没在每次泵作业时重置,
  恢复后的那段活会被立刻杀掉**。反向注入「泵作业时不重置 deadline」→ 长等待被误杀,实测红。
- 同一 spike 的两个注入陷阱:① 第一版把死循环放在**另一个 goroutine** 上 Eval,违反运行时单 goroutine 约束,
  不但没测到东西,还**污染了后面所有测试**(本来通过的变成失败)—— 死循环类注入要开**子进程**;
  ② 第一版「不重置」注入里 `await` 完就 `return`,代码短到中断检查根本没触发,把真约束测成了不存在,
  改成「等完之后接着干 300ms 的活」(真实插件的形状)才红。**注入本身有 bug 比没有注入更糟。**
- 失效条件:新系统若改按 CPU 时间计超时,第一条自然成立,deadline 重置那条仍要验。

**运行时不跨 goroutine**

- goja 的 Runtime 不是并发安全的(`docs/research/plugins-v2/02-embeddable-runtimes.md` 同结论),
  旧实现把所有对 VM 的触碰投进一条 jobs 通道串行执行。
- quickjs-go spike 实测到的失效形态:在**非 owner goroutine** 上造对象,resolve 出去后 JS 拿到 `undefined`,
  **不报任何错**(`TypeError: cannot read property 'status' of undefined`)。常量值(`NewNull()`)不分配所以不受影响
  —— `sleep` 一直是好的,换成返回对象的 `http` 才坏。**简单的先写、先测、先通过,复杂的后写,正是最容易漏测的形态。**
- 正确形状:goroutine 里只做 Go 的事(发 HTTP / 读盘)→ 投回 JS 所在 goroutine 再造值并 resolve;
  泵作业的每一轮排干这条队列。goja 上这条失效形态未单独实测,按 Runtime 非并发安全照做。

**大响应体不能整个读进运行时**

- UHD 测速的官网文件是 32 / 64 / 100 MiB。Flutter 时代插件的 http 把整个 body 读进 64MB isolate → 大文件 OOM。
  当时给宿主 http 加了 `discardBody`(按流丢弃、只数字节,内存恒定)。
- goja 不能按运行时限内存(SPEC D141),一个 100MiB body 直接压在进程 Go 堆上 ——
  新宿主的 fetch 要有流式 / 丢弃 body 的形态,否则测速类插件会触发全局水位看门狗。
- 单次请求内拿不到实时百分比 → 测速下载阶段只能用不定态进度条。

**出网默认必须带 UA**

- 采集站的海报和 m3u8 都不需要 Referer、无防盗链、无 302,但**空 UA 会被部分 CDN 403**;
  旧 `ctx.http` 默认一个头都不发,插件必须自己设。宿主 fetch 要默认带 UA,
  且**插件自己给的 UA 不许被宿主盖掉**(`network.md` 里 `TestImgSendsUA` 钉的同一条)。

**真机端到端**

- 旧市场 + 声明式 UI 接入那一轮(2026-07-23)**7 个 bug 全是编译绿 + 单测绿**,只有真机端到端才现形。
  共性是「两边都不报错,只是功能不对」,逐条见下一节。
- 插件每轮都因没真机验证而返工:宿主 UI 能力有限要先摸清,发版前在真机装一遍跑通。
- **新宿主能力 = 旧 build 装新插件必坏**:build493 不认表单的 `type:'select'` → 退化成文本输入框
  (用户原话「让我填写」);不认带图列表 → 没有列表。新能力必须让用户重新构建 App,
  SPEC D36 的 `minAppVersion` 就是为这个。

---

### 宿主契约:静态声明、数据源动词、UI 树

**清单静态声明与运行时注册必须合并,不能整条顶掉**(SPEC D34 同样是「清单声明、代码实现」)

- 清单写**描述**字段(数据源的 name、登录表单),运行时交**行为**字段(几个回调),两边天然各写一半。
- 旧实现第一版直接 `*slot = c`,插件一注册回调,清单里的 `name` 和 `auth` 就没了 ——
  「添加服务器」页拿到一个**没有任何输入框**的插件源,名字退化成源 id。2026-07-23 真机端到端跑出来的。
- 合并方向:同名键运行时赢,清单只填空缺。测试要让**两边都带同名键**才钉得住方向 —— 方向写反了测试照样绿。

**键名与错误口径**

- 贡献点类型名是写在用户清单里的字面量,也是前端查询的键:改一个字母,所有已发布插件的那一类贡献**静默消失**。
- 撞上废弃字段要报「这是老插件,请获取新版」,不是 JSON 语法错。能力表删一项必须同时进「已删除 + 人话原因」表;
  只删一半,老插件撞上「未知权限: xxx」,看起来像 App 的 bug 而不是设计。
- 启用回调里的异常不许吞:旧版 `onEnable` 错误被 `let _ =` 吞掉 → 插件「已启用、无错误」,面板却空白。
- `register` 收到非对象描述照单全收,编出 `ext_N` 幽灵贡献 —— 描述必须是对象且有非空 `id`,否则当场抛。
- 解析失败要计数上报:索引条目全解析失败时报「0 插件 0 错误」,和空源无法区分;
  市场缓存只存插件不存错误 → 第二次进入时警告条消失。

**数据源动词的返回约定**(SPEC D40 的 home / category / search / detail / play 同样适用)

- 播放地址 `url` 必填,空 / 缺失直接报错 —— 放过去播放器收到空地址,表现是「点了没反应」。
- 分页 `hasMore` 缺省 **false**;缺省 true 会让前端无限拉空页。
- 插件没实现某个动词(返回 null)≠「结果 0 条」,要当 unsupported 静默换路,不弹红字。
- 列表**逐条跳过畸形项**,整体不是数组才报错。
- 错误文案含 `401` / `unauthorized` / `登录` → 标成鉴权错,UI 引导重登。
- 「是不是视频」复用宿主的扩展名表,别让插件自己维护一份 —— 漂移的后果是「某格式在内置源能播、插件源里根本不显示」。
- 下发给插件的服务器信息是**显式白名单**字段,不是整包 —— 宿主以后加字段不会自动流进所有插件。

**插件交来的 UI 树是不可信数据**(SPEC D23 组件树序列化后交原生渲染,同样适用)

- 旧声明式 UI 的配额 `MAX_DEPTH=12` / `MAX_NODES=400` / `MAX_CHILDREN=100`;超深子树**整棵丢掉,不截断成半棵**;
  不认识的节点类型直接丢 —— 将来加新块,老宿主上是「少了一块」而不是崩。
  配额不是洁癖:一棵递归树能把渲染栈打爆,透明窗口下白屏看起来就是「整个 app 打不开」。
- 链接协议白名单只放 `https://` / `http://`;`javascript:` / `data:` 是现成注入面。
- 宿主参考示例一度教的是 `key` / `default`(错的,实际键是 `id` / `value`),消毒器遇到没 `id` 的输入控件返回 null
  → **整个表单一片空白、日志里什么都没有**。示例的集成测试用假 host 硬编码返回值,根本没跑到那段映射。
- 表单的提交按钮由宿主固定提供,不画进插件树:插件忘写按钮 = 一个关不掉的弹窗。
- handler 返回 null 时必须重拉一次界面:绝大多数 handler 只干件事(改开关、发请求)然后返回 null,
  「返回了树才更新」= 点了完全没反应。
- 需要返回值的宿主 UI 调用(表单 / 列表 / 对话框)旧实现**没有超时**,前端不回,插件的 `await` 永远悬着
  —— 前端关弹窗必须显式回 null。

**宿主界面口径**(旧 `UI_PC.md` / `UI_MOBILE.md` 插件章节里与新 SPEC 不冲突的几条)

- 插件贡献的数据源,登录表单由清单声明的字段**现渲染**:不这样的话每接一个插件源都要回来改一次宿主,
  「用户自己做插件自己用」就是空话。插件字段值单独存,别和内置输入框混。
- 「整页」形态的贡献需要宿主给入口,否则声明了也永远打不开。
- 插件启用后要按贡献点给一句**「去哪用」** —— 2026-07-23 调研 Kodi / Jellyfin / HACS,三家共同缺这一条。
- 列表里已装置顶、可装在下(HACS 的做法),不让用户在两个页面之间来回找。
- 插件没有设置项时,它的设置入口整个不出现,不画一个空页。
- 「已装」经常是空的 —— 做成同一页的 tab 而不是两个入口,空 tab 比空页面便宜。

**宿主代发 Emby 请求要防 SSRF**:`base.join(path)` 之后 scheme / host / port 必须仍等于服务器本身,
否则拒绝 —— 否则插件拼一个外站路径就能把 `X-Emby-Token` 带出去(守 SPEC D11)。
旧宿主代发时用的是账号主键 `account.server` 而不是当前生效线路 `active_line_url()`,与 `knowledge/EMBY.md` §1.6
「Session.server 必须是当前生效线路」的口径不一致,旧代码里没有解释这一选择的注释 —— 新宿主按 §1.6 走。

---

### 打包与分发

- **产物必须可复现**:打包时间戳 / 文件顺序 / 权限位钉死,索引里**没有任何时间戳**
  (试过 `datetime.now()` 每次刷新;用 git 提交时间则新插件首次提交时取不到值 → CI 必红)。
  `Path.write_text` 默认在 Windows 上把 `\n` 翻成 CRLF,必须显式 `newline="\n"`,否则跨平台产物不一致、**只有 CI 红**。
- **跨仓库契约只靠宿主测试守**:旧官方源曾全空,根因是插件仓库 `tools/build.py::_author()` 把清单里的字符串
  包成 `{"name": …}` 写进索引,而宿主的 `author` 是字符串 → 整条反序列化失败 → 8 个插件全部静默跳过 →
  市场显示「0 插件 0 错误」。七行代码,两边都不报错。解法是在宿主测试里把**构建脚本真实产出的形状一字不改**钉住;
  另一条硬契约是版本键 snake_case(`package_url` 不是 `packageUrl`)。
- 插件仓库的校验脚本 `--selftest` 往干净清单里注入 23 条真实坏值,任何一条没让它变红就失败。
- 图标构建期压成 data URI 内联(零额外请求、不受图床可达性影响),因此设了 64KB 上限。
  分发通道见 [分发通道 GitHub 优于 CF](build-release.md)。
- 市场里「可装版本」要取**版本号最大值**,不是数组第一个 —— 上游返回顺序不可依赖(同 GitHub `/releases`)。
  多个订阅源并发拉,**单个源拉不到只标那个源**,不整页失败。
- Go 的 `archive/zip` **不做**路径逃逸检查(Rust 的 zip crate 有 `enclosed_name`),解压插件包时要自己补。
- 插件数据目录:不要用系统的「应用配置目录」API —— 那类路径由 identifier 推出另一个根,会在 `%APPDATA%`
  下再开一份,改 identifier 就让已装插件静默失联。所有数据的唯一出口是 `core/paths`;
  插件数据与插件代码目录分开放,升级 / 重装不丢数据。

---

### 苹果CMS 采集接口实测

> 从旧 VOD 资源站插件(2026-08-01)的记录里搬来。🔒 原文含真实站点,已替换为「采集站甲~戊」。
> SPEC D7 的 TVBox type 0/1 数据源对接的就是这套 `…/api.php/provide/vod/` 接口,换了实现照样成立。

**资源站不是文件树。** 第一版把资源站塞进网盘文件页,因为当时数据源契约只有
`{id,name,isDir,isVideo,size,thumb,raw}`。用户列的六条毛病**全是这一个决定的症状**:分类只能伪装成文件夹、
翻页只能伪装成一个叫「下一页」的文件夹、「更新至17集」只能拼进 name、打开只能是文件管理器的双击。
卡片的角标 / 年份 / 评分必须是独立字段;分类**不能和内容平铺**(分类没有图,平铺进海报墙就是一排空盒子),
要做成顶部 chip 横条。这个只有真渲染看得见,DOM 断言全是绿的。

**接口实测,不是文档:**
- **`ac=detail` 同样吃 `t` 和 `pg`,一次回 20 条 × 83 字段,含 `vod_pic` 和 `vod_play_url`。** 这是整个架构的支点。
  `ac=list` 每条只有 8 个字段、**没有海报也没有播放地址**,用它就得「列 20 条 → 再打 20 次详情」。
- 搜索**只有** `ac=detail&wd=` 有效。`ac=list&wd=` 会返回**全站内容**,看起来像搜到一堆其实一条没匹配 —— 很安静的坑。
  搜索并进分类列表(带 keyword)走同一条分页,否则搜索的翻页会漏写。
- 每页恒 20 条,无 limit 参数;`limit` 字段是**字符串** `"20"`。
- `vod_play_from` / `vod_play_url` 用 `$$$` 分多线路(两边 1:1 对齐),`#` 分集,`$` 分集名和地址。
- **顶级分类基本是空的**(采集站甲 `t=2`、采集站乙 `t=1` 都 total=0),内容只挂叶子分类。
  点父级时**自动落到它的第一个子分类**,别把用户扔进空页。
- **有的站 `class` 里根本没有 `type_pid`**(采集站丁只有 type_id + type_name),父子关系无从得知,
  那种站上「电影 / 连续剧」点进去就是空的 —— 站点数据如此,不是对接漏了什么。
- **有的线路给的是网页播放页不是流**:采集站丙某条线路是 `/share/<hash>`,GET 回来 `<!doctype html>`;
  同片的 m3u8 线路才是真流。解法 = 同一部片内部**按媒体扩展名取舍**(有真流的就不摆网页那条;
  一条都认不出时全留,无扩展名的直链是存在的)。
- 海报和 m3u8 都不需要 Referer、无防盗链、无 302;但**空 UA 会被部分 CDN 403**。
- 故障有两种要分开报:返回 HTML 错误页(采集站戊)vs JSON 被截断(采集站丁出现过)。
- 速度实测:`ac=detail` 53KB / 0.83s vs `ac=list` 7KB / 0.75s —— **瓶颈是 RTT 不是体积**,换轻接口省不下来;
  能改的是观感结构(骨架先出 + 首屏预抓两页 + 分页缓存)。
- 仓库里不出现任何采集站域名(用户 2026-08-01 定);本地清单 `vod.json` 在 `.gitignore` 里。

---

### UHD 站点接口:2026-09-21 复测,旧插件那份**已经不对了**

重写 UHD 助手时拿真站逐个打了一遍。和旧插件(`com.linplayer.uhdnow`)里写的比:

| | 旧插件写的 | 真站现在 |
|---|---|---|
| 全部求片 | `POST media-requests/list` | **404**;换成了话题广场 `POST media-requests/topics/list` |
| 投票 | `POST/DELETE media-requests/{id}/vote` | **整个没了**(前端那个模块里一条 vote 都不剩) |
| 提交求片 | `{request_type, media_type, tmdb_id, title, content}` | 只有 **4 个字段**,没有 `title` |
| 流量 | `{used_bytes, limit_bytes}` | 多了 `display_unlimited_traffic`(不限流量档) |
| 求片类型 | missing / refresh | missing / refresh / **feedback** |

**照旧代码抄一遍就会交出一个「全部求片点开永远空」的插件**,而且不报错 —— 404 的 HTML
被当成「解析失败」吞掉。逆向官网前端那条路(见下一节)花了十分钟,比抄快。

拿到的完整表(全部实测 200):

```
POST /api/v1/auth/login                    {username,password} → {token,expires_at}
GET  /api/v1/traffic/me                    → {used_bytes, limit_bytes, display_unlimited_traffic}
GET  /api/v1/users/me                      → {name, balance, invite_code}
POST /api/v1/media-requests/search         {keyword,request_type,page,page_size}
POST /api/v1/media-requests                {request_type,media_type,tmdb_id,content} → {topic_id}
POST /api/v1/media-requests/topics/list    {page,page_size} → {list,total}
POST /api/v1/media-requests/mine/list      同上
GET  /api/v1/subscriptions/domains         → [{id,name,description,domain,normalized_host}]
GET  /api/v1/subscriptions/domains/{id}/resolve → {domain}  ← 动态 CDN 节点,别缓存
POST {节点}/api/v1/speed-test/session      {parent_domain_id,size_mib} → {session_id,report_token}
GET  {节点}/api/v1/speed-test/download?size_mb=&session_id=
POST /api/v1/speed-test/report             {session_id,report_token,average_mbps,peak_mbps,
                                            elapsed_ms,sample_count,server_downloaded_bytes}
```

几个只有实测才知道的:

- **token 是裸值**,`Authorization: <token>`,不是 Bearer;前端还同时带一个同名 Cookie。
- **`resolve` 回来的 domain 前面可能带一个空格**(真站上就有一条)。不 `trim()` 的话拼出来
  的地址是坏的,而表现只是「这条线路测速失败」。
- **前 300 毫秒 / 1 MiB 是热身**,官网不把它算进平均。算进去的话测出来的数明显偏低。
- 提交求片的 `content` 留空 → `{ok:false,msg:"参数验证失败"}`。**这条错误路径可以放心测**,
  它不会在站点上留下垃圾;成功路径会真的建一条求片,而用户自己删不掉,要服主删。

新宿主的 `fetch` 支持 `res.body.getReader()`,所以测速能**流式计字节**,内存恒定,
而且能驱动真实进度条 —— 旧栈那条「单次请求拿不到实时百分比,只能用不定态进度条」
的结论**在新栈上不成立了**。

判据:`core/plugin/uhd_plugin_test.go` + 假站 `core/internal/fakeuhd`。
假站照真站的怪癖来(裸 token、resolve 的空格、说明必填、下载大小必须等于会话大小),
四处反向注入逐个验过会红。**其中两处第一次没红**:只断言「页面上出现 Mbps」的话,
服务端因为大小对不上只回 39 字节、插件照样算得出一个数 —— 把判据改成「下到 1 MiB」才有牙。

---

### UHD 求片站 / 测速接口实测(旧栈时期,部分已过时,见上一节)

> 🔒 原文含真实地址/账号等具体值,已替换为占位符。
> 这些是第三方站点的事实,与插件系统实现无关;UHD 系列插件(流量 / 线路测速 / 求片)
> 在独立的插件仓库,新系统上重写时照样用得上。

UHD(<UHD 求片站>)三个插件都复用同一登录:`POST /api/v1/auth/login {username,password}`
→ `{ok,data:{token,expires_at}}`,token 作 `Authorization: <原始token>` 头(**非 Bearer**)。

**逆向官网接口的方法**(官网是 React Router SPA,接口全在 `/api/v1/`):
1. `curl /speed` 拿 HTML → 找 `/assets/manifest-*.js`
2. manifest 里 `"routes/xxx"` 映射到 `module:"/assets/<route>-*.js"` 的路由 chunk
3. 下载 route chunk + 共享的 `api-*.js`(fetch 封装,baseURL 空=相对,Authorization 头)
4. `grep '/api/v1/...'` 拿端点,读 minified 上下文拿 body 字段

**端点链**:测速 `subscriptions/domains` → `/{id}/resolve` → `{线路}/speed-test/session {parent_domain_id,size_mib}`
→ `/speed-test/download?size_mb=&session_id=` → `/speed-test/report`。
求片 `media-requests/search` → `media-requests`(创建,`request_type=missing` 求片 / `refresh` 追新)
→ `media-requests/mine/list`。响应列表容器是 `data.list`。

**求片**
- 「参数验证失败」根因 = **create 的 `content`(说明)必填**(前端 `if(!o.trim())error("请填写具体说明")`,
  官网标「说明(必填)」)。留空 → 服务端拒。搜索结果项带 `tmdb_id`。
- search 返回「服务端错误」(500)的排查法:同 body 用坏 token 打 search 是**干净 401**(`invalid or expired token`)
  → 鉴权已过、是服务端处理阶段 500。当时的根因假设(未用真号验证):官网前端同源 fetch 会自动带
  `Authorization` Cookie(api-client 用 `document.cookie` 设的)+ Origin / Referer,插件只发了 header;
  修法是鉴权请求补 `Cookie: Authorization=<token>` + `Origin` + `Referer`。
- 曾有段时间 `request_type:"missing"` 搜索恒返回 `{ok:false,msg:"服务端错误"}`(服务端 TMDB 搜索路径故障),
  服主后来修好了,现 missing 搜索与 create 都正常(建出 `status:pending`)。
- **`poster_path` 三形态**:完整 URL(TMDB 直链)/ `/img/...`(UHD 自托管,`<UHD 求片站>`+path 返 jpeg)/ 裸 TMDB 路径。
- 搜索结果含 `exists_in_library` / `allowed_to_create`;对已在库且 `allowed_to_create:false` 的条目提交 missing
  → 服务端回 `媒体库中已存在该影片`(列表里标「已在库」)。没有用户自删求片的接口,测试建出的求片要服主删。

**测速**
- 官网测速文件大小 = **32 / 64 / 100 MiB**(前端 `Ls=[32,64,100]`),官网单线路流式下载不缓冲。
- `POST {线路}/speed-test/session` 的 `size_mib` **只接受 32/64/100**,填 8 → `参数验证失败`。
- `download?size_mb=` **必须等于**会话的 size_mib,否则只回约 39 字节 → **只能单会话单次下载,不能分段**。
  旧插件 1.0.2~1.0.4 用 8MiB 分段驱动进度条 → session 直接被拒,测速全废。
- resolve 返回的 `data.domain` 是子线路,session / download 打这个;`parent_domain_id` 用原列表项 id。
  列表项字段 `{id,name,description,domain,normalized_host}`,**UI 只显示 name**(不暴露 domain,用户要求)。
- **中国大陆线路 resolve 到 `.online` TLD 的备用域**(不是主域的 `.com`)。**resolve 出来的是动态 CDN host,别穷举**。
- **节点按账号分配、稳定**:测试账号大陆线路稳定 resolve 到 `china-vod3`,用户账号稳定到 `china-vod4` ——
  从自己账号 curl **看不到用户的节点**。验证线路类改动必须看**用户日志里的真实 host**,不能只凭自己账号 resolve。
- 测速会耗真实账户流量(每线路 × 大小)。

---

### UHD 测试账号

> 🔒 原文含真实地址/账号等具体值,已替换为占位符。

UHD(<UHD 求片站>)**测试账号**(用户提供,服主已授权测试,可直接 curl 实测接口):
- 用户名:`<测试用户名>`
- 密码:`<测试密码>`

**同一套账密也能登 Emby 测试服 `https://<Emby 测试服 A>`** —— 见 [Emby 测试服务器](emby.md)。
别混:`<UHD 求片站>` 是**求片站**(自家 `/api/v1`),`<Emby 测试服 A>` 是 **Emby 媒体服务器**(标准 `/Users/AuthenticateByName`)。

登录时用 `--data-binary @file`(含中文用户名)避免 shell 编码问题。

---

### 数据源页面的四个坑(从已删的「影视目录」页抢救,2026-09-19)

新 SPEC D40 让数据源页面由官方画,这四条照样成立:

- **资源站不是文件树。** 旧实现曾复用文件浏览页,六个毛病全是这个决定的症状:分类伪装成文件夹、
  翻页伪装成一个叫「下一页」的文件夹、「更新至 17 集」只能拼进文件名。角标 / 年份 / 评分要各占各的位置。
- **首屏要预抓几页。** 一页内容铺不满一屏 → 没有滚动条 → 无限下拉永远不会被触发。
- **有子分类的父分类本身多半是空的**,点它要直接落到第一个子分类,不是把用户扔进空页。
- **详情关掉后,海报墙的滚动位置要还在;单击就打开,不是双击。**

---

### Bangumi 接口实测(从已删的宿主同步 / 追剧日历抢救,2026-09-19)

宿主里的 Trakt / Bangumi 同步与追剧日历已删,将以官方插件重做。下面是接口本身的怪癖,插件照样会撞上:

- **API 走官方,图片走反代。** 用户实测:第三方 anibt 的 **API 反代过不了 CF**,但它的**图片反代**没问题;
  官方图床 `lain.bgm.tv` 国内常不通。所以是「API 官方 + 图片 anibt」这个反直觉组合。
- **授权页在主站,不在 API 子域。** API 切到官方之后,`/oauth/authorize` 必须独立指到主站,
  打到 API 子域直接 404。回调页在自建 oauth-proxy 上(`docs/oauth/bangumi.html`),地址属于我们的中转,编译期注入不进源码。
- **授权码深链 `linplayer://sync-bangumi?code=...` 不可信**:调用方必须先弹确认再拿去换令牌,
  否则一个网页就能把用户绑到攻击者的 Bangumi 账号上。空 `code=` 不能拿去换。
- **个人访问令牌(Access Token)登录完全不经代理**:代理挂了 / 共享密钥轮换了照样能登;
  没有 refresh_token,有效期由 Bangumi 定(通常一年)。**存之前立刻打一次 `/v0/me` 验一下** ——
  废令牌存进去,设置页显示「已连接」而每次同步都静默失败。遥控器上主推令牌:授权码 30+ 字符区分大小写,敲一次好几分钟。
- **官方 API 根本没有放送时刻**(2026-07-16 curl 实证):`/calendar` 只有 `air_date`(无时刻)+ `air_weekday`,
  条目详情的 infobox 也只有「放送开始 / 放送星期 / 播放电视台」。拿 air_date 硬凑 = 显示 00:00 假时间。
  用户选了 **bangumi-data** 数据集(npm 包 `bangumi-data@0.3` 的 `dist/data.json`,7.4MB):
  `broadcast` 是 RFC5545 `R/<ISO UTC 起始>/P7D`,条目自带 `sites[].site=="bangumi"` 的 subject id,**精确对得上不靠标题**。
  实测本周覆盖 **72/111≈64%**,没覆盖的**不显示时刻,不编**。只留 `id→起始时刻` 小索引(约 1800 条)落盘,TTL 7 天。
- **`air_date` 是首播日,不是本周这一集的日期。** 拿它跟本周比对会把整条丢掉 → 放送表全空。
- `/calendar` 的 `summary` 字段整周 111 条**全是空串**(字段在、值不给);真简介只在 `/v0/subjects/{id}`,要按需拉。
- 图片地址是协议相对的 `//lain.bgm.tv/…`,要补 `https:`;海报优先 `large`,`common` 放大到卡片上发虚(用户 2026-07-16「好模糊」)。
  封面实测比例 **0.707~0.711(≈5:7)**,不是 2:3。
- **0 分 = 没人评过**(新番常见),不是「这片 0 分」—— 滤掉,别画出来。名字中文名优先,没有才用原名。
- **单集写入路径的 subject 位必须是字面 `-`**:`PUT /v0/users/-/collections/-/episodes/{episode_id}`;
  带 subject_id 的只有批量 `PATCH /v0/users/-/collections/{subject_id}/episodes`(body `{episode_id:[...], type}`,官方注明它会重算完成度)。
  旧代码写成 `.../collections/{subject_id}/episodes/{episode_id}`,**永远 404**,「在看」那条恰好路径对所以只有它能成。
  EpisodeCollectionType:0 未收藏 / 1 想看 / 2 看过 / 3 抛弃;SubjectCollectionType 是另一套,**3 = 在看**。
- **更单集之前先把条目设成「在看」**:未收藏的番直接更单集会失败。代价是重看已「看过」的番会被降回在看(罕见,可接受)。
- **同步类调用别返回裸 bool。** 上面那个 404 活了几个月没人看见,就因为 `is_success().unwrap_or(false)`;
  失败要带状态码 + 响应体前 200 字。
- **按标题反查条目要设门槛**:旧匹配器「日期对不上就无条件取 `results[0]`」。改成复用弹幕那套标题评分,
  低于 **0.45** 判「没匹配上」—— **标错条目比不标更坏**(往用户账号里写别人的番)。门槛先筛再按总分排,
  反过来会让日期碰巧对上的噪声挤掉真本体。
- 手动「标为看完」也要触发同步,不能只在播到 80% 的停止上报里做。

---

### Trakt 接口实测(从已删的宿主同步抢救,2026-09-19)

- **进度同步用 Scrobble API**(`/scrobble/start|pause|stop`):起播发 start(账号上显示「正在观看」),
  停止发 stop 带真实 progress%,Trakt 自己在 **≥80%** 判看过、<80% 存续播点。取代旧的 `/sync/history` 完播打卡。
  上报失败只返回 false 不抛:上报是记账,播放是主线。**没有外部 id(ProviderIds)就别发。**
- **判「播完」读 mpv 的 `eof-reached`**:`keep-open=yes` 下 END_FILE 永远不发,等它等于「播完从不同步」。
- **设备码登录**:需要 client_secret 的两步(申请设备码 / 换 token / 刷新)走自建 oauth-proxy,客户端只持公开 client_id。
  轮询的状态码语义照 Trakt 原样:**400 = 还没授权(不是错)、429 = 问太快了(间隔 +5s)**、404/409/410 = 过期、418 = 用户拒绝。
  一律当失败的话,用户还没来得及点授权就被告知失败。**间隔听服务端给的 interval**,自己拍更短会被限流(「码是对的但一直连不上」)。
- 令牌给的是 `created_at + expires_in(秒)`,绝对过期时刻要自己算;判过期留 **60 秒余量**;刷出来的新令牌要落盘,否则每次启动都刷。
- **Trakt 自己不发图**,只给 `ids.tmdb`:封面要拿 tmdb id 去 TMDB 查 poster_path(按 id 去重、并发受限),
  所以依赖 TMDB 密钥。`first_aired` 本身是精确时刻,时间不缺。
- 放送表 `/calendars/all` 是**全站火喉**,一次几千条,必须截断(旧实现截 200);起点往前 7 天、共 21 天 ——
  「昨天更新的那集」要在表里。
- 需要 client_secret 的那几步不经代理就做不了:构建没注入代理地址时要明说「这个构建没有配同步服务」,不假装成功。

---

### 付费解锁(爱发电订单号):用户口径与架构(从已删的付费追剧日历抢救,2026-09-19)

- **付费墙是用户要的,别自作主张删。** 2026-07-16 我一度误删了 gate,被当场骂回:「放出来又免费吗?逗我呢」。
  用户要的是「**解锁后**免登录也能看放送表」,不是免费。见 [别过度解读需求](methodology.md)。
- **软锁,故意不加固**:开源客户端里「已解锁」判断可被改。订单号 → 自建 oauth-proxy 的 `/api/afdian/verify`
  → 代理持爱发电 token 调 `query-order`(md5 签名)→ `{valid, planTitle, amount}`。**客户端不接触爱发电 token**;
  路由受 `_middleware.js` 的 `X-LinPlayer-Key` 共享密钥保护。
- 校验失败**带着 reason 返回**,不抛错:没填 / 没配代理 / 订单无效 / 网络失败,每一种要给用户的话都不同。
- **赞助(收款)地址只能有一份,而且必须来自构建注入**:2026-07-19 UI 里写死了一个凭空猜的主页,功能看着完全正常,
  **赞助收益却是零** —— 收款地址是那种「错了也不会报错」的东西。它也是账号地址,不进提交。
- 已知边界:解锁标记 per-device(换设备要重填订单号);订单号可转发(没做设备绑定)。

---

### 排行榜数据源:弹弹 trending 与 TMDB(从已删的宿主排行榜抢救,2026-09-19)

- **错误必须说人话地冒出去,不许吞成空数组。** 2026-07-21 用户报「榜单没数据」,当时 fetch 里有 6 条 `return vec![]`:
  缺凭据 / 请求失败 / 非 JSON / success=false / 缺字段全部长成空榜,分不清是「构建没注入密钥」还是「服务端拒签」。
  UI 侧同理:**「空表(没凭据)」和「取不到(命令失败)」必须分开显示**,见 [排行榜「没有凭据」可能是一句假话](ui-mobile.md)。
- **没有「排行榜开关」。** 分类为空的唯一原因是打包时没注入凭据;没凭据的那一族分类不亮(亮出来点进去必然是空的)。
  TV 端曾写「排行榜默认是关的,去设置里打开」,用户照着找只会翻个空。写这类提示前先 grep 那个设置真的存在。
- 动漫 = 弹弹Play `/api/v2/trending/all/{hot,rising}/{week,month,quarter}` 与
  `/api/v2/trending/new-anime/hot/{current-season,previous-season}`。**需签名**,与弹幕共用签名算法;
  签名路径 = 请求路径(含 `/api/v2` 前缀,不含 query)。字段 `bangumiList` / `animeId` / `animeTitle` / `imageUrl`
  与官方 swagger 逐个核对过。匿名请求一律 403 `X-Error-Message: Missing Authentication Headers`。
- **403 的真因是多串轮换密钥**:GH Secret 里是两串换行分隔的 AppSecret,排行榜把整坨拿去签名(弹幕那边有拆分所以正常)。
  修前 CI 实测 `HTTP 403`,修后 `返回 50 条`。被排除的假设(都验过):凭据没注入、签名算法错、UA、CI 机房 IP、Secret 要手动加密。
  见 [弹弹多密钥轮换](danmaku-sync.md)。
- 影视 = TMDB,密钥自动识别 **v4 Bearer(含点)/ v3 api_key**。TMDB 只给 `poster_path`,图床前缀要自己拼;
  **id 可能是数字也可能是字符串**,解析要两种都吃(移植时错过一次)。榜单文件缓存 6 小时。
- **第三方图床要进本地图片代理的白名单,而且要在每次按账号表整表重建时补回来**:只在注册时放行一次的话,
  排行榜第一次打开有图,之后随便切一下服务器 / 改一下线路,图就全没了,**一点错都不报**。
- 本地构建没有凭据,版式要靠假榜单看;真机自检把两个上游与图床都指到假服务器,**覆盖基址时要同时放行对应图床**,
  否则自检里「数据有、图全空」,和白名单漏了的真实症状混在一起就白验了。
- 前三名金 / 银 / 铜三色要**两两不同**(「三个都是金色」这种退化截图上要盯着看才发现),第四名起回普通色。
- 榜单条目**不在用户的媒体库里**:卡片不能复用带「标记已看 / 收藏 / 屏蔽」右键的媒体卡,那三项点下去只会报错;
  点条目的合理去向是拿标题去搜索。换分类比请求快得多,回来的旧结果要丢掉,别画到新分类上。

**放送表 / 排行榜的界面口径(用户定,插件重做时照旧)**

- 2026-09-18 用户:「重做 排行榜 和 追剧日历 样式」—— 日历从「七列横滚看板」改成「周几日期条 + 当天整宽海报墙」
  (打开日历最常见的目的是看今天更新了什么);排行榜两个下拉换两排 chip(分类十来个,藏在下拉里就得点开才知道有什么),
  前三名单独一排大卡、其余海报墙。
- **今天居中**【用户定】(日期条从今天往前三天排起;周一 / 周日是今天时自然靠边,那不是 bug);
  标题**不许截成「…」**;封面按源站的竖版等比放、不裁;不上背景模糊。
- **「今天是周几」按上游时区 JST 判**:按本地时区,国内用户每天 23:00~01:00 看到的「今天」是错的。
- 简介缓存里存 `null` 代表「查过了,确实没有」,不能用「键不存在」表示,否则每次展开都再查一遍。
- 打开外部链接(赞助页等)失败要说出来,静默失败会让用户以为按钮是坏的。

---

### 字幕翻译与 Whisper 转写:引擎与链路实测(从已删的宿主字幕翻译抢救,2026-09-19)

- **五种引擎**:OpenAI 格式、Anthropic 格式(整批送,模型看得到上下文,质量更好也更省请求)、百度通用、百度大模型、腾讯机器翻译。
  存盘键是字面量,改了等于把用户的选择作废。设置里的 apiKey / secretKey 明文落盘,与账号 token 同等姿态。
- **百度免费版 QPS=1,必须串行**;单条 `q` 上限 6000 字节,按 50 行 / 2000 字双限分批。
  `sign = MD5(appid + q + salt + 密钥)`,多条用 `\n` 拼成一个 `q` 提交,`trans_result` 按行回包。
- **腾讯批量接口 `TextTranslateBatch` 不支持源语言 auto**,源语言未知要退回支持 auto 的单条 `TextTranslate`;
  批量保守取 50 条 / 4000 字,免费 QPS 低,串行。
- 引擎实现必须保证**返回列表与输入等长、顺序一致**,服务层靠这个把译文贴回字幕条目。
- **全部条目都失败 = 引擎根本不可用**(没开通 / 鉴权错),直接报错;静默产出一份原文文件,用户会以为「翻译了但没变化」。
- **Emby 字幕导出路由各服不一**:`/Subtitles/{i}/Stream.srt`、`/Subtitles/{i}/0/Stream.srt`(StartPositionTicks 段)、
  服务端给的 DeliveryUrl。要逐个试,**并校验内容像字幕**(含 `-->` / `Dialogue:` / `WEBVTT` / `[Script Info]`),
  否则 404 的 HTML 页被当字幕,报「源字幕解析为空」而真原因是地址不对。`Path` 是服务端本地路径,**不能当 URL**。
- 同一(源、引擎、目标语言、排版)命中缓存直接复用,别重复烧额度。
- 解析口径:SRT 毫秒位不足要**右补零**(`,5` 是 500ms 不是 5ms);ASS 时间是 `H:MM:SS.cc`,**百分秒**不是毫秒。
- **翻完必须直接挂上**,只返回路径 = 摆了个按钮不接线。挂成次字幕时,`sub-add` 把新轨排在最后,
  要挂完读一次 `track-list` 取**最大的 sid** 再设 `secondary-sid`;直接写 `secondary-sid=1` 会切到内封第一条,
  表现是「次字幕出来了,但不是译文」。次字幕 `sub-add` 的 flag 用 `auto`,不能 `select` 占掉主轨。
- 实时翻译:换引擎 / 语言前**先停旧轮询**,否则两句译文交替闪;单句失败不停整个轮询(限流 / 抖动常见),但要告诉前端。
  译文未到时:双语显示原文占位,仅译文显示空。
- **Whisper 模型几百 MB 到几 GB,放 data/ 不放 cache/**(清缓存会删掉,重下代价太高);下载强制 https
  (自定义镜像可能填 http,明文下载会被中间人替换);流式写临时文件,完成后原子改名,中断不留半截「已下载」。
  认不出的模型键**报错,不静默回落默认档**。下载进度事件限流到每 200ms 一条,否则几 GB 的下载刷爆事件队列。
- **探测 whisper / ffmpeg 可执行要按 exe 名缓存**:每次探测真 spawn 子进程(最多 4 次),不缓存 =「每次打开字幕翻译都卡」;
  Windows 上还要 HideWindow,否则黑框一闪。ffmpeg 包内路径含版本号,**按文件名找不按路径找**;
  Linux 上游是 `.tar.xz`,Go 标准库解不了,明确报错让用户走包管理器。
- 语言码:未知码**原样喂给模型**,归一码剥掉的地区后缀对模型可能有意义。
- 设置写回要**在当前设置之上反序列化**:从零值开始的话,前端只传了一个目标语言,其它字段全被清空;
  老配置缺新键要逐个补默认,不能整份回默认。

### TVBox 兼容:真实配置实测(2026-09-20,插件 0.1.0)

跑法:`go test -tags realsites -run Real -v ./datasource/`(`core/datasource/tvbox_real_test.go`)。
地址只从被忽略的 `docs/plugin-system/tvbox-test-sites.local` 读,日志里抹成 `<地址>`;
`REAL_DEBUG=1` 打插件最近日志,`REAL_DUMP=<文件>` 把失败请求的原始地址写进本机文件再拿 curl 对照。

- **拉配置要用 okhttp 的 UA**:多仓样本里两个仓托管在按 UA 分流的中转上,浏览器 UA 拿到的是下载页 HTML,
  `okhttp/3.12.13` 才拿到藏着配置的图片。症状是「配置不是 TVBox 格式」+ detail 里 `invalid character '<'`。
  站点请求(资源站接口、drpy 抓页)仍用手机浏览器 UA,两处不要合并。
- **`2423` 开头的 AES-CBC 配置要先 trim**:格式按定长从尾部截 26 个 hex 当 iv,文件末尾多一个换行就错一位,
  报 `encoding/hex: odd length hex string`。
- **「JS 写一个校验 cookie 再刷新」的防护页**:不用真跑脚本,抠出 `document.cookie="k=v"` 带上重试一次即可;
  CF 的「Just a moment」挑战页照旧报 `needVerify`,交给界面的[去验证](整页 WebView)。
- **多仓里一个仓挂了只跳过它**(`ui.toast` 说出来),全挂才报错。实测 14 个仓里 3 个是真挂(连不上 / 403 / 404)。
- **「第1集$」这种没有地址的集要滤掉**,不然起播报「缺少 episode_id」。
- 资源站的「主域名」通常**不是接口域名**(实测两个都在 `/api.php/provide/vod/` 下 404,首页是 CF / JS 防护),
  要拿站点公布的采集接口地址测苹果CMS 那条路。
- 抽样 25 个源的失败分布:站点本身不可达(Go 报 `Get ...: EOF`,curl 同样 000)占大头;
  `{"parse":1}` 需要 WebView 嗅探、测试里没有壳,真机上走 WebView2 / Android WebView。
  **失效条件**:站点和配置天天在变,这些比例只代表 2026-09-20 这一份样本。

### 安卓壳:WebView 与 jar spider(2026-09-20)

- jar 跑在 `:spider` 独立进程(D354);`Application.onCreate` 要按进程名判断,**子进程不起核心层**
  (一个数据目录只能有一个核心层实例)。
- **Android 14 起 DexClassLoader 只加载只读文件**:jar 落盘后 `setReadOnly()`,否则直接拒绝加载。
- spider 句柄由**主进程**分配并记住 load 参数:子进程崩了重启,旧句柄在那边不认识,主进程自动重新 load 再调,
  插件手里的句柄一直有效。
- jar 反射调用宿主的 `com.github.catvod.crawler.Spider`、也常直接用宿主的 OkHttp:R8 必须 keep
  `com.github.catvod.**` / `okhttp3.**` / `okio.**`,否则发行包里 jar 加载即 `NoClassDefFoundError`。
- **明文 HTTP 必须全局放开**(`res/xml/network_security_config.xml`):插件 WebView 和 :spider 里的 jar
  走的是 Java 层网络,受 NetworkSecurityPolicy 管;TVBox 站点大多是 http。不放开的表现是嗅探页不加载、
  jar「下载失败」,假站一个请求都收不到。Go 核心层的请求不受这条管,所以以前只放行回环没出过事。
- **jar 源的 `ext` 原样交给 spider 的 init**,宿主不代拉:真实配置里 jar 的 ext 常是 spider 自己解析的 URL,
  代拉会把网页当 ext 传进去(实测拉到 404 直接报「站点上没有这个内容」)。drpy 的 ext 才是规则地址要拉。
- **真机自检**:`bash scripts/selfcheck-android-tvbox.sh`(五个场景:首页 / 直链 / 网页嗅探 / jar / jar 代理)。
  x86_64 模拟器的 ARM 转译跑我们的 arm64 核心层会 **SIGILL**(berberis `UndefinedInsnThunk`),要编 x86_64 那份;
  x86_64 用的是单体 libmpv(ffmpeg 静态链在里面),`build-core-android.sh` 在没有 libavcodec.so 时不链 `-lavcodec`。
  模拟器截图拍不到视频层,起播的判据是假站收到了 m3u8 之后的分片请求。
  内存紧时模拟器 + Gradle + Go 一起跑会被系统回收,先分步编好再 `LP_SKIP_BUILD=1` 跑场景。
- 插件用的 WebView 全挂在 MainActivity 顶层一个 FrameLayout 里,平时 `translationX` 平移到屏幕外 ——
  alpha=0 会挡触摸,INVISIBLE 可能暂停渲染;要用户动手时挪回来,同一个 WebView,页面状态不丢。

## goja 与最小 DOM 的三个坑(2026-09-21)

- **`goja.Callable` 不能直接 `vm.ToValue` 交回 JS。** 它的签名是 `(this, ...args)`,
  goja 会把 JS 那边的**第一个实参喂给 `this` 这一位** —— 回调收到的第一个参数永远是
  undefined。表现是「回调被调到了、也没报错,就是拿不到按键名」。
  要包一层 `func(c goja.FunctionCall) goja.Value`,里面用 `c.Arguments...` 转发。
- **`uiruntime.js` 是拼接出来的不是打包出来的**(`tools/uibundle/build.mjs` 直接拼 UMD)。
  往 `src/*.js` 里写 `import` 会让整份运行时**语法错**,而报出来的是
  「装 UI 运行时失败: SyntaxError … Unexpected reserved word」,看起来像 goja 不支持某个语法。
  要新增一份生成代码,就生成成普通脚本(`var X = ...`)并在 build.mjs 里多拼一块。
- **组件属性表不能在渲染器里手写。** 手写的那份和 `.d.ts` 分叉时,
  D319 的「未知属性」warn 会开始骂**正确**的属性,而插件作者只能选择忽略它 ——
  这条 warn 从此等于不存在。现在由 `tools/sdkgen/gen.mjs` 从定义源生成
  `tools/uibundle/src/props.gen.js`。

## 偏好里带 omitempty 的键**清不掉**(2026-09-21,影响 9 个字段)

`core/config/prefs.go` 的 `prefsTypedKeys` 原来是「marshal 一个零值 `Prefs{}` 再看有哪些键」
建的,而带 `omitempty` 的字段零值时根本不出现在那份 JSON 里 ——
于是它被当成「没接的键」收进 `rest`;之后把它**清空**时,结构体这边省略了,
`rest` 那边又把旧值贴回来。

表现:主题标记成加载失败之后,用户重选同一个主题仍然被当成失败的,**那个主题被永久拉黑**。
反向注入回旧写法后一次点名 9 个字段,其中有 `calendar_unlock_order`(付费门)和 `dev_mode`。

改成按 json tag 反射。判据**不针对某一个字段**:凡是 `omitempty` 的键都要在
`prefsTypedKeys` 里,少一个就会犯同一个病。

## 跨域交叉引用

- [分发通道 GitHub 优于 CF](build-release.md) — 插件包与市场索引走 GitHub,别挪 CF
- [起播不露视频窗](player-mpv.md) — 非 Emby 源起播不露画面窗的两个真因

## 「导入不了」的三种形状,两种是静默的(2026-09-21)

用户拿来两样东西:一个本地 `vod.json`、一个 `.fwd` 订阅链接。两样都不工作,而**只有一样会报错**。

| 拿到的东西 | 原来的表现 | 真正的原因 |
|---|---|---|
| 裸数组 `[{name,type,api}, …]` | 导入「成功」,0 个源 | 外面没有 `{"sites":…}` 那层壳,`cfg.sites` 是 `undefined` |
| 站点不写 `key` | 导入「成功」,0 个源 | `draftsOf` 的 `if (!site.key) continue` —— TVBox 里 key 必填,手写配置基本都没有 |
| `.fwd` 小组件清单 | 导入「成功」,0 个源 | 那根本不是 TVBox 配置,是另一个播放器(ForwardWidget)的插件清单 |

三条里两条是**「成功」加 0 个源** —— 用户看不出是配置不对、地址不对,还是软件坏了。
判据在 `core/datasource/tvbox_e2e_test.go` 的 `TestTVBoxNonStandardConfigShapes`,
夹具在 `core/internal/fakevod`(`/config/bare.json`、`/config/fwd.json`、`/widget/*.js`)。

`.fwd` 值得单说:一份清单里每个小组件是一段自带运行时 API(`WidgetMetadata` / `Widget.http`)的 JS,
看着完全没法接。但**真去读一个**就会发现,这类「VOD 单站点脚本」干的事只有一件 ——
把一个苹果 CMS 采集站的地址写在开头的 `RESOURCE_SITES` 里,剩下全是把 `vod_play_url` 拆成集数,
而那段逻辑我们自己早就有了。所以不用跑它的运行时,抠出那一行就是一个 type 1 站点。
**先读一个样本再判断能不能接**,别看见陌生格式就下结论。

配套的坑:

- **`new URL(u).href` 不是为了转义中文路径**。清单里的地址确实带中文,而实测宿主发请求时
  已经做了百分号编码(夹具 `/widget/一.js` 证明)。留着它是为了让相对路径 / 缺协议的地址当场抛。
  (curl 那边不一样 —— `scripts/fetch-drpy.sh` 里是真的要自己 urlencode,见 `docs/lessons/build-release.md`。)
- **插件读不到本地文件是故意的**(`rt/system.go` 拦 `file://`)。所以「导入一个文件」只能是
  **壳**读文件、填进插件表单的多行字段(D574),不是给插件开一个 fs API。

## 苹果 CMS:顶级分类里一部片都没有(2026-09-21)

用户报「分类不会加载,直接显示加载失败」「有些能点,但点完只显示几个,明明有很多」。
两句话是**同一个**根因。

`class` 是一张平表:

```
{"type_id": 1, "type_pid": 0, "type_name": "电影"}     ← 壳,一部片都没有
{"type_id": 6, "type_pid": 1, "type_name": "动作片"}   ← 片子在这里
```

照着顶级 id 请求 `ac=videolist&t=1` 回的是 `list: [], total: 0`。少数站顶级下面
直接挂了几部,于是 `pagecount` 是 1 —— 「只显示几个,而且划不动」。

**`t` 收逗号分隔的多个 id**,把子分类连起来查就对了。实测用户那 34 个源里能连上的 22 个:

| | 站数 | 顶级分类直接查 | 合并子分类查 |
|---|---|---|---|
| `class` 带 `type_pid` | 13 | 0~12 条,多数没有下一页 | 20 条/页,总数 2440~5306 |
| 不带 `type_pid` | 9 | 0 条 | **做不到** —— API 里没有父子关系 |

那 9 个站只能让用户自己点子分类,所以空分类必须**说人话**(「多半是个父分类,
片子在它下面的子分类里」),而不是一屏空白或者「加载失败」。

同一轮里量出来的另外两件事:

- **12/34 的源已经死了** —— 域名过期被停靠页接管(返回 HTML)、连不上、404、要人机验证。
  停靠页那种原来报的是「站点返回的不是 JSON」加半屏 `<!DOCTYPE html>…`,
  现在说「这个地址返回的是网页,不是采集接口」。**源死了不是我们的 bug,但得让用户看得出来。**
- 首页 `list` 为空的站,退回第一个有内容的分类,别空一屏。

排障手法值得记:**别在代码里猜,拿真源跑**。临时写一个 `zzscratch_real_test.go`
(站点表从环境变量指向的文件读,真实域名不落进仓库),用 `e.subscribe(裸数组)` 把 34 个源
一次灌进去,首页 / 三个分类 / 第二页 / 搜索 / 详情 / 起播全打一遍,一张表就看清楚了。
跑完即删。

## 「云播」线路是个网页,播放器拿到的是 HTML(2026-09-21)

用户:「里面的资源放不出来」。资源站的线路一般有两条:

```
hhm3u8   → https://.../index.m3u8          直接能播
hhyun    → https://<云播站>/play/<一串 id>   ← 这是个网页
```

第二条**没有扩展名**,而 `resolvePlay` 原来的判据是「不像网页(`.html/.php/.shtml`)
就直接播」,于是它被原样交给 mpv —— mpv 拿到一段 HTML。实测 17 个活站里 **7 个**
有这种线路。

那个网页只有 1.3~1.6 KB,是个 DPlayer 壳,m3u8 明文写在里面(`{video:{url:"https:\/\/..."}}`),
一个正则就抠中(7 个里 6 个)。所以顺序改成:

1. 看着就是媒体 → 直接播(不多打一次请求)
2. 其余 http 地址 → **抓页面抠地址**(一次请求,不需要 WebView)
3. 抠不出来 → 配置里的解析器
4. 还不行 → WebView 嗅探

抓页面带 `Range: bytes=0-32767`:万一判断错了、那地址其实是视频本身,也不会把整部片
读进内存。实测 5 个真页面对 Range 的反应是 200 或 206,都给了完整 HTML。

### 顺便澄清一个误判

「放不出来」听起来像解码问题,容易想到「内置 IJK / 网页播放器内核」。不是:
我们带的 libmpv 在播放能力上是 IJK 的超集,HLS / MP4 都不在话下 —— **问题是我们递给它的
不是视频,是网页**。IJK 拿到同一段 HTML 一样播不了。真正需要浏览器内核的是嗅探那一步,
而那一步 Windows 上已经有 WebView2(`WebViewHost.Available`),安卓有 WebShell;
**Linux 桌面没有**(`Available` 在非 Windows 上直接回 false),所以第 2 步「抓页面抠地址」
对 Linux 尤其重要 —— 它是那里唯一的解析手段。

### 排障时踩到的环境坑

那几个站的 .ts 分片挂在**非标准端口**上(`:65`、`:9999`)。本机连不上,
而 DNS 给的是 `198.18.0.x` —— 那是 fake-ip 段,说明流量走了透明代理,
代理只放行了 443。**同一台机器上 443 的地址全通、65/9999 全超时**,
这不是代码问题,是代理规则。遇到「m3u8 能下、分片下不动」先查这个。

## 声明了贡献点,而三个壳一个都没去要(2026-09-21)

用户装了直播插件,报「直播插件根本没用」「甚至不知道哪里打开」。

真相是:**直播插件本身是好的**。拿他给的真实源跑一遍(`m3u` 12 万字节),
6 秒内解析出 13 个分组:央视 23 台、卫视 30、电影 84、地方 245、直播中国 77…
频道列表、分组 chip、VirtualList 全都渲染出来了。

问题在入口。插件在 manifest 里声明了 `contributes.sidebar`,核心层的
`plugin.sidebar` 从阶段 ② 就在 —— **两个壳一个都没调过**。于是:

```
设置 → 插件 → 点直播 → 详情页 → 「页面」卡片 → 直播     ← 唯一的入口,第四层
```

而整条链上**三边全绿**:manifest 合法、`lp check` 通过、核心层返回正确。
这就是本仓点名禁止的那类失败的完全体 —— 没有任何一处报错,只是界面上什么都没有。

配套门禁 `scripts/check-plugin-ui.py` 第 5 条:`core/plugin/anchors.go` 里每条
`plugin.*` 取贡献表的命令,两个壳都必须真去调。

**注释里提一句不算。** 加这一关的时候差点被自己写的文档注释骗过去:桌面端的
`<c>plugin.sidebar</c>` 写在 XML 文档注释里,把真调用删掉照样绿 ——
反向注入才看出来,补了 `strip_comments` 才真的有牙。

顺带一提「加载不了」这类报障的排查顺序:**先证明数据这条路是通的**(拿真源在门禁里跑一遍),
再去看入口。反过来查会在解析器里翻半天,而那里根本没有 bug。

## 贡献点「声明了没人画」不止一处(2026-09-21)

修完侧栏入口(`sidebar`)之后顺手点了一遍:核心层为贡献点开的取表命令,
两个壳到底调了几条。结果 `homeSections`(首页栏目)是同一个病 ——
**核心层连取它的命令都没有**,插件写了也永远画不出来。

这一类失败的共同形状:

```
manifest 合法 ✓   lp check 通过 ✓   贡献点清单里列着它 ✓   界面上什么都没有 ✗
```

四边全绿,没有一处报错。查的时候最容易掉进去的坑是「插件写错了吧」——
其实插件是对的,断的是宿主那一头。

**判断一个贡献点是不是真的通了,只有一条判据:从 manifest 到屏幕,中间每一段都有人接。**
现在由 `check-plugin-ui.py` 第 5 条守着 `anchors.go` 里的取表命令
(`anchors` / `settingsSections` / `playerSurfaces` / `sidebar` / `homeSections` / `homeItems`)。

☠ 这一关自己也栽过一次:**安卓一棵树里装着手机和 TV 两个壳**,把它们当成一个数,
手机调了就算绿 —— 首页栏目(D583)在 TV 上一行都没画,门禁照样全过,我自己加的门禁
第二天就被自己漏过去了。2026-09-21 拆成三端分别算(手机 = 安卓树去掉 `tv/`,TV = 只看 `tv/`)。
拆的时候要注意 `plugin.anchors` / `settingsSections` / `playerSurfaces` 这三条是在
`ui/plugin/` 的**共用组合式**里发的,壳里只看得到函数名 —— 「哪个函数算调了哪条命令」
**从源码里算出来**,别写死对照表,对照表会退化成「新命令忘了登记就默认绿」。

它只能证明「壳去要了」,证明不了「要来的东西画对了」—— 后者靠各自的插件验收测试
(`uhd_plugin_test.go` 的首页流量栏那条就是:列得出来 **且** 挂上去画出真实数字)。

**别再靠记忆维护这张名单。** 2026-09-21 起正本在 `core/plugin/contribPoints`:
35 个 `contributes` 键逐个写清「接了没有、落点是谁、只接了一半时接的是哪一半」。
真接通的只有 11 个;`hooks` / `commands` / `menus` / `pageTakeovers` 接了一半;其余一行都没接。

配套的两件事比名单本身重要:
- 清单上给没接的加「(这一版还不支持)」—— 安装确认原来把 35 个键一视同仁列成中文名,
  等于**替插件许一个不会兑现的愿**:用户点了装,界面上什么都不多,一条错也没有。
- `lp check` 多打一行警告。原来它只查「声明了有没有实现」—— 查的是**插件**那一头,
  插件写得再对,宿主没接照样不会有反应,而它报「声明的贡献点都有实现」。

`check-plugin-ui.py` 第 6 条守着那张表:schema 的键漏一个就红(漏 = 默认被当成已接),
编一个不存在的落点也红。

`pageTakeovers` 值得单独记一笔:插件页的「接管位」标签**能列出来、能选、选完还弹
「重启应用后生效」** —— 而没有任何一个壳会去画插件那一版。这是「摆着不生效的控件」
最完整的一个标本:三层 UI 都写了,只差最后一段。
