// 本文件由 scripts/gen-bindings.py 从 docs/go-migration/COMMANDS.md 生成。
// 不要手改 —— 改了会在下一次生成时被覆盖,而且四方比对会红。
#nullable enable

using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace LinPlayer.Core;

/// <summary>
/// 核心层命令的类型化包装。**生成的,不要手写。**
/// </summary>
/// <remarks>
/// 参数与返回暂时是弱类型(<c>object?</c> / <see cref="JsonElement"/>):
/// COMMANDS.md 的参数列现在装的是现有 Rust 签名,不是新契约的 JSON 形状。
/// 形状回填之后这里会换成 record。
///
/// <para>
/// ⚠️ <c>lp_next_event</c> 不在这里暴露:有且仅有一个消费者线程能调它。
/// 两个线程同时调不会崩,而是事件被<b>随机分给两个线程</b> ——
/// 表现为「有时候收得到有时候收不到」。事件请走 <c>CoreClient</c> 的事件流。
/// </para>
/// </remarks>
public partial interface ILinPlayerCommands
{
    /// <summary>发一条命令,等它的 result 事件。</summary>
    Task<JsonElement> CallAsync(string command, object? args, CancellationToken ct = default);
}

public static class LinPlayerAbi
{
    /// <summary>ABI 版本。真值在 core/ffi/abi.go,这里是生成出来的副本。</summary>
    public const int Version = 1;
}

/// <summary>全部命令名。四方比对(COMMANDS.md ↔ Go 注册表 ↔ 三端绑定)用这份。</summary>
public static class LinPlayerCommandNames
{
    public static readonly string[] All =
    [
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
        "plugin.playerSurfaces",
        "plugin.sidebar",
        "plugin.backRequest",
        "plugin.playerKey",
        "plugin.setCookies",
        "plugin.ui.mount",
        "plugin.ui.unmount",
        "plugin.ui.event",
        "plugin.ui.viewport",
    ];
}

public static class LinPlayerCommandsExtensions
{
    // ---- Emby 浏览与详情 · emby.* (45 条) ----
    public static Task<JsonElement> EmbyAggregateOverview(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.aggregateOverview", args, ct);
    public static Task<JsonElement> EmbyAggregateSearch(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.aggregateSearch", args, ct);
    public static Task<JsonElement> EmbyRankingCategories(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.rankingCategories", args, ct);
    public static Task<JsonElement> EmbyRankingFetch(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.rankingFetch", args, ct);
    public static Task<JsonElement> EmbyAggregateVersions(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.aggregateVersions", args, ct);
    public static Task<JsonElement> EmbyBlockedList(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.blockedList", args, ct);
    public static Task<JsonElement> EmbyCounts(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.counts", args, ct);
    public static Task<JsonElement> EmbyCurrentSession(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.currentSession", args, ct);
    public static Task<JsonElement> EmbyGetFilters(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.getFilters", args, ct);
    public static Task<JsonElement> EmbyIsAdmin(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.isAdmin", args, ct);
    public static Task<JsonElement> EmbyItemDetail(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.itemDetail", args, ct);
    public static Task<JsonElement> EmbyItemMedia(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.itemMedia", args, ct);
    public static Task<JsonElement> EmbyListCollections(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.listCollections", args, ct);
    public static Task<JsonElement> EmbyCollectionItems(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.collectionItems", args, ct);
    public static Task<JsonElement> EmbyListFavorites(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.listFavorites", args, ct);
    public static Task<JsonElement> EmbyListItems(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.listItems", args, ct);
    public static Task<JsonElement> EmbyListItemsPage(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.listItemsPage", args, ct);
    public static Task<JsonElement> EmbyListLatest(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.listLatest", args, ct);
    public static Task<JsonElement> EmbyListNextUp(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.listNextUp", args, ct);
    public static Task<JsonElement> EmbyListRandom(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.listRandom", args, ct);
    public static Task<JsonElement> EmbyListResume(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.listResume", args, ct);
    public static Task<JsonElement> EmbyLogin(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.login", args, ct);
    public static Task<JsonElement> EmbyLogout(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.logout", args, ct);
    public static Task<JsonElement> EmbyPersonDetail(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.personDetail", args, ct);
    public static Task<JsonElement> EmbyPersonItems(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.personItems", args, ct);
    public static Task<JsonElement> EmbyPermissions(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.permissions", args, ct);
    public static Task<JsonElement> EmbyRefreshItem(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.refreshItem", args, ct);
    public static Task<JsonElement> EmbyRelogin(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.relogin", args, ct);
    public static Task<JsonElement> EmbyReportProgress(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.reportProgress", args, ct);
    public static Task<JsonElement> EmbyScanLibraries(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.scanLibraries", args, ct);
    public static Task<JsonElement> EmbySearch(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.search", args, ct);
    public static Task<JsonElement> EmbySeasonEpisodes(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.seasonEpisodes", args, ct);
    public static Task<JsonElement> EmbySeriesSeasons(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.seriesSeasons", args, ct);
    public static Task<JsonElement> EmbyProviders(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.providers", args, ct);
    public static Task<JsonElement> EmbySetBlocked(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.setBlocked", args, ct);
    public static Task<JsonElement> EmbySetFavorite(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.setFavorite", args, ct);
    public static Task<JsonElement> EmbySetPlayed(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.setPlayed", args, ct);
    public static Task<JsonElement> EmbyHideResume(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.hideResume", args, ct);
    public static Task<JsonElement> EmbySimilarItems(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.similarItems", args, ct);
    public static Task<JsonElement> EmbyViews(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.views", args, ct);
    public static Task<JsonElement> EmbyWatchHistoryClear(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.watchHistoryClear", args, ct);
    public static Task<JsonElement> EmbyWatchHistoryDelete(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.watchHistoryDelete", args, ct);
    public static Task<JsonElement> EmbyWatchHistoryList(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.watchHistoryList", args, ct);
    public static Task<JsonElement> EmbyWatchHistoryRestoreCandidate(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.watchHistoryRestoreCandidate", args, ct);
    public static Task<JsonElement> EmbyWatchHistoryScanRestore(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("emby.watchHistoryScanRestore", args, ct);

    // ---- 账号与线路 · account.* (21 条) ----
    public static Task<JsonElement> AccountBatchAddServers(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("account.batchAddServers", args, ct);
    public static Task<JsonElement> AccountBatchParse(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("account.batchParse", args, ct);
    public static Task<JsonElement> AccountClearAccountIcon(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("account.clearAccountIcon", args, ct);
    public static Task<JsonElement> AccountGetCrossServerResume(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("account.getCrossServerResume", args, ct);
    public static Task<JsonElement> AccountIcon(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("account.icon", args, ct);
    public static Task<JsonElement> AccountListAccounts(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("account.listAccounts", args, ct);
    public static Task<JsonElement> AccountParseDeepLink(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("account.parseDeepLink", args, ct);
    public static Task<JsonElement> AccountProbeAccounts(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("account.probeAccounts", args, ct);
    public static Task<JsonElement> AccountProbeLine(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("account.probeLine", args, ct);
    public static Task<JsonElement> AccountProbeLines(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("account.probeLines", args, ct);
    public static Task<JsonElement> AccountRemoveAccount(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("account.removeAccount", args, ct);
    public static Task<JsonElement> AccountReorderAccounts(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("account.reorderAccounts", args, ct);
    public static Task<JsonElement> AccountSetAccountIconFile(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("account.setAccountIconFile", args, ct);
    public static Task<JsonElement> AccountSetActiveLine(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("account.setActiveLine", args, ct);
    public static Task<JsonElement> AccountSetActiveServer(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("account.setActiveServer", args, ct);
    public static Task<JsonElement> AccountSetCrossServerResume(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("account.setCrossServerResume", args, ct);
    public static Task<JsonElement> AccountSetLines(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("account.setLines", args, ct);
    public static Task<JsonElement> AccountStartupDeepLink(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("account.startupDeepLink", args, ct);
    public static Task<JsonElement> AccountSyncLines(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("account.syncLines", args, ct);
    public static Task<JsonElement> AccountTestConnection(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("account.testConnection", args, ct);
    public static Task<JsonElement> AccountUpdateAccount(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("account.updateAccount", args, ct);

    // ---- 播放器 · player.* (53 条) ----
    public static Task<JsonElement> PlayerAddSubtitle(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.addSubtitle", args, ct);
    public static Task<JsonElement> PlayerChapterInfo(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.chapterInfo", args, ct);
    public static Task<JsonElement> PlayerGetMpvConf(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.getMpvConf", args, ct);
    public static Task<JsonElement> PlayerGetPlaybackPrefs(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.getPlaybackPrefs", args, ct);
    public static Task<JsonElement> PlayerGetScreenshotDir(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.getScreenshotDir", args, ct);
    public static Task<JsonElement> PlayerGetSkipRange(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.getSkipRange", args, ct);
    public static Task<JsonElement> PlayerMpvCommand(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.mpvCommand", args, ct);
    public static Task<JsonElement> PlayerMpvGet(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.mpvGet", args, ct);
    public static Task<JsonElement> PlayerMpvSet(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.mpvSet", args, ct);
    public static Task<JsonElement> PlayerOpts(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.opts", args, ct);
    public static Task<JsonElement> PlayerPlay(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.play", args, ct);
    public static Task<JsonElement> PlayerPlayExternal(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.playExternal", args, ct);
    public static Task<JsonElement> PlayerPlayLocal(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.playLocal", args, ct);
    public static Task<JsonElement> PlayerPlayUrl(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.playUrl", args, ct);
    public static Task<JsonElement> PlayerScreenshot(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.screenshot", args, ct);
    public static Task<JsonElement> PlayerSeek(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.seek", args, ct);
    public static Task<JsonElement> PlayerSetAspectRatio(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.setAspectRatio", args, ct);
    public static Task<JsonElement> PlayerSetAudioDelay(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.setAudioDelay", args, ct);
    public static Task<JsonElement> PlayerSetHwdec(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.setHwdec", args, ct);
    public static Task<JsonElement> PlayerSetMpvConf(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.setMpvConf", args, ct);
    public static Task<JsonElement> PlayerSetMute(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.setMute", args, ct);
    public static Task<JsonElement> PlayerSetPause(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.setPause", args, ct);
    public static Task<JsonElement> PlayerDanmakuSet(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.danmakuSet", args, ct);
    public static Task<JsonElement> PlayerSetDanmakuEnabled(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.setDanmakuEnabled", args, ct);
    public static Task<JsonElement> PlayerSetDanmakuStyle(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.setDanmakuStyle", args, ct);
    public static Task<JsonElement> PlayerGetDanmakuStyle(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.getDanmakuStyle", args, ct);
    public static Task<JsonElement> PlayerDanmakuLayout(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.danmakuLayout", args, ct);
    public static Task<JsonElement> PlayerDanmakuHeatmap(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.danmakuHeatmap", args, ct);
    public static Task<JsonElement> PlayerSetPlaybackPrefs(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.setPlaybackPrefs", args, ct);
    public static Task<JsonElement> PlayerSetScreenshotDir(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.setScreenshotDir", args, ct);
    public static Task<JsonElement> PlayerSetSecondarySub(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.setSecondarySub", args, ct);
    public static Task<JsonElement> PlayerSetSecondarySubOpts(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.setSecondarySubOpts", args, ct);
    public static Task<JsonElement> PlayerSetShaderLevel(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.setShaderLevel", args, ct);
    public static Task<JsonElement> PlayerSetSkipRange(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.setSkipRange", args, ct);
    public static Task<JsonElement> PlayerSetSpeed(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.setSpeed", args, ct);
    public static Task<JsonElement> PlayerSetSubDelay(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.setSubDelay", args, ct);
    public static Task<JsonElement> PlayerSetSubStyle(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.setSubStyle", args, ct);
    public static Task<JsonElement> PlayerGetSubStyle(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.getSubStyle", args, ct);
    public static Task<JsonElement> PlayerInterpLevels(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.interpLevels", args, ct);
    public static Task<JsonElement> PlayerSetInterpLevel(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.setInterpLevel", args, ct);
    public static Task<JsonElement> PlayerInterpInstall(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.interpInstall", args, ct);
    public static Task<JsonElement> PlayerSetTrack(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.setTrack", args, ct);
    public static Task<JsonElement> PlayerSetTrackRegexes(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.setTrackRegexes", args, ct);
    public static Task<JsonElement> PlayerSetVolume(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.setVolume", args, ct);
    public static Task<JsonElement> PlayerShaderLevels(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.shaderLevels", args, ct);
    public static Task<JsonElement> PlayerStatus(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.status", args, ct);
    public static Task<JsonElement> PlayerStopPlayback(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.stopPlayback", args, ct);
    public static Task<JsonElement> PlayerTakePending(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.takePending", args, ct);
    public static Task<JsonElement> PlayerThumbnail(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.thumbnail", args, ct);
    public static Task<JsonElement> PlayerTracks(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.tracks", args, ct);
    public static Task<JsonElement> PlayerValidateTrackRegex(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.validateTrackRegex", args, ct);
    public static Task<JsonElement> PlayerWindowClose(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.windowClose", args, ct);
    public static Task<JsonElement> PlayerWindowOpen(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.windowOpen", args, ct);

    // ---- 媒体源与数据源 · source.* (30 条) ----
    public static Task<JsonElement> SourceCurrentSource(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("source.currentSource", args, ct);
    public static Task<JsonElement> SourceListDir(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("source.listDir", args, ct);
    public static Task<JsonElement> SourceLogin(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("source.login", args, ct);
    public static Task<JsonElement> SourceFormSchema(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("source.formSchema", args, ct);
    public static Task<JsonElement> SourcePlay(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("source.play", args, ct);
    public static Task<JsonElement> SourceSearch(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("source.search", args, ct);
    public static Task<JsonElement> SourceWatchdog(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("source.watchdog", args, ct);
    public static Task<JsonElement> SourceCaps(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("source.caps", args, ct);
    public static Task<JsonElement> SourceHome(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("source.home", args, ct);
    public static Task<JsonElement> SourceCategory(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("source.category", args, ct);
    public static Task<JsonElement> SourceSearchItems(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("source.searchItems", args, ct);
    public static Task<JsonElement> SourcePerson(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("source.person", args, ct);
    public static Task<JsonElement> SourceDetail(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("source.detail", args, ct);
    public static Task<JsonElement> SourcePlayItem(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("source.playItem", args, ct);
    public static Task<JsonElement> SourceContinueWatching(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("source.continueWatching", args, ct);
    public static Task<JsonElement> SourceCreateSources(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("source.createSources", args, ct);
    public static Task<JsonElement> SourceAddSources(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("source.addSources", args, ct);
    public static Task<JsonElement> SourceSetAggregate(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("source.setAggregate", args, ct);
    public static Task<JsonElement> SourceSetHost(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("source.setHost", args, ct);
    public static Task<JsonElement> SourceRemoveGroup(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("source.removeGroup", args, ct);
    public static Task<JsonElement> SourceServerMenus(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("source.serverMenus", args, ct);
    public static Task<JsonElement> SourceRunCommand(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("source.runCommand", args, ct);
    public static Task<JsonElement> SourceAggregateSearch(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("source.aggregateSearch", args, ct);
    public static Task<JsonElement> SourceSwitchCandidates(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("source.switchCandidates", args, ct);
    public static Task<JsonElement> SourceCheckAll(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("source.checkAll", args, ct);
    public static Task<JsonElement> SourceSetFavorite(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("source.setFavorite", args, ct);
    public static Task<JsonElement> SourceIsFavorite(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("source.isFavorite", args, ct);
    public static Task<JsonElement> SourceFavorites(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("source.favorites", args, ct);
    public static Task<JsonElement> SourceHistory(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("source.history", args, ct);
    public static Task<JsonElement> SourceLinkSwitch(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("source.linkSwitch", args, ct);

    // ---- 弹幕 · danmaku.* (17 条) ----
    public static Task<JsonElement> DanmakuAutoLoad(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("danmaku.autoLoad", args, ct);
    public static Task<JsonElement> DanmakuCacheClear(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("danmaku.cacheClear", args, ct);
    public static Task<JsonElement> DanmakuCacheSize(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("danmaku.cacheSize", args, ct);
    public static Task<JsonElement> DanmakuEpisodes(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("danmaku.episodes", args, ct);
    public static Task<JsonElement> DanmakuFilter(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("danmaku.filter", args, ct);
    public static Task<JsonElement> DanmakuGetDanmakuConfig(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("danmaku.getDanmakuConfig", args, ct);
    public static Task<JsonElement> DanmakuGetOfficialDanmaku(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("danmaku.getOfficialDanmaku", args, ct);
    public static Task<JsonElement> DanmakuImportBlocklist(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("danmaku.importBlocklist", args, ct);
    public static Task<JsonElement> DanmakuLoad(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("danmaku.load", args, ct);
    public static Task<JsonElement> DanmakuLoadLocal(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("danmaku.loadLocal", args, ct);
    public static Task<JsonElement> DanmakuMatch(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("danmaku.match", args, ct);
    public static Task<JsonElement> DanmakuLastMatch(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("danmaku.lastMatch", args, ct);
    public static Task<JsonElement> DanmakuMinAutoScore(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("danmaku.minAutoScore", args, ct);
    public static Task<JsonElement> DanmakuSearch(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("danmaku.search", args, ct);
    public static Task<JsonElement> DanmakuSetDanmakuConfig(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("danmaku.setDanmakuConfig", args, ct);
    public static Task<JsonElement> DanmakuGetBlockwords(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("danmaku.getBlockwords", args, ct);
    public static Task<JsonElement> DanmakuSetBlockwords(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("danmaku.setBlockwords", args, ct);

    // ---- 下载 · download.* (9 条) ----
    public static Task<JsonElement> DownloadAndApplyUpdate(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("download.andApplyUpdate", args, ct);
    public static Task<JsonElement> DownloadClearCompleted(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("download.clearCompleted", args, ct);
    public static Task<JsonElement> DownloadEnqueue(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("download.enqueue", args, ct);
    public static Task<JsonElement> DownloadEnqueueSeason(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("download.enqueueSeason", args, ct);
    public static Task<JsonElement> DownloadList(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("download.list", args, ct);
    public static Task<JsonElement> DownloadPause(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("download.pause", args, ct);
    public static Task<JsonElement> DownloadRemove(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("download.remove", args, ct);
    public static Task<JsonElement> DownloadResume(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("download.resume", args, ct);
    public static Task<JsonElement> DownloadSetThreads(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("download.setThreads", args, ct);

    // ---- 设置与偏好 · prefs.* (30 条) ----
    public static Task<JsonElement> PrefsApplyPrefs(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("prefs.applyPrefs", args, ct);
    public static Task<JsonElement> PrefsCfProxyDisable(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("prefs.cfProxyDisable", args, ct);
    public static Task<JsonElement> PrefsCfProxyEnable(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("prefs.cfProxyEnable", args, ct);
    public static Task<JsonElement> PrefsCfProxyStatus(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("prefs.cfProxyStatus", args, ct);
    public static Task<JsonElement> PrefsCfSpeedTest(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("prefs.cfSpeedTest", args, ct);
    public static Task<JsonElement> PrefsConfigExportQr(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("prefs.configExportQr", args, ct);
    public static Task<JsonElement> PrefsConfigImportQr(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("prefs.configImportQr", args, ct);
    public static Task<JsonElement> PrefsBackupExport(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("prefs.backupExport", args, ct);
    public static Task<JsonElement> PrefsBackupImport(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("prefs.backupImport", args, ct);
    public static Task<JsonElement> PrefsBackupPreview(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("prefs.backupPreview", args, ct);
    public static Task<JsonElement> PrefsGetHomeSettings(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("prefs.getHomeSettings", args, ct);
    public static Task<JsonElement> PrefsGetPrefetchSettings(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("prefs.getPrefetchSettings", args, ct);
    public static Task<JsonElement> PrefsGetPrefs(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("prefs.getPrefs", args, ct);
    public static Task<JsonElement> PrefsGetPreloadSettings(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("prefs.getPreloadSettings", args, ct);
    public static Task<JsonElement> PrefsGetProxy(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("prefs.getProxy", args, ct);
    public static Task<JsonElement> PrefsGetUpdateSettings(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("prefs.getUpdateSettings", args, ct);
    public static Task<JsonElement> PrefsGetWritebackSettings(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("prefs.getWritebackSettings", args, ct);
    public static Task<JsonElement> PrefsIconLibrary(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("prefs.iconLibrary", args, ct);
    public static Task<JsonElement> PrefsSetIconSources(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("prefs.setIconSources", args, ct);
    public static Task<JsonElement> PrefsPreloadCancel(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("prefs.preloadCancel", args, ct);
    public static Task<JsonElement> PrefsPreloadItem(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("prefs.preloadItem", args, ct);
    public static Task<JsonElement> PrefsSetDetailBlur(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("prefs.setDetailBlur", args, ct);
    public static Task<JsonElement> PrefsSetHomeSettings(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("prefs.setHomeSettings", args, ct);
    public static Task<JsonElement> PrefsSetPrefetchSettings(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("prefs.setPrefetchSettings", args, ct);
    public static Task<JsonElement> PrefsSetPrefs(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("prefs.setPrefs", args, ct);
    public static Task<JsonElement> PrefsPushSearch(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("prefs.pushSearch", args, ct);
    public static Task<JsonElement> PrefsSetPreloadSettings(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("prefs.setPreloadSettings", args, ct);
    public static Task<JsonElement> PrefsSetProxy(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("prefs.setProxy", args, ct);
    public static Task<JsonElement> PrefsSetUpdateSettings(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("prefs.setUpdateSettings", args, ct);
    public static Task<JsonElement> PrefsSetWritebackSettings(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("prefs.setWritebackSettings", args, ct);

    // ---- 系统 · system.* (20 条) ----
    public static Task<JsonElement> SystemCacheSize(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("system.cacheSize", args, ct);
    public static Task<JsonElement> SystemCancelUpdate(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("system.cancelUpdate", args, ct);
    public static Task<JsonElement> SystemCapabilities(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("system.capabilities", args, ct);
    public static Task<JsonElement> SystemCheckUpdate(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("system.checkUpdate", args, ct);
    public static Task<JsonElement> SystemClearCache(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("system.clearCache", args, ct);
    public static Task<JsonElement> SystemDataPaths(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("system.dataPaths", args, ct);
    public static Task<JsonElement> SystemDownloadUpdate(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("system.downloadUpdate", args, ct);
    public static Task<JsonElement> SystemExportDiagnostics(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("system.exportDiagnostics", args, ct);
    public static Task<JsonElement> SystemInstallUpdate(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("system.installUpdate", args, ct);
    public static Task<JsonElement> SystemSendReport(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("system.sendReport", args, ct);
    public static Task<JsonElement> SystemOpenDataDir(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("system.openDataDir", args, ct);
    public static Task<JsonElement> SystemShortcutStatus(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("system.shortcutStatus", args, ct);
    public static Task<JsonElement> SystemMakeShortcut(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("system.makeShortcut", args, ct);
    public static Task<JsonElement> SystemPickDirectory(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("system.pickDirectory", args, ct);
    public static Task<JsonElement> SystemPickFile(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("system.pickFile", args, ct);
    public static Task<JsonElement> SystemPickLocalFolder(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("system.pickLocalFolder", args, ct);
    public static Task<JsonElement> SystemPing(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("system.ping", args, ct);
    public static Task<JsonElement> SystemUpdateProgress(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("system.updateProgress", args, ct);
    public static Task<JsonElement> SystemAfdianSponsorUrl(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("system.afdianSponsorUrl", args, ct);
    public static Task<JsonElement> SystemAfdianVerify(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("system.afdianVerify", args, ct);

    // ---- 手机扫码遥控(电视端) · companion.* (4 条) ----
    public static Task<JsonElement> CompanionStart(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("companion.start", args, ct);
    public static Task<JsonElement> CompanionStatus(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("companion.status", args, ct);
    public static Task<JsonElement> CompanionSetEnabled(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("companion.setEnabled", args, ct);
    public static Task<JsonElement> CompanionSetNowPlaying(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("companion.setNowPlaying", args, ct);

    // ---- 同步账号与追剧日历 · sync.* (14 条) ----
    public static Task<JsonElement> SyncTraktAccount(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("sync.traktAccount", args, ct);
    public static Task<JsonElement> SyncTraktDeviceCode(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("sync.traktDeviceCode", args, ct);
    public static Task<JsonElement> SyncTraktPoll(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("sync.traktPoll", args, ct);
    public static Task<JsonElement> SyncTraktLogout(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("sync.traktLogout", args, ct);
    public static Task<JsonElement> SyncTraktCalendar(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("sync.traktCalendar", args, ct);
    public static Task<JsonElement> SyncBangumiAccount(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("sync.bangumiAccount", args, ct);
    public static Task<JsonElement> SyncBangumiAuthorizeUrl(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("sync.bangumiAuthorizeUrl", args, ct);
    public static Task<JsonElement> SyncBangumiExchange(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("sync.bangumiExchange", args, ct);
    public static Task<JsonElement> SyncBangumiLoginToken(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("sync.bangumiLoginToken", args, ct);
    public static Task<JsonElement> SyncBangumiLogout(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("sync.bangumiLogout", args, ct);
    public static Task<JsonElement> SyncBangumiSummary(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("sync.bangumiSummary", args, ct);
    public static Task<JsonElement> SyncBangumiCalendar(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("sync.bangumiCalendar", args, ct);
    public static Task<JsonElement> SyncCalendarLibrary(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("sync.calendarLibrary", args, ct);
    public static Task<JsonElement> SyncCalendarDue(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("sync.calendarDue", args, ct);

    // ---- 插件 · plugin.* (48 条) ----
    public static Task<JsonElement> PluginList(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.list", args, ct);
    public static Task<JsonElement> PluginPendingRestart(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.pendingRestart", args, ct);
    public static Task<JsonElement> PluginInspect(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.inspect", args, ct);
    public static Task<JsonElement> PluginInstallFile(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.installFile", args, ct);
    public static Task<JsonElement> PluginInstallFromRepo(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.installFromRepo", args, ct);
    public static Task<JsonElement> PluginUninstall(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.uninstall", args, ct);
    public static Task<JsonElement> PluginCancelUninstall(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.cancelUninstall", args, ct);
    public static Task<JsonElement> PluginSetEnabled(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.setEnabled", args, ct);
    public static Task<JsonElement> PluginDisableAll(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.disableAll", args, ct);
    public static Task<JsonElement> PluginRestoreAll(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.restoreAll", args, ct);
    public static Task<JsonElement> PluginRollback(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.rollback", args, ct);
    public static Task<JsonElement> PluginSetLocked(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.setLocked", args, ct);
    public static Task<JsonElement> PluginSkipVersion(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.skipVersion", args, ct);
    public static Task<JsonElement> PluginDetail(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.detail", args, ct);
    public static Task<JsonElement> PluginSetSetting(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.setSetting", args, ct);
    public static Task<JsonElement> PluginClearData(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.clearData", args, ct);
    public static Task<JsonElement> PluginErrorDetail(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.errorDetail", args, ct);
    public static Task<JsonElement> PluginMarket(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.market", args, ct);
    public static Task<JsonElement> PluginRepos(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.repos", args, ct);
    public static Task<JsonElement> PluginAddRepo(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.addRepo", args, ct);
    public static Task<JsonElement> PluginRemoveRepo(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.removeRepo", args, ct);
    public static Task<JsonElement> PluginSetGithubPrefix(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.setGithubPrefix", args, ct);
    public static Task<JsonElement> PluginSetAutoUpdate(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.setAutoUpdate", args, ct);
    public static Task<JsonElement> PluginUpdates(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.updates", args, ct);
    public static Task<JsonElement> PluginUpdateAll(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.updateAll", args, ct);
    public static Task<JsonElement> PluginTakeovers(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.takeovers", args, ct);
    public static Task<JsonElement> PluginSetTakeover(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.setTakeover", args, ct);
    public static Task<JsonElement> PluginDevLoad(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.devLoad", args, ct);
    public static Task<JsonElement> PluginDevUnload(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.devUnload", args, ct);
    public static Task<JsonElement> PluginDevList(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.devList", args, ct);
    public static Task<JsonElement> PluginSetCapabilities(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.setCapabilities", args, ct);
    public static Task<JsonElement> PluginAppEvent(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.appEvent", args, ct);
    public static Task<JsonElement> PluginAnchors(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.anchors", args, ct);
    public static Task<JsonElement> PluginSettingsSections(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.settingsSections", args, ct);
    public static Task<JsonElement> SyncTraktRequest(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("sync.traktRequest", args, ct);
    public static Task<JsonElement> SyncBangumiRequest(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("sync.bangumiRequest", args, ct);
    public static Task<JsonElement> PlayerGetSubtitleText(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("player.getSubtitleText", args, ct);
    public static Task<JsonElement> PluginSetEnv(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.setEnv", args, ct);
    public static Task<JsonElement> PluginShellResult(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.shellResult", args, ct);
    public static Task<JsonElement> PluginPlayerSurfaces(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.playerSurfaces", args, ct);
    public static Task<JsonElement> PluginSidebar(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.sidebar", args, ct);
    public static Task<JsonElement> PluginBackRequest(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.backRequest", args, ct);
    public static Task<JsonElement> PluginPlayerKey(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.playerKey", args, ct);
    public static Task<JsonElement> PluginSetCookies(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.setCookies", args, ct);
    public static Task<JsonElement> PluginUiMount(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.ui.mount", args, ct);
    public static Task<JsonElement> PluginUiUnmount(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.ui.unmount", args, ct);
    public static Task<JsonElement> PluginUiEvent(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.ui.event", args, ct);
    public static Task<JsonElement> PluginUiViewport(this ILinPlayerCommands c, object? args = null, CancellationToken ct = default)
        => c.CallAsync("plugin.ui.viewport", args, ct);
}
