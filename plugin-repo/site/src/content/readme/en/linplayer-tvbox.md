---
title: TVBox compatibility
---

Paste a TVBox configuration URL and the sites inside it become data sources in LinPlayer —
browsing, search and playback all go through the normal entry points and look like any other server.

## Supported configuration shapes

- Plain JSON configurations, and encrypted ones (append `;pk;key` to the URL);
- Multi-repository URLs: pick a repository, then pick sources;
- A single drpy rule, pasted as text or as a URL.

The first time you add one you are asked which sources to enable — a configuration often carries
dozens, and turning them all on only makes search slower. Got it wrong? "Re-select sources" in the
server menu.

## Sniffing

Sites that need sniffing go through the built-in sniffer, 15 seconds by default. A timeout does not
fail outright: it opens a **visible** web page so you can press play or clear a captcha yourself.
That path is deliberate — it is what keeps sites working when automation cannot get through.

## Ad filtering

The m3u8 ad rules shipped inside the configuration are on by default; turn them off in plugin settings.

## Boundaries

The plugin ships **no sources at all**. It is empty after install; you subscribe to your own content.
It needs the `jar-runtime` and `python` components, which the app offers to download on first use.
