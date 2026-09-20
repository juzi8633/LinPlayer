#!/usr/bin/env python3
"""插件 UI 的三端一致门禁(SPEC 7.4 7.5 7.6,D41 D44 D319 D555)。

判据只有一条,但它是整章里最容易悄悄失守的一条:
**SDK 放行的东西,壳不许静默忽略。**

查四件事:
  1. `plugin-sdk.d.ts` 声明的每个组件,桌面与安卓渲染器都要**认得**。
     认不得的走 D319 的占位块 —— 那是给「老宿主 + 新插件」留的,
     不是给「我们自己还没写」留的。两者在截图上长得一模一样,所以只能靠这里分。
  2. 组件声明的**属性**也要有人接。组件名对上不代表能用:`Chip.selected`
     一端认一端不认,表现是「选中的和没选中的长得一样」,不报错。
  3. SPEC 7.6 的 transition / animation:要么两端都实现,要么别在 `.d.ts` 里放行。
  4. `.d.ts` 里的 hooks 要进 SDKHooks 名单,挂载那一侧按名单走(D514 的 hooks 那一半)。

用法:python scripts/check-plugin-ui.py
退出码非 0 = 有一端把某个组件或属性静默降级了。
"""
import pathlib
import re
import sys

# Windows 的 CI runner 默认按 cp1252 输出,打中文当场 UnicodeEncodeError(见 check-ffi-contract.py)
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

ROOT = pathlib.Path(__file__).resolve().parent.parent
GEN = ROOT / "core/plugin/rt/sdkspec_gen.go"
DTS = ROOT / "docs/plugin-system/api/plugin-sdk.d.ts"
DESKTOP = [
    ROOT / "apps/windows/LinPlayer.Desktop/Views/PluginUi.cs",
    ROOT / "apps/windows/LinPlayer.Desktop/Views/PluginUiComponents.cs",
]
ANDROID = [
    ROOT / "apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/plugin/PluginRender.kt",
    ROOT / "apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/plugin/PluginComponents.kt",
    ROOT / "apps/android/app/src/main/kotlin/xyz/linplayer/app/ui/plugin/PluginSurface.kt",
]

# token 表有**两份用处**,要分开查:一份是报给核心层的(plugin.setEnv),
# 一份是渲染器解 `token:名字` 用的。合成一个文件集去搜的话,
# 只要报的那一份里有这个名字,渲染器认不认得都看不出来 —— 实测注入过,门禁不红。
DESKTOP_ENV = [ROOT / "apps/windows/LinPlayer.Desktop/Views/PluginShell.cs"]
ANDROID_ENV = [ROOT / "apps/android/app/src/main/kotlin/xyz/linplayer/app/plugin/PluginEnv.kt"]
RT_UI = ROOT / "core/plugin/rt/ui.go"

# BaseProps / PressProps / FocusProps 这几个共用包里的属性名(plugin-sdk.d.ts 第 1209 行一带)
FOCUS = ["key", "focusable", "autoFocus", "focusGroup",
         "nextFocusUp", "nextFocusDown", "nextFocusLeft", "nextFocusRight", "a11yLabel"]
BUNDLES = {
    "BaseProps": ["style", "children"] + FOCUS,
    "PressProps": ["onPress", "onLongPress", "onFocus", "onBlur"],
    "FocusProps": FOCUS,
}

fail = []


def strip_comments(text):
    """注释里写一句「TODO Slider」不该让门禁变绿。"""
    text = re.sub(r"//.*", "", text)
    return re.sub(r"/\*.*?\*/", "", text, flags=re.S)


def read(paths):
    return "\n".join(strip_comments(p.read_text(encoding="utf-8")) for p in paths if p.exists())


def components():
    """SDKComponents —— 由 gen.mjs 从 .d.ts 生成,是组件名的唯一定义源。"""
    body = re.search(r"SDKComponents = \[\]string\{(.*?)\n\}", GEN.read_text(encoding="utf-8"), re.S)
    if not body:
        sys.exit("读不到 SDKComponents —— sdkspec_gen.go 的写法变了?")
    return re.findall(r'"([A-Za-z]+)"', body.group(1))


# 走不到属性流的:children / 子元素槽由 insert op 送(壳按父子关系摆),
# key 被 Preact 自己吃掉,压根不进 ops。
SKIP_TYPES = ("Child",)
# children / Child 槽由 insert op 送(壳按父子关系摆),key 被 Preact 自己吃掉;
# 下面这两个在**JS 那一侧**就消费掉了,壳从来收不到它们:
#   draw —— Canvas 在渲染期跑它,发给壳的是录好的 cmds(D104)
#   renderItem —— 虚拟列表只把窗口内那一段渲成子节点发过去(D134)
#   animate —— 逐帧由 JS 这边排(D105),壳收到的只是一帧又一帧的 cmds
SKIP_PROPS = {"children", "key", "draw", "renderItem", "animate"}

# 明确不接、而且在界面上**说清楚了**的。留在这里是为了它可被审 ——
# 从判据里悄悄删掉和静默忽略是一回事。
EXEMPT = {
    "Player": "视频层区域跟随是 D268 的未完成 spike;两端都认领了这个类型并画「不可用」",
}

# 组件在,但个别属性要等别的东西先落地。写在这儿是为了它可被审 ——
# 从判据里悄悄删掉和静默忽略是一回事。
EXEMPT_PROPS = {
    # seekable 只对「绑 mpv 的那一种」有意义,而 player 命名空间(SPEC 9)阶段 ③ 才做。
    # 现在实现的话拖动会送去一个不存在的播放器,那正是「摆着不生效的控件」(D562)。
    "ProgressBar.seekable",
}


def signature(dts, at):
    """从 `(p:` 的左括号扫到配对的右括号。

    不能用 `[^)]*` 那种写法:签名里有 `() => void` 这类嵌套括号,
    正则在第一个右括号就截断,后面的属性一条都抽不到 ——
    实测 41 个组件里有 18 个这么被漏掉,而门禁照样打印「属性都有人接」。
    """
    depth, i = 0, at
    while i < len(dts):
        if dts[i] == "(":
            depth += 1
        elif dts[i] == ")":
            depth -= 1
            if depth == 0:
                return dts[at + 1:i]
        i += 1
    return ""


def top_level_props(sig):
    """只取**属性包自己**那一层的键名。

    回调参数里的对象字面量(`onScroll?: (e: { x; y }) => void`)不是组件属性,
    算进去的话门禁会一直报 `ScrollView.x` 这种根本不存在的东西,
    而人会开始习惯性忽略它 —— 那比没有门禁更糟。
    做法:逐字符扫,只在「属性包大括号里、且不在任何括号里」时收键名。
    """
    names, depth, paren, buf, key = [], 0, 0, "", ""
    for ch in sig:
        if ch == "(":
            paren += 1
        elif ch == ")":
            paren -= 1
        elif ch == "{":
            depth += 1
            buf = ""
            continue
        elif ch == "}":
            if depth == 1 and paren == 0 and key:
                names.append((key, buf.strip()))
            depth -= 1
            key, buf = "", ""
            continue
        if depth == 1 and paren == 0:
            if ch == ":" and not key:
                m = re.search(r"(\w+)\??$", buf.strip())
                key = m.group(1) if m else ""
                buf = ""
            elif ch in ";,":
                if key:
                    names.append((key, buf.strip()))
                key, buf = "", ""
            else:
                buf += ch
    if key:
        names.append((key, buf.strip()))
    return names


def component_props(dts):
    """组件名 → 它声明的属性名。"""
    out = {}
    for m in re.finditer(r"export declare function ([A-Z]\w*)\(p: ", dts):
        name = m.group(1)
        if name in EXEMPT:
            continue
        sig = signature(dts, m.end() - 4)
        names = [k for k, typ in top_level_props(sig) if typ.strip() not in SKIP_TYPES]
        for bundle, props in BUNDLES.items():
            if bundle in sig:
                names += props
        out[name] = sorted(set(names) - SKIP_PROPS)
    return out


def arms(text, pattern):
    """switch/when 的分支标签上出现过的字符串。"""
    got = set()
    for arm in re.findall(pattern, text):
        got |= set(re.findall(r'"([A-Za-z#]+)"', arm))
    return got


def main():
    comps = components()
    if len(comps) < 30:
        sys.exit("只读到 %d 个组件 —— 生成器八成坏了" % len(comps))

    desk_text, andr_text = read(DESKTOP), read(ANDROID)
    # 桌面:`"View" or "Column" => ...`;安卓:`"Row", "ChipGroup" -> ...`
    shells = (
        ("桌面", desk_text, arms(desk_text, r'((?:"[A-Za-z#]+"\s*(?:or\s*)?)+)=>')),
        ("安卓", andr_text, arms(andr_text, r'((?:"[A-Za-z#]+"\s*,?\s*)+)->')),
    )

    # 1. 组件名
    for name, _, got in shells:
        missing = sorted(c for c in comps if c not in got)
        if missing:
            fail.append("%s渲染器认不得 %d/%d 个组件,会画成「需要更新 LinPlayer」占位:\n    %s"
                        % (name, len(missing), len(comps), " ".join(missing)))
        else:
            print("  ✓ %s:%d/%d 个组件都有原生实现" % (name, len(comps), len(comps)))

    # 2. 组件属性
    decls = component_props(DTS.read_text(encoding="utf-8"))
    for name, text, _ in shells:
        missing = []
        for comp, props in decls.items():
            missing += ["%s.%s" % (comp, p) for p in props
                        if '"%s"' % p not in text and "%s.%s" % (comp, p) not in EXEMPT_PROPS]
        missing = sorted(set(missing))
        if missing:
            fail.append("%s渲染器认不得这 %d 个属性(SDK 放行了,壳静默忽略):\n    %s"
                        % (name, len(missing), " ".join(missing)))
        else:
            print("  ✓ %s:声明的组件属性都有人接" % name)

    # 2.5 主题 token 名(SPEC 20.4,D556):两端都要认得那一套名字。
    #     ☠ 桌面查不到 token 时返回的是**透明** —— 那段文字整个看不见,不报错;
    #       安卓回落默认色。而 SDK 里写的是 `token:color.ink2`,
    #       壳只认旧的 `Ink2` 的话,示例页自己就踩上了。
    spec = (ROOT / "docs/plugin-system/spec/20-appendix.md").read_text(encoding="utf-8")
    tokens = sorted(set(re.findall(
        r"`(color\.\w+|radius\.\w+|space\.\w+|font\.size\.\w+|motion\.duration\.\w+)`", spec)))
    if len(tokens) < 20:
        fail.append("从 SPEC 20.4 只读到 %d 个 token 名 —— 那张表的写法变了,这一关等于没跑" % len(tokens))
    else:
        for name, text in (("桌面渲染器", desk_text), ("安卓渲染器", andr_text)):
            miss = [t for t in tokens if ('"%s"' % t) not in text]
            if miss:
                fail.append("%s认不得这 %d 个主题 token(SPEC 20.4 D556),样式里写 token:名字 会落空:\n    %s"
                            % (name, len(miss), " ".join(miss)))
            else:
                print("  ✓ %s:%d 个主题 token 都认得" % (name, len(tokens)))
        # 报给核心层的那一侧要**引用**渲染器那张表,不是自己再抄一份 ——
        # 抄一份的话改一个名字只会有一处失效,而那一处的表现只是「颜色不对」
        for name, text, ref in (("桌面", read(DESKTOP_ENV), "PluginTokens"),
                                ("安卓", read(ANDROID_ENV), "TOKEN_COLOR_NAMES")):
            if ref not in text:
                fail.append("%s报给核心层的那份 token 表没有引用渲染器的 %s —— "
                            "两张表会分叉,而分叉的表现只是「颜色不对」" % (name, ref))
            else:
                print("  ✓ %s:报给核心层的表和渲染器用的是同一张" % name)

    # 3. 动效(SPEC 7.6)
    for key in ("transition", "animation"):
        gaps = [n for n, text, _ in shells if '"%s"' % key not in text]
        if gaps:
            fail.append("SPEC 7.6 的 style.%s 在 %s 没有实现 —— 插件写了动效连一条 warn 都收不到"
                        % (key, "/".join(gaps)))
        else:
            print("  ✓ style.%s:两端都实现了" % key)

    # 4. hooks(D514 的另一半)。「每个 hook 真能调」由 rt/sdk_contract_test.go 在真运行时里验,
    #    那才是判据;这里只挡「名单没生成」和「挂载不按名单走」这两种把门禁架空的改法。
    declared = re.findall(r"export declare function (use[A-Z]\w*)", DTS.read_text(encoding="utf-8"))
    listed = set(re.findall(r'"(use[A-Z]\w*)"', GEN.read_text(encoding="utf-8")))
    missing = sorted(set(declared) - listed)
    if missing:
        fail.append("`.d.ts` 声明的 hook 没进 SDKHooks,门禁看不见它们:\n    %s\n    跑 `pnpm --dir tools/sdkgen gen`"
                    % " ".join(missing))
    elif "range SDKHooks" not in RT_UI.read_text(encoding="utf-8"):
        fail.append("rt/ui.go 不再按 SDKHooks 挂 hook —— 名单和挂载脱钩,少挂一个没人看得见")
    else:
        print("  ✓ hooks:%d 个都在 SDKHooks 名单里,挂载按名单走" % len(declared))

    if fail:
        print()
        for f in fail:
            print("  ✗ " + f)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
