# 发 2.0.0 的操作手册

> 「一条命令就能发」的那条命令是 **第 2 步**。前面一步和后面几步是人要做的判断,
> 不该自动化 —— 它们每一条都不可撤回。

## 0. 先决条件

| 事 | 状态 |
|---|---|
| 自有公开域名豁免红线 | ✅ 2026-09-21 裁决豁免(D565)。名单在 `scripts/secrets-allow.txt`,口径写进 `AGENTS.md` §3.1 |
| `lp` 的许可证(D519 原来的卡点) | ✅ 整仓改 AGPL-3.0-or-later(D566)—— 和主仓库同许可证,不再需要版权人另行授权 |
| 清空重建官方插件仓库(D149) | ✅ 2026-09-21 已推(**保留历史**,一次提交换内容)。Pages 自定义域名与 `SITE_URL` / `PUBLIC_REPO_URL` 变量都配好了,站点已上线 |
| 官方插件的 .lpplugin 已发 Release | ✅ 2026-09-21 九个 + drpy 引擎全在 **`plugins` 这一条** Release 里(D572),`release-plugins.sh` 发完照索引逐条回验下载体积 |
| 官方市场地址 | ✅ 站点托管 `/registry/index.json`(D567),`LP_PLUGIN_MARKET_URL` 已配。**没配这个 = 构建出来的插件商店是空的,而且不报错** |
| git 历史里的旧泄漏 | ⏸ 红线原文要求「改写历史或删库重建」,破坏性操作,等你决定。现状见 `docs/lessons/red-line-audit.md` |

## 1. 版本号

改 `VERSION` 一处(唯一权威,见 `docs/VERSIONING.md`):

```
1.1.0  →  2.0.0    ← 2026-09-21 已改
```

☠ **不要在没想好之前改它**:`VERSION` 一进 main,CI 就会自动出预发布。
2.0.0 无论 build 号多小都压得过线上的 1.1.0-buildN,所以单调性没问题。

大版本还要写 `docs/plugin-system/RELEASE-<VERSION>.md`(**加了什么 / 删了什么 / 挪去哪了**)——
`release-notes.sh` 把它拼在提交清单上面,文件不在就直接红(D570)。

## 2. 那条命令

```bash
bash scripts/release.sh
```

它按顺序做:门禁 → 出三端包 → 生成更新说明 → 打 tag。**它不推。**
推是最后一步,你自己来(见第 5 步)。

## 3. 发 SDK 与 CLI 到 pnpm

```bash
bash scripts/publish-sdk.sh          # 演练:打包、列出会发出去的每一个文件,不发
bash scripts/publish-sdk.sh --yes    # 确认清单没问题之后再发
```

☠ 默认只演练。npm 发布不可撤回(unpublish 有 72 小时窗口且会留痕),
而这个脚本很可能被人手滑跑到。

## 4. 官方插件仓库

仓库本身已经重建并上线。要更新内容(改了官方插件、SDK 类型、站点)时:

```bash
bash scripts/check-secrets.sh                                      # 先扫红线
for d in plugins/*/; do (cd core && go run ./cmd/lp pack "../$d"   -o "../build/lpplugin/$(basename $d).lpplugin"); done            # 打包(索引要真实体积)
PLUGIN_REPO_SLUG=... MAIN_REPO_URL=... node scripts/gen-registry.mjs
PLUGIN_REPO_SLUG=... bash scripts/push-plugin-repo.sh              # 演练
PLUGIN_REPO_SLUG=... bash scripts/push-plugin-repo.sh --yes        # 真推
```

**把包发上去**(索引里的地址指向官方副本,发完才不是 404):

```bash
PLUGIN_REPO_SLUG=... bash scripts/release-plugins.sh        # 演练:列标签与资产名
PLUGIN_REPO_SLUG=... bash scripts/release-plugins.sh --yes  # 真发
```

资产名必须和 `gen-registry.mjs` 的 `assetUrl()` 完全一致 —— 脚本会逐个对,对不上就不发。
全部资产进 `plugins` 这一条 Release(D572),不要再按插件开 tag。

☠ **换索引地址时:先推索引,后删旧 Release**。反过来的话中间那段时间线上索引
指着已经删掉的资产,用户点下载全是 404。

## 5. 推

```bash
git push origin main       # 触发自动预发布
git push origin v2.0.0     # 打了 tag 才发正式版
```

## 5.5 推完先验一件事

安装包里**不带任何插件**,市场地址是拿到插件的唯一一条路。它是编译期注入的
(`LP_PLUGIN_MARKET_URL` → `core/cmd/sealsecrets`),漏注入时**不报错**,
表现是「插件商店是空的」。

验法:装上预发布,打开「设置 → 插件 → 市场」——九条都在 = 注入成功。

## 6. 发完之后

- 在 Release 页面把 `docs/plugin-system/RELEASE-2.0.0.md` 的正文贴在提交清单**上面**。
- 应用内的「更新说明」读的是 Release body,所以那两段的顺序就是用户看到的顺序。
- 官方仓库的 Pages 站会在合并时自动重建(定时任务每 6 小时也会重建一次,D240)。
