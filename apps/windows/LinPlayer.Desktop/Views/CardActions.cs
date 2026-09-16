using System;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;
using Avalonia.Media.Transformation;
using Avalonia.Threading;
using LinPlayer.Core;
using LinPlayer.Desktop.Core;

namespace LinPlayer.Desktop.Views;

/// <summary>
/// 卡片动作:标记已看 / 收藏 / 屏蔽。一处实现,所有卡片共用。
///
/// <para>这几个动作在首页、媒体库网格、收藏、详情页的分集里都要有 —— 各处各写
/// 一份的表现是「某个入口少一项」,而少的那一项永远是最后加的那个。
/// 参数名照核心层原样:收藏是 <c>fav</c> 不是 <c>favorite</c>,屏蔽是 <c>id</c>/<c>blocked</c>。
/// 写错了不报错,布尔默认成 false —— 表现是「点收藏反而取消了收藏」。</para>
/// </summary>
public static class CardActions
{
    /// <summary>
    /// 给一张卡挂右键菜单。<paramref name="after"/> 在动作成功后调,用来刷新。
    ///
    /// <para>菜单是右键那一下才建的,不是造卡时就建 —— 一个 <see cref="ContextMenu"/>
    /// 是个弹出宿主,不是轻量对象;一屏 140 张卡就是 140 个宿主 + 200 多个菜单项,
    /// 而其中被打开过的是 0 个。建好之后留住:菜单项里的「标记为已看 / 未看」
    /// 是有状态的,每次重建会把它抹掉。</para>
    /// </summary>
    public static void Attach(Control host, CoreClient core, CardItem item, Action<string>? after = null)
    {
        after ??= DefaultAfter;
        host.ContextRequested += (_, e) =>
        {
            if (host.ContextMenu is not null) return; // 已经建过了,让它自己弹
            host.ContextMenu = Build(core, item, after);
            /* 这一次的右键要**自己补开一次**:ContextMenu 是在事件处理当中才挂上去的,
               挂之前那一下已经走过「有没有菜单」的判断了。不补的话第一次右键没反应,
               第二次才出来 —— 而用户只会认为右键坏了。 */
            e.Handled = true;
            host.ContextMenu.Open(host);
        };
    }

    /// <summary>
    /// 右键动作做完之后的默认收尾:<b>重建当前这一页</b>。
    ///
    /// <para>三个 <see cref="Attach"/> 调用点<b>一个都没传 after</b>,而 <see cref="Run"/> 里是
    /// <c>after?.Invoke()</c> —— 于是「标记为已播放」点下去:命令真发了、Toast 也弹了,
    /// 而<b>卡片纹丝不动</b>(绿勾和未看数是建卡那一刻画的)。看不出生效,
    /// 在用户眼里和这个选项不存在是同一回事。</para>
    /// <para>失败时不重建:Toast 已经说了原因,重建一页只会把它抹掉。</para>
    /// </summary>
    private static void DefaultAfter(string err)
    {
        if (err.Length > 0) return;
        Dispatcher.UIThread.Post(() => { if (Nav.CanReload) Nav.Reload(); });
    }

    /// <summary>
    /// 菜单弹出的入场动效。用户 2026-09-04:「右键菜单没有动效,没有小图标,看着生硬」。
    ///
    /// <para>挂在 <c>Opened</c> 上而不是写进样式表:Avalonia 的 ContextMenu 弹出时
    /// 另开一个 PopupRoot,样式里的 <c>:open</c> 伪类<b>触发不到它</b> ——
    /// 写了不生效,而且不报错。</para>
    ///
    /// <para>90ms + 往下 6px:菜单是**跟手**的东西,再长一点就成了「卡了一下」。
    /// 位移朝下 —— 菜单是从鼠标那一点长出来的。</para>
    /// </summary>
    internal static void AnimateMenu(ContextMenu menu) => Animate(menu);

    private static void Animate(ContextMenu menu)
    {
        menu.Transitions =
        [
            new Avalonia.Animation.DoubleTransition
            {
                Property = Visual.OpacityProperty,
                Duration = TimeSpan.FromMilliseconds(90),
                Easing = new Avalonia.Animation.Easings.CubicEaseOut(),
            },
            new Avalonia.Animation.TransformOperationsTransition
            {
                Property = Visual.RenderTransformProperty,
                Duration = TimeSpan.FromMilliseconds(90),
                Easing = new Avalonia.Animation.Easings.CubicEaseOut(),
            },
        ];
        menu.Opened += (_, _) =>
        {
            menu.Opacity = 0;
            menu.RenderTransform = TransformOperations.Parse("translateY(-6px)");
            // 下一帧再改终值:同一帧里设初值和终值,过渡读不到「变过」,一下就跳到位。
            Dispatcher.UIThread.Post(() =>
            {
                menu.Opacity = 1;
                menu.RenderTransform = TransformOperations.Parse("translateY(0px)");
            }, DispatcherPriority.Render);
        };
    }

    /// <summary>菜单项左边那个小图标。<b>Segoe MDL2</b>,和侧栏、播放页 OSD 同一套字形。</summary>
    private static TextBlock Icon(string glyph) => new()
    {
        Text = glyph, FontFamily = Glyph.Font, FontSize = 13,
        Foreground = new SolidColorBrush(Color.FromRgb(0xa8, 0xb0, 0xc0)),
    };

    /// <summary>MDL2 字形。 集中一处写:散在下面的 new MenuItem 里的话,
    /// 「这一项忘了给图标」会静默漏掉 —— 缺图标和缺一个空格看起来一样。</summary>
    internal static class G
    {
        public const string Unblock = "";  // 眼睛 —— 恢复显示
        public const string Detail = "";   // i —— 查看详情
        public const string Series = "";   // 胶片 —— 转到剧集
        public const string Play = "\uE768";
        public const string Replay = "\uE72C";   // 从头播放
        public const string Played = "\uE73E";   // 打勾
        public const string Unplayed = "\uE739"; // 空框
        public const string Fav = "\uE734";      // 空心星
        public const string FavOn = "\uE735";    // 实心星
        public const string Block = "\uE711";    // 叉
        public const string Download = "\uE896";  // 下箭头

        /// <summary>自检用:全表。字体里没这个码位时画出来是个空心方框,而它编译绿、运行不报错。</summary>
        public static readonly (string Name, string Glyph)[] All =
        [
            ("播放", Play), ("从头播放", Replay), ("已播放", Played), ("未播放", Unplayed),
            ("收藏", Fav), ("已收藏", FavOn), ("屏蔽", Block), ("恢复显示", Unblock),
            ("查看详情", Detail), ("转到剧集", Series), ("下载", Download),
        ];
    }

    /// <summary>右键菜单能直接播的类型。剧 / 季点「播放」不知道该播哪一集,交给详情页。</summary>
    private static bool Playable(string type) =>
        type is "Movie" or "Episode" or "Video" or "MusicVideo";

    /// <summary>这张卡是不是一个**媒体库**(而不是一部片子)。判据和 <see cref="LibraryPage.OpenDetail"/> 一致。</summary>
    internal static bool IsLibrary(string type) =>
        type is "CollectionFolder" or "UserView" or "Folder";

    /// <summary>
    /// 当前被屏蔽的媒体库 id。
    ///
    /// <para>放静态是因为**画卡那一刻**就要知道(灰不灰是建卡时画上去的,不是绑定),
    /// 而卡是 <see cref="MediaGrid"/> 在虚拟化回调里现造的 —— 把一个谓词从
    /// 媒体库页穿过 Grid、MediaGrid、Card 三层传下去,只为传一个全局事实。
    /// 写入只有两处:媒体库页拉名单时(Paint 之前)、以及下面那条菜单(UI 线程),
    /// 读只在建卡时。<see cref="Features"/> 也是同样的静态查表。</para>
    /// </summary>
    internal static readonly HashSet<string> BlockedLibraries = [];

    /// <summary>
    /// 建一张卡的右键菜单,按 Emby 自己那份排(用户 2026-09-03:「对齐 Emby,
    /// 方便观看的人标记等等;user / admin 的右键操作我们不做」)。
    ///
    /// <para>对照:播放 / 从头播放 —— 补上了(原来只有一条播放);标记已播放 —— 本来就有;
    /// 收藏 —— 原来是一条不带状态的「添加」,现在先按列表状态摆好再异步问准;
    /// 添加到播放列表 —— 不做,核心层根本没有播放列表,画一条点了没反应的更糟;
    /// 编辑元数据 / 删除 —— 用户点名不做。「从头播放」只在真看过一半时才画。</para>
    /// </summary>
    private static ContextMenu Build(CoreClient core, CardItem item, Action<string>? after)
    {
        var menu = new ContextMenu();
        Animate(menu);
        var items = new List<Control>();

        /* ☠ **屏蔽只对媒体库**(用户 2026-09-16:「首页的右键屏蔽条目不知道你是
           哪里看到的,Emby 官方网页端都没有这个东西。屏蔽只出现在媒体库页:
           屏蔽某个媒体库之后不参与检索,该媒体库卡片样式变灰,再次右键选择恢复即可」)。
           所以条目卡的菜单里**没有**屏蔽这一项了 —— 下面也不再有那段代码。
           库卡另给一份菜单:库不能播、不能标已看、不能收藏,那几项摆上去
           点了只会报错。 */
        if (IsLibrary(item.Type))
        {
            var on = BlockedLibraries.Contains(item.Id);
            var toggle = new MenuItem
            {
                Header = on ? "恢复显示" : "屏蔽这个媒体库",
                Icon = Icon(on ? G.Unblock : G.Block),
            };
            toggle.Click += async (_, _) =>
            {
                var want = !BlockedLibraries.Contains(item.Id);
                /* 这一条**不走 after**:默认的 after 会立刻重建页面,而那时
                   BlockedLibraries 还没更新 —— 重建出来的仍是不灰的卡,
                   然后下面再重建一次。顺序有要求,所以自己收尾。 */
                var ok = await Run(core, "emby.setBlocked",
                    new { id = item.Id, name = item.Name, blocked = want }, null);
                Toast.Result(ok,
                    want ? $"已屏蔽「{item.Name}」,这个库不再参与检索"
                         : $"已恢复「{item.Name}」",
                    "操作失败");
                if (!ok) return;
                if (want) BlockedLibraries.Add(item.Id); else BlockedLibraries.Remove(item.Id);
                /* 卡片的灰是**建卡那一刻画上去的**,不是绑定 —— 要看见变化只能重造这一页。
                   不重造的话用户点完「屏蔽」界面上一点反应都没有,
                   而那和「点了没生效」长得一模一样。 */
                Dispatcher.UIThread.Post(() => { if (Nav.CanReload) Nav.Reload(); });
            };
            menu.ItemsSource = new List<Control> { toggle };
            return menu;
        }

        if (Playable(item.Type))
        {
            var play = new MenuItem
            {
                Header = item.ResumeSecs > 0 ? "继续播放" : "播放",
                Icon = Icon(G.Play),
            };
            play.Click += (_, _) => Play(core, item, item.ResumeSecs);
            items.Add(play);

            if (item.ResumeSecs > 0)
            {
                var fresh = new MenuItem { Header = "从头播放", Icon = Icon(G.Replay) };
                // 负数 = 明说从头放。传 0 是「交给核心层定」,会被服务端进度顶回续播
                fresh.Click += (_, _) => Play(core, item, -1);
                items.Add(fresh);
            }
            items.Add(new Separator());
        }

        /* 剧 / 季上这一条是**级联整部 / 整季**的:核心层打的是
           `POST /Users/{uid}/PlayedItems/{itemId}`,而 Emby 对 Series / Season
           会把底下所有分集一起标上。文案必须说出这件事 ——
           写「标记为已播放」而实际标掉了 40 集,用户不会知道自己刚干了什么,
           更不会知道还能一键标回来。 */
        var scope = item.Type switch
        {
            "Series" => "整部", "Season" => "整季", "BoxSet" => "整个合集", _ => "",
        };
        string PlayedText(bool done) => done ? $"标记{scope}为未播放" : $"标记{scope}为已播放";

        var played = new MenuItem
        {
            Header = PlayedText(item.Played),
            Icon = Icon(item.Played ? G.Unplayed : G.Played),
        };
        played.Click += async (_, _) =>
        {
            var want = played.Header as string == PlayedText(false);
            var ok = await Run(core, "emby.setPlayed", new { item_id = item.Id, played = want }, after);
            Toast.Result(ok,
                want ? $"已标记{scope}为已播放" : $"已标记{scope}为未播放", "标记失败");
            if (!ok) return;
            played.Header = PlayedText(want);
            played.Icon = Icon(want ? G.Unplayed : G.Played);
        };
        items.Add(played);

        /* 收藏和屏蔽跟着 Features 走,而且 card.block 必须和 set.blocked
           **成对**开关 —— 留着屏蔽却没有解除列表,用户屏蔽掉的东西再也找不回来。
           本仓的老规矩:隐藏类功能必须配集中解除列表。 */
        var fav = new MenuItem { Header = "添加到收藏", Tag = false, Icon = Icon(G.Fav) };
        fav.Click += async (_, _) =>
        {
            var want = fav.Tag is not true;
            /* <b>这里就是用户点名的那一处</b>(2026-09-04:「我在首页添加收藏
               就没有 toast 提示,我都不知道有没有加成功」)。菜单点完就关了、
               卡片上什么都不变 —— 成功和「什么都没发生」长得一模一样。 */
            var ok = await Run(core, "emby.setFavorite", new { item_id = item.Id, fav = want }, after);
            Toast.Result(ok, want ? "已添加到收藏" : "已从收藏中移除", "收藏操作失败");
            if (ok) Label(fav, want);
        };
        if (Features.On("card.favorite"))
        {
            items.Add(fav);
            /* 列表命令里<b>没有</b> is_favorite —— 那是 emby.itemDetail 才有的字段
               (给列表加这个字段要动 core 的输出形状,而 5 条差分对账语料录的正是
               那个形状,不能为了一句菜单文案去改黄金实现的对账基准)。
               所以状态在**右键那一下**才去问一次,回来再把文案改准。
               一次右键 = 一次小请求,而且只在用户真的右键时才发。
               没回来之前显示的是「添加到收藏」—— 猜错一次的代价是用户多点一下,
                 而反过来(默认显示「从收藏中移除」)会让人以为自己收藏过了。 */
            _ = SyncFavorite(core, item.Id, fav);
        }

        /* 下载(桌面草稿 03 页第 16 条:分集右键要有「下载本集」)。
           详情页那颗下载按钮一直都在,卡片右键里却没有 —— 而选集时想下一集,
           右键才是顺手的那一下,点进详情页再回来是两次跳转。 */
        if (Playable(item.Type))
        {
            var down = new MenuItem { Header = "下载", Icon = Icon(G.Download), IsVisible = false };
            down.Click += async (_, _) =>
            {
                // container 这儿拿不到(列表命令不发它)。空串 = 交给核心层兜底(默认 mkv),
                // 和详情页「取不到就交给核心层」同一个口径。
                // 剧名 / 季集号必须送:分集的 name 常常只是「第 12 集」,
                // 不送的话两部剧各下一集会撞成同一个文件(见 core 的 fileBase)。
                var ok = await Run(core, "download.enqueue",
                    new
                    {
                        item_id = item.Id, type_ = item.Type, title = item.Name, container = "",
                        series_name = item.SeriesName,
                        season_number = (long)item.SeasonNo, episode_number = (long)item.EpisodeNo,
                    }, after);
                Toast.Result(ok, "已加入下载", "加入下载失败");
            };
            items.Add(down);
            _ = ShowIfDownloadable(core, (Control)down);
        }

        /* 「查看详情」和「转到剧集」补上(用户 2026-09-16:「完善首页的右键的选项」)。
           Emby 网页端两条都有,而我们原来只能左键点卡进去 —— 在**列表模式**
           和详情页的分集行上尤其别扭:那两处的左键是直接起播,想看简介没有入口。 */
        items.Add(new Separator());
        var detail = new MenuItem { Header = "查看详情", Icon = Icon(G.Detail) };
        detail.Click += (_, _) =>
        {
            if (Nav.Session is { } s) LibraryPage.OpenDetail(core, s.server)(item);
        };
        items.Add(detail);

        // 只有分集才画。电影没有「所属剧」,而 series_id 为空时跳过去是一页空白
        if (item.Type == "Episode" && item.SeriesId.Length > 0)
        {
            var goSeries = new MenuItem
            {
                Header = item.SeriesName is { Length: > 0 } sn ? $"转到《{sn}》" : "转到剧集",
                Icon = Icon(G.Series),
            };
            goSeries.Click += (_, _) =>
            {
                if (Nav.Session is not { } s) return;
                Nav.Push(new DetailPage(core, s.server, item.SeriesId),
                    () => new DetailPage(core, s.server, item.SeriesId));
            };
            items.Add(goSeries);
        }

        menu.ItemsSource = items;
        return menu;
    }

    /// <summary>
    /// 下载权限问明白了再把那一条画出来。<b>能不能下载是服务端判的</b>,
    /// 不问就画等于摆一个必定失败的菜单项(详情页那颗下载按钮同一个口径)。
    ///
    /// <para>结果按服务器记一次:权限和条目无关,而右键二十张卡就是二十次同样的请求。
    /// 只在 UI 线程读写,不加锁。</para>
    /// </summary>
    private static readonly Dictionary<string, bool> DownloadOk = [];

    internal static async Task ShowIfDownloadable(CoreClient core, Control down)
    {
        var s = Nav.Session;
        if (s is null) return;
        if (!DownloadOk.TryGetValue(s.server, out var ok))
        {
            try
            {
                var perm = await core.EmbyPermissions(new { s.server, s.token, s.user_id, s.device_id });
                ok = perm.ValueKind == System.Text.Json.JsonValueKind.Object
                     && perm.TryGetProperty("can_download", out var v)
                     && v.ValueKind == System.Text.Json.JsonValueKind.True;
            }
            catch { return; } // 问不到权限就不画这一条 —— 宁可少给,也不摆一个必定失败的
            DownloadOk[s.server] = ok;
        }
        if (ok) Dispatcher.UIThread.Post(() => down.IsVisible = true);
    }

    /// <summary>收藏那一条的文案 + 状态。<b>状态存在 Tag 里,不从文案反推</b> —— 文案是给人看的。</summary>
    private static void Label(MenuItem fav, bool on)
    {
        fav.Tag = on;
        fav.Header = on ? "从收藏中移除" : "添加到收藏";
        fav.Icon = Icon(on ? G.FavOn : G.Fav);
    }

    /// <summary>问一次这条到底收藏了没有,把菜单文案改准。问不到就保持原样(不报错)。</summary>
    private static async Task SyncFavorite(CoreClient core, string id, MenuItem fav)
    {
        try
        {
            var s = Nav.Session!;
            var d = await core.EmbyItemDetail(new
            {
                s.server, s.token, s.user_id, s.device_id, item_id = id, with_children = false,
            });
            if (d.ValueKind == System.Text.Json.JsonValueKind.Object &&
                d.TryGetProperty("is_favorite", out var v))
                Avalonia.Threading.Dispatcher.UIThread.Post(
                    () => Label(fav, v.ValueKind == System.Text.Json.JsonValueKind.True));
        }
        catch { /* 问不到就让它停在「添加到收藏」——点一下照样是一次真切换 */ }
    }

    /// <summary>
    /// 从右键菜单直接起播。<b>走的是详情页主按钮同一句</b> ——
    /// <c>Nav.Push(new PlayerPage(...))</c>,不另起一条起播路径。
    ///
    /// <para>不带 mediaSourceId / 音轨 / 字幕:右键这条路上用户什么都没选,
    /// 空着就是「交给核心层按正则挑」,和详情页里一次都没动过下拉是同一种状态。</para>
    /// </summary>
    private static void Play(CoreClient core, CardItem item, double resume) =>
        Nav.Push(new PlayerPage(core, item.Id, item.DisplayTitle, resume));

    private static async Task<bool> Run(CoreClient core, string cmd, object args, Action<string>? after)
    {
        try
        {
            var s = Nav.Session!;
            var merged = Merge(s, args);
            await core.CallAsync(cmd, merged);
            after?.Invoke("");
            return true;
        }
        catch (Exception e)
        {
            after?.Invoke(LibraryPage.Advice(e));
            return false;
        }
    }

    /// <summary>
    /// 把会话四件套并进参数。
    ///
    /// <para>迁移期命令层还要显式收 server/token/user_id/device_id;
    /// 匿名类型合不了,所以走字典 —— 字典的键名就是线上字段名,不会被 C# 命名习惯带偏。</para>
    /// </summary>
    private static Dictionary<string, object?> Merge(Sess s, object args)
    {
        var d = new Dictionary<string, object?>
        {
            ["server"] = s.server, ["token"] = s.token,
            ["user_id"] = s.user_id, ["device_id"] = s.device_id,
        };
        foreach (var p in args.GetType().GetProperties()) d[p.Name] = p.GetValue(args);
        return d;
    }
}
