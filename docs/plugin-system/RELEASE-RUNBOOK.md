# 发 2.0.0 的操作手册

> 「一条命令就能发」的那条命令是 **第 2 步**。前面一步和后面几步是人要做的判断,
> 不该自动化 —— 它们每一条都不可撤回。

## 0. 先决条件(**这些还没做,要你点头**)

| 事 | 为什么要你决定 | 在哪 |
|---|---|---|
| **清空重建官方插件仓库**(D149) | 破坏性:仓库里现有的东西会没。清空时**不要动 Pages 设置**(D395) | 内容已备在 `plugin-repo/`,推法见第 4 步 |
| **自有公开域名是否豁免红线** | 红线原文写的是「任何域名」,而 SPEC 15.6 明写「仓库文件与提交里不出现域名」。现在站点自定义域名还在 3 个已跟踪文件里 | `docs/lessons/red-line-audit.md`「等裁决的那一件」 |
| **git 历史里的旧泄漏怎么处理** | 红线原文要求「改写历史或删库重建」,两条都是破坏性操作 | 同上 |
| **`lp` 以 MIT 发布的授权**(D519) | `lp` 复用主仓库宿主代码;主仓库相关代码若有他人贡献,要先确认 | `plugin-repo/README.md` |

## 1. 版本号

改 `VERSION` 一处(唯一权威,见 `docs/VERSIONING.md`):

```
1.1.0  →  2.0.0
```

☠ **不要在没想好之前改它**:`VERSION` 一进 main,CI 就会自动出预发布。
2.0.0 无论 build 号多小都压得过线上的 1.1.0-buildN,所以单调性没问题。

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

```bash
bash scripts/sync-plugin-repo.sh   # 把 .d.ts / schema / 官方插件 / 示例同步进 plugin-repo/
bash scripts/check-secrets.sh      # 推之前扫一遍
```

`plugin-repo/` 是**准备好的内容**,脚本不碰远端。清空重建那一步要你点头(见第 0 步)。
站点的自定义域名存在 GitHub 的 Pages 设置里,仓库文件里不写。

## 5. 推

```bash
git push origin main       # 触发自动预发布
git push origin v2.0.0     # 打了 tag 才发正式版
```

## 6. 发完之后

- 在 Release 页面把 `docs/plugin-system/RELEASE-2.0.0.md` 的正文贴在提交清单**上面**。
- 应用内的「更新说明」读的是 Release body,所以那两段的顺序就是用户看到的顺序。
- 官方仓库的 Pages 站会在合并时自动重建(定时任务每 6 小时也会重建一次,D240)。
