---
title: What the plugin system is, and what it can do
description: An overview — which places a plugin can take over, where it runs, and what it never gets.
order: 10
---

LinPlayer plugins are not the "reskin it" kind. What a plugin gets is a set of **takeover
points**: the app hands over specific places in the UI and in the data flow, a plugin fills
them, and the result looks and behaves like a built-in feature.

## What can be taken over

- **Data sources** (`dataSource`): define your own "server type" with a few fields; browsing, search and playback then run through your implementation. That is exactly how the TVBox plugin works.
- **Pages and sidebar** (`pages` / `sidebar`): add a whole page and hang it in the sidebar. Live TV, the rule editor and the developer tools are all standalone pages.
- **Player overlays and side panels** (`playerOverlays` / `playerPanels`): draw over the video, or open a panel beside it. Live subtitle translation uses an overlay.
- **Anchors** (`anchors`): insert a block at a named position inside an existing page — for example under the ratings on a detail page.
- **Settings** (`settingsSections` / `settings`): add a section to the settings page, or just declare a few switches and let the host render them.
- **Themes** (`theme`): replace design tokens and component state styles. Structure and behaviour stay put — a theme package cannot change interaction.
- **Commands and menus** (`commands` / `menus`): context menus, the command palette, and — once a command declares `external` — an entry point reachable over a `linplayer://cmd` deep link.

## Where it runs

Plugins are JavaScript running in the app's plugin runtime, talking to the host through the SDK.
You do not draw pixels: you describe controls and the host renders them with native widgets, so a
plugin page looks right on Windows, Linux, Android phone and Android TV, with focus and remote
control working throughout.

## What it never gets

- **Account credentials.** Accounts such as Trakt or Bangumi are linked in the host; a plugin borrows the session and never touches the token.
- **Undeclared capabilities.** Local network access (`lan`) and required external components (`requires.components`) must be declared in the manifest and are shown to the user at install time.
- **Another plugin's storage.** Each one is on its own.

## Where to start

Install one of the official plugins from the wall, then read the [publishing guide](/en/guide).
Every extension point has a minimal example — a few dozen lines — under `examples/` in the repository.
