# LinPlayer 官方插件仓库(**待发布的内容,还没推上去**)

这个目录是**按 SPEC 15.1 准备好的官方插件仓库内容**,还没有推到任何远端。

> 官方仓库 = 用户 GitHub 上现有的插件仓库,**清空后整仓重建**(D149)。
> 清空是破坏性操作 —— 动手前要再向项目负责人确认一次,清空时不动仓库的 Pages 设置(D395)。
> 在那次确认之前,这份内容只存在于主仓库的 `plugin-repo/` 下。

## 里面有什么

```
packages/sdk/      @linplayer/plugin-sdk —— 类型 + JSON Schema(.d.ts 随应用发版同步进来,D410)
packages/cli/      @linplayer/cli —— 按平台装 lp 二进制(D308)
registry/          官方市场索引 index.json(D28)
site/              Astro 站:插件墙 + 开发者文档(D405)
docs/              开发者文档源,中英双语(D150 D399)
examples/          每个扩展点一个几十行的最小示例(D151)
plugins/           官方插件源码 —— **推的时候从主仓库 plugins/ 同步过来**,这里不放副本
```

`plugins/` 故意是空的:主仓库的 `plugins/` 才是正本,两处各存一份迟早对不上。
同步由 `scripts/sync-plugin-repo.sh` 做。

## 许可证

**整仓 AGPL-3.0-or-later**,和 LinPlayer 主体一致(D566,2026-09-21 定,推翻 D519 的分法)。

这也解掉了 D519 自己标出来的那个卡点:`lp` 复用主仓库(AGPL)的宿主代码,
按 MIT 发布本来需要版权人另行授权、还要确认他人贡献 —— 同为 AGPL 之后这一步不再需要。

☠ 要在 `@linplayer/plugin-sdk` 的 README 里写清一句:**这不要求第三方插件也用 AGPL**。
  那个包只提供类型(`.d.ts` 构建时被擦掉,运行时由宿主注入),**不进插件产物**。
  不写的话作者会以为装了它自己就得开源,直接劝退。

## 地址

站点用的自定义域名**存在仓库的 Pages 设置里**,仓库文件与提交里不出现域名(SPEC 15.6)。
