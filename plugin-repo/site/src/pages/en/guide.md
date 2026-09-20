---
layout: ../../layouts/Md.astro
lang: en
nav: guide
altPath: /guide
title: Publishing a plugin
description: How to get a plugin into the official LinPlayer marketplace — flow, checks and terms.
---

## The flow

1. Develop in your own repository, run `lp pack` to build a `.lpplugin`, publish a GitHub Release.
2. **First listing**: fork the official repository, generate your entry with `lp submit`, add it to `registry/index.json` and open a PR. A maintainer reviews it by hand.
3. **Updates**: open a PR editing your own entry. It merges automatically once every check below passes — you only touched your own id, the committer is that author, the package downloads, the manifest is valid and the version went up. Anything short of that goes to a maintainer.
4. On merge, CI copies the package into the official repository's Releases and points the index at that copy. Deleting your repo or swapping the artifact cannot break an already listed version, and download counts live in one place.

There is no delisting flag and no remote kill switch.

## What CI checks

- The manifest validates against the JSON Schema; the version is semver and greater than the last one.
- The author name equals the PR committer's GitHub username; the `linplayer/` prefix is maintainers only.
- The package actually installs: CI installs it with `lp` and runs `activate` without errors.
- Extraction is safe — no path traversal, no zip bombs.
- The `repository` field points at a publicly reachable source repository.
- Every contribution declared in the manifest has an implementation.

Run the same set locally with `lp check`.

## Terms

Plugins are developed and maintained by their authors. **The project does not vouch for plugin content.** We ask that you:

- do not place ads or funnel traffic inside the UI;
- do not upload a user's data without them knowing;
- publish your source;
- do not ship links to unlicensed content — the TVBox plugin itself carries no sources, users subscribe to their own.

Maintainers reserve the right not to list a plugin.
