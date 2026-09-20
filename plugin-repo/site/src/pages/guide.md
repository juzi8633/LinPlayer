---
layout: ../layouts/Md.astro
lang: zh
nav: guide
altPath: /guide
title: 上架指南
description: 把插件发到 LinPlayer 官方市场:流程、校验、以及上架须知。
---

## 流程

1. 在自己的仓库里开发,`lp pack` 打出 `.lpplugin`,发一个 GitHub Release。
2. **首次上架**:fork 官方仓库,用 `lp submit` 生成条目片段写进 `registry/index.json`,提 PR。维护者人工看一遍再合。
3. **后续更新**:直接提 PR 改自己那条。下面的校验全过就自动合并——只改自己 id 的条目、提交者是该作者、包能下载、manifest 合法、版本递增。任一条不满足,留给维护者。
4. 合并时 CI 把包复制进官方仓库的 Releases,索引里的地址指向官方副本。你删仓库或换包都不影响已上架的版本,下载量也只在一处统计。

不做下架标记,也不做远程禁用。

## CI 会检查什么

- manifest 过 JSON Schema;版本是 semver 且比上一版大。
- 作者名等于 PR 提交者的 GitHub 用户名;`linplayer/` 前缀只有维护者能用。
- 包真的能装:CI 里用 `lp` 装一遍并执行 `activate`,不许报错。
- 解压安全:路径穿越、压缩炸弹。
- `repository` 指向的源码仓库公开可访问。
- manifest 声明的贡献点在代码里有实现。

本地跑同一套:`lp check`。

## 上架须知

插件由作者独立开发维护,**官方不对插件内容负责**。希望你:

- 不在界面里投放广告或引流;
- 不在用户不知情时上传他的数据;
- 公开源码;
- 不内置指向未授权内容的站点——TVBox 插件本身不带任何源,内容由用户自行订阅。

维护者保留不予收录的权利。
