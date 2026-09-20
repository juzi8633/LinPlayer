---
layout: ../../layouts/Md.astro
lang: en
altPath: /download
title: Get LinPlayer
description: Plugins run inside LinPlayer. Windows, Linux, Android phone and Android TV.
---

Plugins run inside LinPlayer, so you need the app first. Four targets: **Windows**,
**Linux**, **Android phone** and **Android TV**.

Builds are published in the main repository's Releases. The repository link is injected at
build time — it is the "Repository" link in the footer.

Once installed, the **Install** button on any plugin page hands the package to the app over a
`linplayer://` deep link. The app shows you what the plugin is asking for before anything is
installed. On a phone, scanning the QR code on the detail page is quicker.

The first time your browser sees a `linplayer://` link it will ask whether to open it — say yes.
If nothing happens, you most likely do not have the app: after two seconds the page offers
"Get LinPlayer" and a direct package download.
