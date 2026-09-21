// 本文件由 scripts/gen-bindings.py 从 docs/go-migration/COMMANDS.md 生成。
// 不要手改 —— 改了会在下一次生成时被覆盖,而且四方比对会红。

package xyz.linplayer.core

import kotlinx.serialization.json.JsonElement

/**
 * 核心层命令的类型化包装。**生成的,不要手写。**
 *
 * 参数与返回暂时是弱类型:COMMANDS.md 的参数列现在装的是现有 Rust 签名,
 * 不是新契约的 JSON 形状。形状回填之后这里会换成 data class。
 *
 * ⚠️ `lp_next_event` 不在这里暴露:有且仅有一个消费者线程能调它。
 * 两个线程同时调不会崩,而是事件被**随机分给两个线程** ——
 * 表现为「有时候收得到有时候收不到」。事件请走 `CoreClient` 的 Flow。
 */
interface LinPlayerCommands {
    /** 发一条命令,挂起到它的 result 事件回来。 */
    suspend fun call(command: String, args: Map<String, Any?>? = null): JsonElement
}

object LinPlayerAbi {
    /** ABI 版本。真值在 core/ffi/abi.go,这里是生成出来的副本。 */
    const val VERSION = 1
}

/** 全部命令名。四方比对(COMMANDS.md ↔ Go 注册表 ↔ 三端绑定)用这份。 */
object LinPlayerCommandNames {
    @JvmField
    val ALL: List<String> = listOf(
        "emby.aggregateOverview",
        "emby.aggregateSearch",
        "emby.rankingCategories",
        "emby.rankingFetch",
        "emby.aggregateVersions",
        "emby.blockedList",
        "emby.counts",
        "emby.currentSession",
        "emby.getFilters",
        "emby.isAdmin",
        "emby.itemDetail",
        "emby.itemMedia",
        "emby.listCollections",
        "emby.collectionItems",
        "emby.listFavorites",
        "emby.listItems",
        "emby.listItemsPage",
        "emby.listLatest",
        "emby.listNextUp",
        "emby.listRandom",
        "emby.listResume",
        "emby.login",
        "emby.logout",
        "emby.personDetail",
        "emby.personItems",
        "emby.permissions",
        "emby.refreshItem",
        "emby.relogin",
        "emby.reportProgress",
        "emby.scanLibraries",
        "emby.search",
        "emby.seasonEpisodes",
        "emby.seriesSeasons",
        "emby.providers",
        "emby.setBlocked",
        "emby.setFavorite",
        "emby.setPlayed",
        "emby.hideResume",
        "emby.similarItems",
        "emby.views",
        "emby.watchHistoryClear",
        "emby.watchHistoryDelete",
        "emby.watchHistoryList",
        "emby.watchHistoryRestoreCandidate",
        "emby.watchHistoryScanRestore",
        "account.batchAddServers",
        "account.batchParse",
        "account.clearAccountIcon",
        "account.getCrossServerResume",
        "account.icon",
        "account.listAccounts",
        "account.parseDeepLink",
        "account.probeAccounts",
        "account.probeLine",
        "account.probeLines",
        "account.removeAccount",
        "account.reorderAccounts",
        "account.setAccountIconFile",
        "account.setActiveLine",
        "account.setActiveServer",
        "account.setCrossServerResume",
        "account.setLines",
        "account.startupDeepLink",
        "account.syncLines",
        "account.testConnection",
        "account.updateAccount",
        "player.addSubtitle",
        "player.chapterInfo",
        "player.getMpvConf",
        "player.getPlaybackPrefs",
        "player.getScreenshotDir",
        "player.getSkipRange",
        "player.mpvCommand",
        "player.mpvGet",
        "player.mpvSet",
        "player.opts",
        "player.play",
        "player.playExternal",
        "player.playLocal",
        "player.playUrl",
        "player.screenshot",
        "player.seek",
        "player.setAspectRatio",
        "player.setAudioDelay",
        "player.setHwdec",
        "player.setMpvConf",
        "player.setMute",
        "player.setPause",
        "player.danmakuSet",
        "player.setDanmakuEnabled",
        "player.setDanmakuStyle",
        "player.getDanmakuStyle",
        "player.danmakuLayout",
        "player.danmakuHeatmap",
        "player.setPlaybackPrefs",
        "player.setScreenshotDir",
        "player.setSecondarySub",
        "player.setSecondarySubOpts",
        "player.setShaderLevel",
        "player.setSkipRange",
        "player.setSpeed",
        "player.setSubDelay",
        "player.setSubStyle",
        "player.getSubStyle",
        "player.interpLevels",
        "player.setInterpLevel",
        "player.interpInstall",
        "player.setTrack",
        "player.setTrackRegexes",
        "player.setVolume",
        "player.shaderLevels",
        "player.status",
        "player.stopPlayback",
        "player.takePending",
        "player.thumbnail",
        "player.tracks",
        "player.validateTrackRegex",
        "player.windowClose",
        "player.windowOpen",
        "source.currentSource",
        "source.listDir",
        "source.login",
        "source.formSchema",
        "source.play",
        "source.search",
        "source.watchdog",
        "source.caps",
        "source.home",
        "source.category",
        "source.searchItems",
        "source.person",
        "source.detail",
        "source.playItem",
        "source.continueWatching",
        "source.createSources",
        "source.addSources",
        "source.setAggregate",
        "source.setHost",
        "source.removeGroup",
        "source.serverMenus",
        "source.runCommand",
        "source.aggregateSearch",
        "source.switchCandidates",
        "source.checkAll",
        "source.setFavorite",
        "source.isFavorite",
        "source.favorites",
        "source.history",
        "source.linkSwitch",
        "danmaku.autoLoad",
        "danmaku.cacheClear",
        "danmaku.cacheSize",
        "danmaku.episodes",
        "danmaku.filter",
        "danmaku.getDanmakuConfig",
        "danmaku.getOfficialDanmaku",
        "danmaku.importBlocklist",
        "danmaku.load",
        "danmaku.loadLocal",
        "danmaku.match",
        "danmaku.lastMatch",
        "danmaku.minAutoScore",
        "danmaku.search",
        "danmaku.setDanmakuConfig",
        "danmaku.getBlockwords",
        "danmaku.setBlockwords",
        "download.andApplyUpdate",
        "download.clearCompleted",
        "download.enqueue",
        "download.enqueueSeason",
        "download.list",
        "download.pause",
        "download.remove",
        "download.resume",
        "download.setThreads",
        "prefs.applyPrefs",
        "prefs.cfProxyDisable",
        "prefs.cfProxyEnable",
        "prefs.cfProxyStatus",
        "prefs.cfSpeedTest",
        "prefs.configExportQr",
        "prefs.configImportQr",
        "prefs.backupExport",
        "prefs.backupImport",
        "prefs.backupPreview",
        "prefs.getHomeSettings",
        "prefs.getPrefetchSettings",
        "prefs.getPrefs",
        "prefs.getPreloadSettings",
        "prefs.getProxy",
        "prefs.getUpdateSettings",
        "prefs.getWritebackSettings",
        "prefs.iconLibrary",
        "prefs.setIconSources",
        "prefs.preloadCancel",
        "prefs.preloadItem",
        "prefs.setDetailBlur",
        "prefs.setHomeSettings",
        "prefs.setPrefetchSettings",
        "prefs.setPrefs",
        "prefs.pushSearch",
        "prefs.setPreloadSettings",
        "prefs.setProxy",
        "prefs.setUpdateSettings",
        "prefs.setWritebackSettings",
        "system.cacheSize",
        "system.cancelUpdate",
        "system.capabilities",
        "system.checkUpdate",
        "system.clearCache",
        "system.dataPaths",
        "system.downloadUpdate",
        "system.exportDiagnostics",
        "system.installUpdate",
        "system.sendReport",
        "system.openDataDir",
        "system.shortcutStatus",
        "system.makeShortcut",
        "system.pickDirectory",
        "system.pickFile",
        "system.pickLocalFolder",
        "system.ping",
        "system.updateProgress",
        "system.afdianSponsorUrl",
        "system.afdianVerify",
        "companion.start",
        "companion.status",
        "companion.setEnabled",
        "companion.setNowPlaying",
        "sync.traktAccount",
        "sync.traktDeviceCode",
        "sync.traktPoll",
        "sync.traktLogout",
        "sync.traktCalendar",
        "sync.bangumiAccount",
        "sync.bangumiAuthorizeUrl",
        "sync.bangumiExchange",
        "sync.bangumiLoginToken",
        "sync.bangumiLogout",
        "sync.bangumiSummary",
        "sync.bangumiCalendar",
        "sync.calendarLibrary",
        "sync.calendarDue",
        "plugin.list",
        "plugin.pendingRestart",
        "plugin.inspect",
        "plugin.installFile",
        "plugin.installFromRepo",
        "plugin.uninstall",
        "plugin.cancelUninstall",
        "plugin.setEnabled",
        "plugin.disableAll",
        "plugin.restoreAll",
        "plugin.rollback",
        "plugin.setLocked",
        "plugin.skipVersion",
        "plugin.detail",
        "plugin.setSetting",
        "plugin.clearData",
        "plugin.errorDetail",
        "plugin.market",
        "plugin.repos",
        "plugin.addRepo",
        "plugin.removeRepo",
        "plugin.setGithubPrefix",
        "plugin.setAutoUpdate",
        "plugin.updates",
        "plugin.updateAll",
        "plugin.takeovers",
        "plugin.setTakeover",
        "plugin.devLoad",
        "plugin.devUnload",
        "plugin.devList",
        "plugin.setCapabilities",
        "plugin.appEvent",
        "plugin.anchors",
        "plugin.settingsSections",
        "sync.traktRequest",
        "sync.bangumiRequest",
        "player.getSubtitleText",
        "plugin.setEnv",
        "plugin.shellResult",
        "plugin.themes",
        "plugin.activeTheme",
        "plugin.setActiveTheme",
        "plugin.themeFailed",
        "plugin.wallpapers",
        "plugin.activeWallpaper",
        "plugin.initialWallpaper",
        "plugin.setActiveWallpaper",
        "plugin.playerSurfaces",
        "plugin.sidebar",
        "plugin.homeSections",
        "plugin.homeItems",
        "plugin.backRequest",
        "plugin.playerKey",
        "plugin.setCookies",
        "plugin.ui.mount",
        "plugin.ui.unmount",
        "plugin.ui.event",
        "plugin.ui.viewport",
    )
}

// ---- Emby 浏览与详情 · emby.* (45 条) ----
suspend fun LinPlayerCommands.embyAggregateOverview(args: Map<String, Any?>? = null): JsonElement =
    call("emby.aggregateOverview", args)
suspend fun LinPlayerCommands.embyAggregateSearch(args: Map<String, Any?>? = null): JsonElement =
    call("emby.aggregateSearch", args)
suspend fun LinPlayerCommands.embyRankingCategories(args: Map<String, Any?>? = null): JsonElement =
    call("emby.rankingCategories", args)
suspend fun LinPlayerCommands.embyRankingFetch(args: Map<String, Any?>? = null): JsonElement =
    call("emby.rankingFetch", args)
suspend fun LinPlayerCommands.embyAggregateVersions(args: Map<String, Any?>? = null): JsonElement =
    call("emby.aggregateVersions", args)
suspend fun LinPlayerCommands.embyBlockedList(args: Map<String, Any?>? = null): JsonElement =
    call("emby.blockedList", args)
suspend fun LinPlayerCommands.embyCounts(args: Map<String, Any?>? = null): JsonElement =
    call("emby.counts", args)
suspend fun LinPlayerCommands.embyCurrentSession(args: Map<String, Any?>? = null): JsonElement =
    call("emby.currentSession", args)
suspend fun LinPlayerCommands.embyGetFilters(args: Map<String, Any?>? = null): JsonElement =
    call("emby.getFilters", args)
suspend fun LinPlayerCommands.embyIsAdmin(args: Map<String, Any?>? = null): JsonElement =
    call("emby.isAdmin", args)
suspend fun LinPlayerCommands.embyItemDetail(args: Map<String, Any?>? = null): JsonElement =
    call("emby.itemDetail", args)
suspend fun LinPlayerCommands.embyItemMedia(args: Map<String, Any?>? = null): JsonElement =
    call("emby.itemMedia", args)
suspend fun LinPlayerCommands.embyListCollections(args: Map<String, Any?>? = null): JsonElement =
    call("emby.listCollections", args)
suspend fun LinPlayerCommands.embyCollectionItems(args: Map<String, Any?>? = null): JsonElement =
    call("emby.collectionItems", args)
suspend fun LinPlayerCommands.embyListFavorites(args: Map<String, Any?>? = null): JsonElement =
    call("emby.listFavorites", args)
suspend fun LinPlayerCommands.embyListItems(args: Map<String, Any?>? = null): JsonElement =
    call("emby.listItems", args)
suspend fun LinPlayerCommands.embyListItemsPage(args: Map<String, Any?>? = null): JsonElement =
    call("emby.listItemsPage", args)
suspend fun LinPlayerCommands.embyListLatest(args: Map<String, Any?>? = null): JsonElement =
    call("emby.listLatest", args)
suspend fun LinPlayerCommands.embyListNextUp(args: Map<String, Any?>? = null): JsonElement =
    call("emby.listNextUp", args)
suspend fun LinPlayerCommands.embyListRandom(args: Map<String, Any?>? = null): JsonElement =
    call("emby.listRandom", args)
suspend fun LinPlayerCommands.embyListResume(args: Map<String, Any?>? = null): JsonElement =
    call("emby.listResume", args)
suspend fun LinPlayerCommands.embyLogin(args: Map<String, Any?>? = null): JsonElement =
    call("emby.login", args)
suspend fun LinPlayerCommands.embyLogout(args: Map<String, Any?>? = null): JsonElement =
    call("emby.logout", args)
suspend fun LinPlayerCommands.embyPersonDetail(args: Map<String, Any?>? = null): JsonElement =
    call("emby.personDetail", args)
suspend fun LinPlayerCommands.embyPersonItems(args: Map<String, Any?>? = null): JsonElement =
    call("emby.personItems", args)
suspend fun LinPlayerCommands.embyPermissions(args: Map<String, Any?>? = null): JsonElement =
    call("emby.permissions", args)
suspend fun LinPlayerCommands.embyRefreshItem(args: Map<String, Any?>? = null): JsonElement =
    call("emby.refreshItem", args)
suspend fun LinPlayerCommands.embyRelogin(args: Map<String, Any?>? = null): JsonElement =
    call("emby.relogin", args)
suspend fun LinPlayerCommands.embyReportProgress(args: Map<String, Any?>? = null): JsonElement =
    call("emby.reportProgress", args)
suspend fun LinPlayerCommands.embyScanLibraries(args: Map<String, Any?>? = null): JsonElement =
    call("emby.scanLibraries", args)
suspend fun LinPlayerCommands.embySearch(args: Map<String, Any?>? = null): JsonElement =
    call("emby.search", args)
suspend fun LinPlayerCommands.embySeasonEpisodes(args: Map<String, Any?>? = null): JsonElement =
    call("emby.seasonEpisodes", args)
suspend fun LinPlayerCommands.embySeriesSeasons(args: Map<String, Any?>? = null): JsonElement =
    call("emby.seriesSeasons", args)
suspend fun LinPlayerCommands.embyProviders(args: Map<String, Any?>? = null): JsonElement =
    call("emby.providers", args)
suspend fun LinPlayerCommands.embySetBlocked(args: Map<String, Any?>? = null): JsonElement =
    call("emby.setBlocked", args)
suspend fun LinPlayerCommands.embySetFavorite(args: Map<String, Any?>? = null): JsonElement =
    call("emby.setFavorite", args)
suspend fun LinPlayerCommands.embySetPlayed(args: Map<String, Any?>? = null): JsonElement =
    call("emby.setPlayed", args)
suspend fun LinPlayerCommands.embyHideResume(args: Map<String, Any?>? = null): JsonElement =
    call("emby.hideResume", args)
suspend fun LinPlayerCommands.embySimilarItems(args: Map<String, Any?>? = null): JsonElement =
    call("emby.similarItems", args)
suspend fun LinPlayerCommands.embyViews(args: Map<String, Any?>? = null): JsonElement =
    call("emby.views", args)
suspend fun LinPlayerCommands.embyWatchHistoryClear(args: Map<String, Any?>? = null): JsonElement =
    call("emby.watchHistoryClear", args)
suspend fun LinPlayerCommands.embyWatchHistoryDelete(args: Map<String, Any?>? = null): JsonElement =
    call("emby.watchHistoryDelete", args)
suspend fun LinPlayerCommands.embyWatchHistoryList(args: Map<String, Any?>? = null): JsonElement =
    call("emby.watchHistoryList", args)
suspend fun LinPlayerCommands.embyWatchHistoryRestoreCandidate(args: Map<String, Any?>? = null): JsonElement =
    call("emby.watchHistoryRestoreCandidate", args)
suspend fun LinPlayerCommands.embyWatchHistoryScanRestore(args: Map<String, Any?>? = null): JsonElement =
    call("emby.watchHistoryScanRestore", args)

// ---- 账号与线路 · account.* (21 条) ----
suspend fun LinPlayerCommands.accountBatchAddServers(args: Map<String, Any?>? = null): JsonElement =
    call("account.batchAddServers", args)
suspend fun LinPlayerCommands.accountBatchParse(args: Map<String, Any?>? = null): JsonElement =
    call("account.batchParse", args)
suspend fun LinPlayerCommands.accountClearAccountIcon(args: Map<String, Any?>? = null): JsonElement =
    call("account.clearAccountIcon", args)
suspend fun LinPlayerCommands.accountGetCrossServerResume(args: Map<String, Any?>? = null): JsonElement =
    call("account.getCrossServerResume", args)
suspend fun LinPlayerCommands.accountIcon(args: Map<String, Any?>? = null): JsonElement =
    call("account.icon", args)
suspend fun LinPlayerCommands.accountListAccounts(args: Map<String, Any?>? = null): JsonElement =
    call("account.listAccounts", args)
suspend fun LinPlayerCommands.accountParseDeepLink(args: Map<String, Any?>? = null): JsonElement =
    call("account.parseDeepLink", args)
suspend fun LinPlayerCommands.accountProbeAccounts(args: Map<String, Any?>? = null): JsonElement =
    call("account.probeAccounts", args)
suspend fun LinPlayerCommands.accountProbeLine(args: Map<String, Any?>? = null): JsonElement =
    call("account.probeLine", args)
suspend fun LinPlayerCommands.accountProbeLines(args: Map<String, Any?>? = null): JsonElement =
    call("account.probeLines", args)
suspend fun LinPlayerCommands.accountRemoveAccount(args: Map<String, Any?>? = null): JsonElement =
    call("account.removeAccount", args)
suspend fun LinPlayerCommands.accountReorderAccounts(args: Map<String, Any?>? = null): JsonElement =
    call("account.reorderAccounts", args)
suspend fun LinPlayerCommands.accountSetAccountIconFile(args: Map<String, Any?>? = null): JsonElement =
    call("account.setAccountIconFile", args)
suspend fun LinPlayerCommands.accountSetActiveLine(args: Map<String, Any?>? = null): JsonElement =
    call("account.setActiveLine", args)
suspend fun LinPlayerCommands.accountSetActiveServer(args: Map<String, Any?>? = null): JsonElement =
    call("account.setActiveServer", args)
suspend fun LinPlayerCommands.accountSetCrossServerResume(args: Map<String, Any?>? = null): JsonElement =
    call("account.setCrossServerResume", args)
suspend fun LinPlayerCommands.accountSetLines(args: Map<String, Any?>? = null): JsonElement =
    call("account.setLines", args)
suspend fun LinPlayerCommands.accountStartupDeepLink(args: Map<String, Any?>? = null): JsonElement =
    call("account.startupDeepLink", args)
suspend fun LinPlayerCommands.accountSyncLines(args: Map<String, Any?>? = null): JsonElement =
    call("account.syncLines", args)
suspend fun LinPlayerCommands.accountTestConnection(args: Map<String, Any?>? = null): JsonElement =
    call("account.testConnection", args)
suspend fun LinPlayerCommands.accountUpdateAccount(args: Map<String, Any?>? = null): JsonElement =
    call("account.updateAccount", args)

// ---- 播放器 · player.* (53 条) ----
suspend fun LinPlayerCommands.playerAddSubtitle(args: Map<String, Any?>? = null): JsonElement =
    call("player.addSubtitle", args)
suspend fun LinPlayerCommands.playerChapterInfo(args: Map<String, Any?>? = null): JsonElement =
    call("player.chapterInfo", args)
suspend fun LinPlayerCommands.playerGetMpvConf(args: Map<String, Any?>? = null): JsonElement =
    call("player.getMpvConf", args)
suspend fun LinPlayerCommands.playerGetPlaybackPrefs(args: Map<String, Any?>? = null): JsonElement =
    call("player.getPlaybackPrefs", args)
suspend fun LinPlayerCommands.playerGetScreenshotDir(args: Map<String, Any?>? = null): JsonElement =
    call("player.getScreenshotDir", args)
suspend fun LinPlayerCommands.playerGetSkipRange(args: Map<String, Any?>? = null): JsonElement =
    call("player.getSkipRange", args)
suspend fun LinPlayerCommands.playerMpvCommand(args: Map<String, Any?>? = null): JsonElement =
    call("player.mpvCommand", args)
suspend fun LinPlayerCommands.playerMpvGet(args: Map<String, Any?>? = null): JsonElement =
    call("player.mpvGet", args)
suspend fun LinPlayerCommands.playerMpvSet(args: Map<String, Any?>? = null): JsonElement =
    call("player.mpvSet", args)
suspend fun LinPlayerCommands.playerOpts(args: Map<String, Any?>? = null): JsonElement =
    call("player.opts", args)
suspend fun LinPlayerCommands.playerPlay(args: Map<String, Any?>? = null): JsonElement =
    call("player.play", args)
suspend fun LinPlayerCommands.playerPlayExternal(args: Map<String, Any?>? = null): JsonElement =
    call("player.playExternal", args)
suspend fun LinPlayerCommands.playerPlayLocal(args: Map<String, Any?>? = null): JsonElement =
    call("player.playLocal", args)
suspend fun LinPlayerCommands.playerPlayUrl(args: Map<String, Any?>? = null): JsonElement =
    call("player.playUrl", args)
suspend fun LinPlayerCommands.playerScreenshot(args: Map<String, Any?>? = null): JsonElement =
    call("player.screenshot", args)
suspend fun LinPlayerCommands.playerSeek(args: Map<String, Any?>? = null): JsonElement =
    call("player.seek", args)
suspend fun LinPlayerCommands.playerSetAspectRatio(args: Map<String, Any?>? = null): JsonElement =
    call("player.setAspectRatio", args)
suspend fun LinPlayerCommands.playerSetAudioDelay(args: Map<String, Any?>? = null): JsonElement =
    call("player.setAudioDelay", args)
suspend fun LinPlayerCommands.playerSetHwdec(args: Map<String, Any?>? = null): JsonElement =
    call("player.setHwdec", args)
suspend fun LinPlayerCommands.playerSetMpvConf(args: Map<String, Any?>? = null): JsonElement =
    call("player.setMpvConf", args)
suspend fun LinPlayerCommands.playerSetMute(args: Map<String, Any?>? = null): JsonElement =
    call("player.setMute", args)
suspend fun LinPlayerCommands.playerSetPause(args: Map<String, Any?>? = null): JsonElement =
    call("player.setPause", args)
suspend fun LinPlayerCommands.playerDanmakuSet(args: Map<String, Any?>? = null): JsonElement =
    call("player.danmakuSet", args)
suspend fun LinPlayerCommands.playerSetDanmakuEnabled(args: Map<String, Any?>? = null): JsonElement =
    call("player.setDanmakuEnabled", args)
suspend fun LinPlayerCommands.playerSetDanmakuStyle(args: Map<String, Any?>? = null): JsonElement =
    call("player.setDanmakuStyle", args)
suspend fun LinPlayerCommands.playerGetDanmakuStyle(args: Map<String, Any?>? = null): JsonElement =
    call("player.getDanmakuStyle", args)
suspend fun LinPlayerCommands.playerDanmakuLayout(args: Map<String, Any?>? = null): JsonElement =
    call("player.danmakuLayout", args)
suspend fun LinPlayerCommands.playerDanmakuHeatmap(args: Map<String, Any?>? = null): JsonElement =
    call("player.danmakuHeatmap", args)
suspend fun LinPlayerCommands.playerSetPlaybackPrefs(args: Map<String, Any?>? = null): JsonElement =
    call("player.setPlaybackPrefs", args)
suspend fun LinPlayerCommands.playerSetScreenshotDir(args: Map<String, Any?>? = null): JsonElement =
    call("player.setScreenshotDir", args)
suspend fun LinPlayerCommands.playerSetSecondarySub(args: Map<String, Any?>? = null): JsonElement =
    call("player.setSecondarySub", args)
suspend fun LinPlayerCommands.playerSetSecondarySubOpts(args: Map<String, Any?>? = null): JsonElement =
    call("player.setSecondarySubOpts", args)
suspend fun LinPlayerCommands.playerSetShaderLevel(args: Map<String, Any?>? = null): JsonElement =
    call("player.setShaderLevel", args)
suspend fun LinPlayerCommands.playerSetSkipRange(args: Map<String, Any?>? = null): JsonElement =
    call("player.setSkipRange", args)
suspend fun LinPlayerCommands.playerSetSpeed(args: Map<String, Any?>? = null): JsonElement =
    call("player.setSpeed", args)
suspend fun LinPlayerCommands.playerSetSubDelay(args: Map<String, Any?>? = null): JsonElement =
    call("player.setSubDelay", args)
suspend fun LinPlayerCommands.playerSetSubStyle(args: Map<String, Any?>? = null): JsonElement =
    call("player.setSubStyle", args)
suspend fun LinPlayerCommands.playerGetSubStyle(args: Map<String, Any?>? = null): JsonElement =
    call("player.getSubStyle", args)
suspend fun LinPlayerCommands.playerInterpLevels(args: Map<String, Any?>? = null): JsonElement =
    call("player.interpLevels", args)
suspend fun LinPlayerCommands.playerSetInterpLevel(args: Map<String, Any?>? = null): JsonElement =
    call("player.setInterpLevel", args)
suspend fun LinPlayerCommands.playerInterpInstall(args: Map<String, Any?>? = null): JsonElement =
    call("player.interpInstall", args)
suspend fun LinPlayerCommands.playerSetTrack(args: Map<String, Any?>? = null): JsonElement =
    call("player.setTrack", args)
suspend fun LinPlayerCommands.playerSetTrackRegexes(args: Map<String, Any?>? = null): JsonElement =
    call("player.setTrackRegexes", args)
suspend fun LinPlayerCommands.playerSetVolume(args: Map<String, Any?>? = null): JsonElement =
    call("player.setVolume", args)
suspend fun LinPlayerCommands.playerShaderLevels(args: Map<String, Any?>? = null): JsonElement =
    call("player.shaderLevels", args)
suspend fun LinPlayerCommands.playerStatus(args: Map<String, Any?>? = null): JsonElement =
    call("player.status", args)
suspend fun LinPlayerCommands.playerStopPlayback(args: Map<String, Any?>? = null): JsonElement =
    call("player.stopPlayback", args)
suspend fun LinPlayerCommands.playerTakePending(args: Map<String, Any?>? = null): JsonElement =
    call("player.takePending", args)
suspend fun LinPlayerCommands.playerThumbnail(args: Map<String, Any?>? = null): JsonElement =
    call("player.thumbnail", args)
suspend fun LinPlayerCommands.playerTracks(args: Map<String, Any?>? = null): JsonElement =
    call("player.tracks", args)
suspend fun LinPlayerCommands.playerValidateTrackRegex(args: Map<String, Any?>? = null): JsonElement =
    call("player.validateTrackRegex", args)
suspend fun LinPlayerCommands.playerWindowClose(args: Map<String, Any?>? = null): JsonElement =
    call("player.windowClose", args)
suspend fun LinPlayerCommands.playerWindowOpen(args: Map<String, Any?>? = null): JsonElement =
    call("player.windowOpen", args)

// ---- 媒体源与数据源 · source.* (30 条) ----
suspend fun LinPlayerCommands.sourceCurrentSource(args: Map<String, Any?>? = null): JsonElement =
    call("source.currentSource", args)
suspend fun LinPlayerCommands.sourceListDir(args: Map<String, Any?>? = null): JsonElement =
    call("source.listDir", args)
suspend fun LinPlayerCommands.sourceLogin(args: Map<String, Any?>? = null): JsonElement =
    call("source.login", args)
suspend fun LinPlayerCommands.sourceFormSchema(args: Map<String, Any?>? = null): JsonElement =
    call("source.formSchema", args)
suspend fun LinPlayerCommands.sourcePlay(args: Map<String, Any?>? = null): JsonElement =
    call("source.play", args)
suspend fun LinPlayerCommands.sourceSearch(args: Map<String, Any?>? = null): JsonElement =
    call("source.search", args)
suspend fun LinPlayerCommands.sourceWatchdog(args: Map<String, Any?>? = null): JsonElement =
    call("source.watchdog", args)
suspend fun LinPlayerCommands.sourceCaps(args: Map<String, Any?>? = null): JsonElement =
    call("source.caps", args)
suspend fun LinPlayerCommands.sourceHome(args: Map<String, Any?>? = null): JsonElement =
    call("source.home", args)
suspend fun LinPlayerCommands.sourceCategory(args: Map<String, Any?>? = null): JsonElement =
    call("source.category", args)
suspend fun LinPlayerCommands.sourceSearchItems(args: Map<String, Any?>? = null): JsonElement =
    call("source.searchItems", args)
suspend fun LinPlayerCommands.sourcePerson(args: Map<String, Any?>? = null): JsonElement =
    call("source.person", args)
suspend fun LinPlayerCommands.sourceDetail(args: Map<String, Any?>? = null): JsonElement =
    call("source.detail", args)
suspend fun LinPlayerCommands.sourcePlayItem(args: Map<String, Any?>? = null): JsonElement =
    call("source.playItem", args)
suspend fun LinPlayerCommands.sourceContinueWatching(args: Map<String, Any?>? = null): JsonElement =
    call("source.continueWatching", args)
suspend fun LinPlayerCommands.sourceCreateSources(args: Map<String, Any?>? = null): JsonElement =
    call("source.createSources", args)
suspend fun LinPlayerCommands.sourceAddSources(args: Map<String, Any?>? = null): JsonElement =
    call("source.addSources", args)
suspend fun LinPlayerCommands.sourceSetAggregate(args: Map<String, Any?>? = null): JsonElement =
    call("source.setAggregate", args)
suspend fun LinPlayerCommands.sourceSetHost(args: Map<String, Any?>? = null): JsonElement =
    call("source.setHost", args)
suspend fun LinPlayerCommands.sourceRemoveGroup(args: Map<String, Any?>? = null): JsonElement =
    call("source.removeGroup", args)
suspend fun LinPlayerCommands.sourceServerMenus(args: Map<String, Any?>? = null): JsonElement =
    call("source.serverMenus", args)
suspend fun LinPlayerCommands.sourceRunCommand(args: Map<String, Any?>? = null): JsonElement =
    call("source.runCommand", args)
suspend fun LinPlayerCommands.sourceAggregateSearch(args: Map<String, Any?>? = null): JsonElement =
    call("source.aggregateSearch", args)
suspend fun LinPlayerCommands.sourceSwitchCandidates(args: Map<String, Any?>? = null): JsonElement =
    call("source.switchCandidates", args)
suspend fun LinPlayerCommands.sourceCheckAll(args: Map<String, Any?>? = null): JsonElement =
    call("source.checkAll", args)
suspend fun LinPlayerCommands.sourceSetFavorite(args: Map<String, Any?>? = null): JsonElement =
    call("source.setFavorite", args)
suspend fun LinPlayerCommands.sourceIsFavorite(args: Map<String, Any?>? = null): JsonElement =
    call("source.isFavorite", args)
suspend fun LinPlayerCommands.sourceFavorites(args: Map<String, Any?>? = null): JsonElement =
    call("source.favorites", args)
suspend fun LinPlayerCommands.sourceHistory(args: Map<String, Any?>? = null): JsonElement =
    call("source.history", args)
suspend fun LinPlayerCommands.sourceLinkSwitch(args: Map<String, Any?>? = null): JsonElement =
    call("source.linkSwitch", args)

// ---- 弹幕 · danmaku.* (17 条) ----
suspend fun LinPlayerCommands.danmakuAutoLoad(args: Map<String, Any?>? = null): JsonElement =
    call("danmaku.autoLoad", args)
suspend fun LinPlayerCommands.danmakuCacheClear(args: Map<String, Any?>? = null): JsonElement =
    call("danmaku.cacheClear", args)
suspend fun LinPlayerCommands.danmakuCacheSize(args: Map<String, Any?>? = null): JsonElement =
    call("danmaku.cacheSize", args)
suspend fun LinPlayerCommands.danmakuEpisodes(args: Map<String, Any?>? = null): JsonElement =
    call("danmaku.episodes", args)
suspend fun LinPlayerCommands.danmakuFilter(args: Map<String, Any?>? = null): JsonElement =
    call("danmaku.filter", args)
suspend fun LinPlayerCommands.danmakuGetDanmakuConfig(args: Map<String, Any?>? = null): JsonElement =
    call("danmaku.getDanmakuConfig", args)
suspend fun LinPlayerCommands.danmakuGetOfficialDanmaku(args: Map<String, Any?>? = null): JsonElement =
    call("danmaku.getOfficialDanmaku", args)
suspend fun LinPlayerCommands.danmakuImportBlocklist(args: Map<String, Any?>? = null): JsonElement =
    call("danmaku.importBlocklist", args)
suspend fun LinPlayerCommands.danmakuLoad(args: Map<String, Any?>? = null): JsonElement =
    call("danmaku.load", args)
suspend fun LinPlayerCommands.danmakuLoadLocal(args: Map<String, Any?>? = null): JsonElement =
    call("danmaku.loadLocal", args)
suspend fun LinPlayerCommands.danmakuMatch(args: Map<String, Any?>? = null): JsonElement =
    call("danmaku.match", args)
suspend fun LinPlayerCommands.danmakuLastMatch(args: Map<String, Any?>? = null): JsonElement =
    call("danmaku.lastMatch", args)
suspend fun LinPlayerCommands.danmakuMinAutoScore(args: Map<String, Any?>? = null): JsonElement =
    call("danmaku.minAutoScore", args)
suspend fun LinPlayerCommands.danmakuSearch(args: Map<String, Any?>? = null): JsonElement =
    call("danmaku.search", args)
suspend fun LinPlayerCommands.danmakuSetDanmakuConfig(args: Map<String, Any?>? = null): JsonElement =
    call("danmaku.setDanmakuConfig", args)
suspend fun LinPlayerCommands.danmakuGetBlockwords(args: Map<String, Any?>? = null): JsonElement =
    call("danmaku.getBlockwords", args)
suspend fun LinPlayerCommands.danmakuSetBlockwords(args: Map<String, Any?>? = null): JsonElement =
    call("danmaku.setBlockwords", args)

// ---- 下载 · download.* (9 条) ----
suspend fun LinPlayerCommands.downloadAndApplyUpdate(args: Map<String, Any?>? = null): JsonElement =
    call("download.andApplyUpdate", args)
suspend fun LinPlayerCommands.downloadClearCompleted(args: Map<String, Any?>? = null): JsonElement =
    call("download.clearCompleted", args)
suspend fun LinPlayerCommands.downloadEnqueue(args: Map<String, Any?>? = null): JsonElement =
    call("download.enqueue", args)
suspend fun LinPlayerCommands.downloadEnqueueSeason(args: Map<String, Any?>? = null): JsonElement =
    call("download.enqueueSeason", args)
suspend fun LinPlayerCommands.downloadList(args: Map<String, Any?>? = null): JsonElement =
    call("download.list", args)
suspend fun LinPlayerCommands.downloadPause(args: Map<String, Any?>? = null): JsonElement =
    call("download.pause", args)
suspend fun LinPlayerCommands.downloadRemove(args: Map<String, Any?>? = null): JsonElement =
    call("download.remove", args)
suspend fun LinPlayerCommands.downloadResume(args: Map<String, Any?>? = null): JsonElement =
    call("download.resume", args)
suspend fun LinPlayerCommands.downloadSetThreads(args: Map<String, Any?>? = null): JsonElement =
    call("download.setThreads", args)

// ---- 设置与偏好 · prefs.* (30 条) ----
suspend fun LinPlayerCommands.prefsApplyPrefs(args: Map<String, Any?>? = null): JsonElement =
    call("prefs.applyPrefs", args)
suspend fun LinPlayerCommands.prefsCfProxyDisable(args: Map<String, Any?>? = null): JsonElement =
    call("prefs.cfProxyDisable", args)
suspend fun LinPlayerCommands.prefsCfProxyEnable(args: Map<String, Any?>? = null): JsonElement =
    call("prefs.cfProxyEnable", args)
suspend fun LinPlayerCommands.prefsCfProxyStatus(args: Map<String, Any?>? = null): JsonElement =
    call("prefs.cfProxyStatus", args)
suspend fun LinPlayerCommands.prefsCfSpeedTest(args: Map<String, Any?>? = null): JsonElement =
    call("prefs.cfSpeedTest", args)
suspend fun LinPlayerCommands.prefsConfigExportQr(args: Map<String, Any?>? = null): JsonElement =
    call("prefs.configExportQr", args)
suspend fun LinPlayerCommands.prefsConfigImportQr(args: Map<String, Any?>? = null): JsonElement =
    call("prefs.configImportQr", args)
suspend fun LinPlayerCommands.prefsBackupExport(args: Map<String, Any?>? = null): JsonElement =
    call("prefs.backupExport", args)
suspend fun LinPlayerCommands.prefsBackupImport(args: Map<String, Any?>? = null): JsonElement =
    call("prefs.backupImport", args)
suspend fun LinPlayerCommands.prefsBackupPreview(args: Map<String, Any?>? = null): JsonElement =
    call("prefs.backupPreview", args)
suspend fun LinPlayerCommands.prefsGetHomeSettings(args: Map<String, Any?>? = null): JsonElement =
    call("prefs.getHomeSettings", args)
suspend fun LinPlayerCommands.prefsGetPrefetchSettings(args: Map<String, Any?>? = null): JsonElement =
    call("prefs.getPrefetchSettings", args)
suspend fun LinPlayerCommands.prefsGetPrefs(args: Map<String, Any?>? = null): JsonElement =
    call("prefs.getPrefs", args)
suspend fun LinPlayerCommands.prefsGetPreloadSettings(args: Map<String, Any?>? = null): JsonElement =
    call("prefs.getPreloadSettings", args)
suspend fun LinPlayerCommands.prefsGetProxy(args: Map<String, Any?>? = null): JsonElement =
    call("prefs.getProxy", args)
suspend fun LinPlayerCommands.prefsGetUpdateSettings(args: Map<String, Any?>? = null): JsonElement =
    call("prefs.getUpdateSettings", args)
suspend fun LinPlayerCommands.prefsGetWritebackSettings(args: Map<String, Any?>? = null): JsonElement =
    call("prefs.getWritebackSettings", args)
suspend fun LinPlayerCommands.prefsIconLibrary(args: Map<String, Any?>? = null): JsonElement =
    call("prefs.iconLibrary", args)
suspend fun LinPlayerCommands.prefsSetIconSources(args: Map<String, Any?>? = null): JsonElement =
    call("prefs.setIconSources", args)
suspend fun LinPlayerCommands.prefsPreloadCancel(args: Map<String, Any?>? = null): JsonElement =
    call("prefs.preloadCancel", args)
suspend fun LinPlayerCommands.prefsPreloadItem(args: Map<String, Any?>? = null): JsonElement =
    call("prefs.preloadItem", args)
suspend fun LinPlayerCommands.prefsSetDetailBlur(args: Map<String, Any?>? = null): JsonElement =
    call("prefs.setDetailBlur", args)
suspend fun LinPlayerCommands.prefsSetHomeSettings(args: Map<String, Any?>? = null): JsonElement =
    call("prefs.setHomeSettings", args)
suspend fun LinPlayerCommands.prefsSetPrefetchSettings(args: Map<String, Any?>? = null): JsonElement =
    call("prefs.setPrefetchSettings", args)
suspend fun LinPlayerCommands.prefsSetPrefs(args: Map<String, Any?>? = null): JsonElement =
    call("prefs.setPrefs", args)
suspend fun LinPlayerCommands.prefsPushSearch(args: Map<String, Any?>? = null): JsonElement =
    call("prefs.pushSearch", args)
suspend fun LinPlayerCommands.prefsSetPreloadSettings(args: Map<String, Any?>? = null): JsonElement =
    call("prefs.setPreloadSettings", args)
suspend fun LinPlayerCommands.prefsSetProxy(args: Map<String, Any?>? = null): JsonElement =
    call("prefs.setProxy", args)
suspend fun LinPlayerCommands.prefsSetUpdateSettings(args: Map<String, Any?>? = null): JsonElement =
    call("prefs.setUpdateSettings", args)
suspend fun LinPlayerCommands.prefsSetWritebackSettings(args: Map<String, Any?>? = null): JsonElement =
    call("prefs.setWritebackSettings", args)

// ---- 系统 · system.* (20 条) ----
suspend fun LinPlayerCommands.systemCacheSize(args: Map<String, Any?>? = null): JsonElement =
    call("system.cacheSize", args)
suspend fun LinPlayerCommands.systemCancelUpdate(args: Map<String, Any?>? = null): JsonElement =
    call("system.cancelUpdate", args)
suspend fun LinPlayerCommands.systemCapabilities(args: Map<String, Any?>? = null): JsonElement =
    call("system.capabilities", args)
suspend fun LinPlayerCommands.systemCheckUpdate(args: Map<String, Any?>? = null): JsonElement =
    call("system.checkUpdate", args)
suspend fun LinPlayerCommands.systemClearCache(args: Map<String, Any?>? = null): JsonElement =
    call("system.clearCache", args)
suspend fun LinPlayerCommands.systemDataPaths(args: Map<String, Any?>? = null): JsonElement =
    call("system.dataPaths", args)
suspend fun LinPlayerCommands.systemDownloadUpdate(args: Map<String, Any?>? = null): JsonElement =
    call("system.downloadUpdate", args)
suspend fun LinPlayerCommands.systemExportDiagnostics(args: Map<String, Any?>? = null): JsonElement =
    call("system.exportDiagnostics", args)
suspend fun LinPlayerCommands.systemInstallUpdate(args: Map<String, Any?>? = null): JsonElement =
    call("system.installUpdate", args)
suspend fun LinPlayerCommands.systemSendReport(args: Map<String, Any?>? = null): JsonElement =
    call("system.sendReport", args)
suspend fun LinPlayerCommands.systemOpenDataDir(args: Map<String, Any?>? = null): JsonElement =
    call("system.openDataDir", args)
suspend fun LinPlayerCommands.systemShortcutStatus(args: Map<String, Any?>? = null): JsonElement =
    call("system.shortcutStatus", args)
suspend fun LinPlayerCommands.systemMakeShortcut(args: Map<String, Any?>? = null): JsonElement =
    call("system.makeShortcut", args)
suspend fun LinPlayerCommands.systemPickDirectory(args: Map<String, Any?>? = null): JsonElement =
    call("system.pickDirectory", args)
suspend fun LinPlayerCommands.systemPickFile(args: Map<String, Any?>? = null): JsonElement =
    call("system.pickFile", args)
suspend fun LinPlayerCommands.systemPickLocalFolder(args: Map<String, Any?>? = null): JsonElement =
    call("system.pickLocalFolder", args)
suspend fun LinPlayerCommands.systemPing(args: Map<String, Any?>? = null): JsonElement =
    call("system.ping", args)
suspend fun LinPlayerCommands.systemUpdateProgress(args: Map<String, Any?>? = null): JsonElement =
    call("system.updateProgress", args)
suspend fun LinPlayerCommands.systemAfdianSponsorUrl(args: Map<String, Any?>? = null): JsonElement =
    call("system.afdianSponsorUrl", args)
suspend fun LinPlayerCommands.systemAfdianVerify(args: Map<String, Any?>? = null): JsonElement =
    call("system.afdianVerify", args)

// ---- 手机扫码遥控(电视端) · companion.* (4 条) ----
suspend fun LinPlayerCommands.companionStart(args: Map<String, Any?>? = null): JsonElement =
    call("companion.start", args)
suspend fun LinPlayerCommands.companionStatus(args: Map<String, Any?>? = null): JsonElement =
    call("companion.status", args)
suspend fun LinPlayerCommands.companionSetEnabled(args: Map<String, Any?>? = null): JsonElement =
    call("companion.setEnabled", args)
suspend fun LinPlayerCommands.companionSetNowPlaying(args: Map<String, Any?>? = null): JsonElement =
    call("companion.setNowPlaying", args)

// ---- 同步账号与追剧日历 · sync.* (14 条) ----
suspend fun LinPlayerCommands.syncTraktAccount(args: Map<String, Any?>? = null): JsonElement =
    call("sync.traktAccount", args)
suspend fun LinPlayerCommands.syncTraktDeviceCode(args: Map<String, Any?>? = null): JsonElement =
    call("sync.traktDeviceCode", args)
suspend fun LinPlayerCommands.syncTraktPoll(args: Map<String, Any?>? = null): JsonElement =
    call("sync.traktPoll", args)
suspend fun LinPlayerCommands.syncTraktLogout(args: Map<String, Any?>? = null): JsonElement =
    call("sync.traktLogout", args)
suspend fun LinPlayerCommands.syncTraktCalendar(args: Map<String, Any?>? = null): JsonElement =
    call("sync.traktCalendar", args)
suspend fun LinPlayerCommands.syncBangumiAccount(args: Map<String, Any?>? = null): JsonElement =
    call("sync.bangumiAccount", args)
suspend fun LinPlayerCommands.syncBangumiAuthorizeUrl(args: Map<String, Any?>? = null): JsonElement =
    call("sync.bangumiAuthorizeUrl", args)
suspend fun LinPlayerCommands.syncBangumiExchange(args: Map<String, Any?>? = null): JsonElement =
    call("sync.bangumiExchange", args)
suspend fun LinPlayerCommands.syncBangumiLoginToken(args: Map<String, Any?>? = null): JsonElement =
    call("sync.bangumiLoginToken", args)
suspend fun LinPlayerCommands.syncBangumiLogout(args: Map<String, Any?>? = null): JsonElement =
    call("sync.bangumiLogout", args)
suspend fun LinPlayerCommands.syncBangumiSummary(args: Map<String, Any?>? = null): JsonElement =
    call("sync.bangumiSummary", args)
suspend fun LinPlayerCommands.syncBangumiCalendar(args: Map<String, Any?>? = null): JsonElement =
    call("sync.bangumiCalendar", args)
suspend fun LinPlayerCommands.syncCalendarLibrary(args: Map<String, Any?>? = null): JsonElement =
    call("sync.calendarLibrary", args)
suspend fun LinPlayerCommands.syncCalendarDue(args: Map<String, Any?>? = null): JsonElement =
    call("sync.calendarDue", args)

// ---- 插件 · plugin.* (58 条) ----
suspend fun LinPlayerCommands.pluginList(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.list", args)
suspend fun LinPlayerCommands.pluginPendingRestart(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.pendingRestart", args)
suspend fun LinPlayerCommands.pluginInspect(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.inspect", args)
suspend fun LinPlayerCommands.pluginInstallFile(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.installFile", args)
suspend fun LinPlayerCommands.pluginInstallFromRepo(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.installFromRepo", args)
suspend fun LinPlayerCommands.pluginUninstall(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.uninstall", args)
suspend fun LinPlayerCommands.pluginCancelUninstall(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.cancelUninstall", args)
suspend fun LinPlayerCommands.pluginSetEnabled(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.setEnabled", args)
suspend fun LinPlayerCommands.pluginDisableAll(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.disableAll", args)
suspend fun LinPlayerCommands.pluginRestoreAll(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.restoreAll", args)
suspend fun LinPlayerCommands.pluginRollback(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.rollback", args)
suspend fun LinPlayerCommands.pluginSetLocked(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.setLocked", args)
suspend fun LinPlayerCommands.pluginSkipVersion(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.skipVersion", args)
suspend fun LinPlayerCommands.pluginDetail(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.detail", args)
suspend fun LinPlayerCommands.pluginSetSetting(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.setSetting", args)
suspend fun LinPlayerCommands.pluginClearData(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.clearData", args)
suspend fun LinPlayerCommands.pluginErrorDetail(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.errorDetail", args)
suspend fun LinPlayerCommands.pluginMarket(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.market", args)
suspend fun LinPlayerCommands.pluginRepos(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.repos", args)
suspend fun LinPlayerCommands.pluginAddRepo(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.addRepo", args)
suspend fun LinPlayerCommands.pluginRemoveRepo(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.removeRepo", args)
suspend fun LinPlayerCommands.pluginSetGithubPrefix(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.setGithubPrefix", args)
suspend fun LinPlayerCommands.pluginSetAutoUpdate(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.setAutoUpdate", args)
suspend fun LinPlayerCommands.pluginUpdates(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.updates", args)
suspend fun LinPlayerCommands.pluginUpdateAll(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.updateAll", args)
suspend fun LinPlayerCommands.pluginTakeovers(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.takeovers", args)
suspend fun LinPlayerCommands.pluginSetTakeover(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.setTakeover", args)
suspend fun LinPlayerCommands.pluginDevLoad(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.devLoad", args)
suspend fun LinPlayerCommands.pluginDevUnload(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.devUnload", args)
suspend fun LinPlayerCommands.pluginDevList(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.devList", args)
suspend fun LinPlayerCommands.pluginSetCapabilities(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.setCapabilities", args)
suspend fun LinPlayerCommands.pluginAppEvent(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.appEvent", args)
suspend fun LinPlayerCommands.pluginAnchors(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.anchors", args)
suspend fun LinPlayerCommands.pluginSettingsSections(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.settingsSections", args)
suspend fun LinPlayerCommands.syncTraktRequest(args: Map<String, Any?>? = null): JsonElement =
    call("sync.traktRequest", args)
suspend fun LinPlayerCommands.syncBangumiRequest(args: Map<String, Any?>? = null): JsonElement =
    call("sync.bangumiRequest", args)
suspend fun LinPlayerCommands.playerGetSubtitleText(args: Map<String, Any?>? = null): JsonElement =
    call("player.getSubtitleText", args)
suspend fun LinPlayerCommands.pluginSetEnv(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.setEnv", args)
suspend fun LinPlayerCommands.pluginShellResult(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.shellResult", args)
suspend fun LinPlayerCommands.pluginThemes(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.themes", args)
suspend fun LinPlayerCommands.pluginActiveTheme(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.activeTheme", args)
suspend fun LinPlayerCommands.pluginSetActiveTheme(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.setActiveTheme", args)
suspend fun LinPlayerCommands.pluginThemeFailed(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.themeFailed", args)
suspend fun LinPlayerCommands.pluginWallpapers(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.wallpapers", args)
suspend fun LinPlayerCommands.pluginActiveWallpaper(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.activeWallpaper", args)
suspend fun LinPlayerCommands.pluginInitialWallpaper(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.initialWallpaper", args)
suspend fun LinPlayerCommands.pluginSetActiveWallpaper(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.setActiveWallpaper", args)
suspend fun LinPlayerCommands.pluginPlayerSurfaces(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.playerSurfaces", args)
suspend fun LinPlayerCommands.pluginSidebar(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.sidebar", args)
suspend fun LinPlayerCommands.pluginHomeSections(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.homeSections", args)
suspend fun LinPlayerCommands.pluginHomeItems(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.homeItems", args)
suspend fun LinPlayerCommands.pluginBackRequest(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.backRequest", args)
suspend fun LinPlayerCommands.pluginPlayerKey(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.playerKey", args)
suspend fun LinPlayerCommands.pluginSetCookies(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.setCookies", args)
suspend fun LinPlayerCommands.pluginUiMount(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.ui.mount", args)
suspend fun LinPlayerCommands.pluginUiUnmount(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.ui.unmount", args)
suspend fun LinPlayerCommands.pluginUiEvent(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.ui.event", args)
suspend fun LinPlayerCommands.pluginUiViewport(args: Map<String, Any?>? = null): JsonElement =
    call("plugin.ui.viewport", args)
