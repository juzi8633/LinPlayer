---
layout: ../layouts/Md.astro
lang: zh
altPath: /download
title: 下载 LinPlayer
description: 插件要装在 LinPlayer 里。Windows / Linux / Android 手机 / Android TV 四个端。
---

插件装在 LinPlayer 里,所以先得有 LinPlayer。四个端:**Windows**、**Linux**、
**Android 手机**、**Android TV**。

安装包在主仓库的 Releases 里发。仓库地址由构建时注入,页脚那条「主仓库」就是。

装好之后,插件页上的**一键安装**会通过 `linplayer://` 深链直接把包交给应用,
应用会先把插件要的权限摆给你看,你点头才装。手机上用详情页的二维码扫一下更快。

浏览器第一次遇到 `linplayer://` 会问你是否允许打开——允许就行。
按了没反应,基本就是没装应用,页面 2 秒后会把「下载 LinPlayer / 手动下载包」摆出来。
