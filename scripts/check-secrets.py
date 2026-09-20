"""红线扫描:已跟踪文件里的真实 IP / 域名 / 账号 / 密钥。

从 stdin 读文件名(一行一个),命中就打印位置并以非零退出。

☠ 门禁只管**当前版本**。git 历史里已经有的东西它看不见也修不掉 ——
  历史要么改写要么删库重建,那是破坏性操作,由项目负责人决定
  (现状见 docs/lessons/red-line-audit.md)。

☠ 宁可漏报也不能滥报。第一版把所有后缀都当域名扫,`java.io` / `androidx.tv` /
  `xyz.linplayer.app` / `proguard-rules.pro` 全是命中,三千多条噪音里没人找得到
  真的那两条 —— 门禁就变成了「每次都红,于是每次都无视」。
"""

import hashlib
import io
import os
import re
import sys

sys.stdout.reconfigure(encoding="utf-8")

# ☠ 不许把 `ts` 放进来:本意是 MPEG-TS 切片,而它同时是**所有插件源码**的后缀。
# 放进来之后 plugins/ 下一整棵 TypeScript 全部不扫 —— 自测时往 .ts 里塞一条
# 假地址,门禁照样绿。真的 .ts 视频切片不会进仓库。
SKIP_EXT = re.compile(
    r"\.(png|jpg|jpeg|gif|webp|ico|svg|ttf|otf|woff2?|zip|gz|xz|apk|aab|dll|so|dylib"
    r"|exe|bin|jar|lock|sum|pdf|mp4|mkv|class|dex|kotlin_module)$",
    re.I,
)

# 公开厂商 API / 公开文档站 / 包管理源。白名单只进这一类。
HOST_ALLOW = re.compile(
    r"^(?:[a-z0-9-]+\.)*(?:"
    r"github\.com|githubusercontent\.com|github\.io|gitlab\.com|"
    r"golang\.org|go\.dev|rust-lang\.org|kotlinlang\.org|java\.net|android\.com|"
    r"apache\.org|mozilla\.org|w3\.org|whatwg\.org|json-schema\.org|"
    r"avaloniaui\.net|jetbrains\.com|gradle\.org|maven\.org|npmjs\.com|pnpm\.io|nodejs\.org|"
    r"microsoft\.com|dot\.net|nuget\.org|python\.org|pypi\.org|"
    r"mpv\.io|ffmpeg\.org|videolan\.org|gyan\.dev|evermeet\.cx|huggingface\.co|"
    r"emby\.media|jellyfin\.org|kodi\.wiki|trakt\.tv|bgm\.tv|bangumi\.tv|"
    r"themoviedb\.org|tmdb\.org|dandanplay\.(?:com|net)|mikanani\.me|bilibili\.com|"
    r"baidu\.com|anthropic\.com|openai\.com|sentry\.io|cloudflare\.com|pages\.dev|"
    r"workers\.dev|afdian\.com|afdian\.net|telegram\.org|t\.me|creativecommons\.org|"
    r"unicode\.org|iana\.org|gnu\.org|fsf\.org|shields\.io|opensource\.org|"
    r"example\.(?:com|org|net)"
    r")$",
    re.I,
)

# 占位符与保留后缀
PLACEHOLDER = re.compile(r"(?:^|\.)(?:example|invalid|test|local|lan|localhost)$", re.I)

# 测试夹具里的占位主机。写法沿用 scripts/extract-lore.py 的白名单,免得两处判得不一样。
FIXTURE = re.compile(
    r"^(?:[abcdhxk]|cdn\d?|d\d|old-a|evil|ok|official|my-cdn|host|srv)"
    r"(?:\.(?:com|net|org|lan|local))?$",
    re.I,
)

IP_ALLOW = {
    "0.0.0.0", "127.0.0.1", "255.255.255.255", "255.255.255.0",
    "10.0.2.2",  # 安卓模拟器访问宿主机的固定保留地址
    "1.2.3.4", "8.8.8.8", "1.1.1.1",  # 文档与测试里的公知示例
}

URL_HOST = re.compile(r"https?://([a-zA-Z0-9.\-]+)")

# 不带协议头的主机只查**廉价个人域名后缀**:真实基础设施地址基本都带协议头
# (URL_HOST 管)或者是 IP(IPV4 管),而这些后缀是个人自建站的主力。
# 裸主机只查廉价个人域名后缀,而且**要求域名里带数字或连字符**。
# 不要求的话 Kotlin 的 `nav.top` / `Gravity.TOP` / `2.dp.toPx` / `source.link`
# 全是命中 —— 这些后缀同时是一大堆属性名。带数字/连字符是这类站点的常态
# (902541.xyz 这种),而属性名极少这么写。
# 代价:纯字母的个人域名(如 `mysite.top`)漏报 —— 它们多半以 URL 形式出现,URL_HOST 管得到。
BARE_HOST = re.compile(
    r"([a-z0-9]*[\d-][a-z0-9-]*(?:\.[a-z0-9-]+)*\.(?:xyz|top|vip|club|site|online|shop|link|tk|lol|icu|buzz|cyou))",
)

IPV4 = re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b")

# 凭据只认「右边像个值」的写法。`password = password`(具名实参)、
# `password = pass.Text`(取控件内容)是代码不是凭据 —— 算进来的话每个登录页都红。
CRED = re.compile(
    r"(?:root@[a-z0-9.\-]{3,}"
    r"|\b(?:password|passwd|api[_-]?key|secret|access[_-]?token|bearer)\s*[=:]\s*"
    r"[\"']([A-Za-z0-9_\-/+]{12,})[\"'])",
    re.I,
)

# 版本号会撞 IPv4 的形状(1.0.0.0 / 4.9.3.1)。
VERSIONISH = re.compile(r"version|assembly|semver|\bv\d", re.I)

BASE_HEADER = "\n".join([
    "# 红线欠账清单（门禁做出来之前就在仓库里的）。",
    "# 格式：文件<TAB>值的短哈希。**不写值本身** ——",
    "# 把散在各处的泄漏汇总到一个文件里等于再泄一次，而且更好找",
    "#（docs/lessons/red-line-audit.md 开头就是这么写的，第一版清单正好犯了它）。",
    "# 要看是哪一处：bash scripts/check-secrets.sh 会打出文件与行号。",
    "# 这些**不是被赦免了**，是等项目负责人裁决。",
    "",
])


def digest(v):
    return hashlib.sha1(v.lower().encode("utf-8")).hexdigest()[:12]


def ip_is_versionish(ip, line):
    parts = [int(x) for x in ip.split(".")]
    if VERSIONISH.search(line) and all(p <= 99 for p in parts):
        return True
    # 四段都极小(1.0.0.0 这种),现实里不是地址
    return all(p <= 9 for p in parts)


def host_ok(host):
    h = host.strip(".").lower()
    if not h:
        return True
    if re.fullmatch(r"[\d.]+", h):
        return True  # 纯数字:版本号
    if "." not in h:
        return True  # 单段主机名不是域名(http://a 这种夹具)
    if FIXTURE.match(h) or PLACEHOLDER.search(h) or HOST_ALLOW.match(h):
        return True
    return False


def load_allow(path):
    """豁免名单。格式:`值  # 理由`。**没写理由的不算豁免**。"""
    out = {}
    if not path or not os.path.exists(path):
        return out
    for line in io.open(path, encoding="utf-8"):
        s = line.strip()
        if not s or s.startswith("#"):
            continue
        if "#" not in s:
            print("!! 豁免名单里这一行没写理由,不算豁免:" + s)
            continue
        val, why = s.split("#", 1)
        if val.strip():
            out[val.strip().lower()] = why.strip()
    return out


def load_baseline(path):
    out = set()
    if not path or not os.path.exists(path):
        return out
    for line in io.open(path, encoding="utf-8"):
        s = line.rstrip("\n")
        if not s.strip() or s.startswith("#"):
            continue
        parts = s.split("\t")
        if len(parts) >= 2:
            out.add((parts[0], parts[1].lower()))
    return out


def scan(files, allow):
    hits = []
    for f in files:
        if SKIP_EXT.search(f) or not os.path.exists(f):
            continue
        if f.replace("\\", "/").endswith("scripts/secrets-baseline.txt"):
            continue  # 清单本身不扫(它只存哈希,但扫它没有意义)
        try:
            text = io.open(f, encoding="utf-8", errors="ignore").read()
        except OSError:
            continue
        for i, line in enumerate(text.splitlines(), 1):
            for m in URL_HOST.finditer(line):
                h = m.group(1)
                if not host_ok(h) and h.lower() not in allow:
                    hits.append((f, i, h))
            for m in BARE_HOST.finditer(line):
                h = m.group(1)
                if not host_ok(h) and h.lower() not in allow:
                    hits.append((f, i, h))
            for m in IPV4.finditer(line):
                ip = m.group(0)
                if ip in IP_ALLOW or ip.lower() in allow or ip_is_versionish(ip, line):
                    continue
                hits.append((f, i, ip))
            for m in CRED.finditer(line):
                val = m.group(0)[:40]
                if val.lower() not in allow:
                    hits.append((f, i, val))
    return hits


def main():
    allow_path = sys.argv[1] if len(sys.argv) > 1 else ""
    base_path = sys.argv[2] if len(sys.argv) > 2 else ""
    write_base = "--write-baseline" in sys.argv

    allow = load_allow(allow_path)
    files = [f.strip() for f in sys.stdin.read().splitlines() if f.strip()]
    hits = scan(files, allow)

    if write_base:
        rows = sorted({(f, digest(v)) for f, _, v in hits})
        with io.open(base_path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(BASE_HEADER)
            for f, v in rows:
                fh.write(f + "\t" + v + "\n")
        print("已写出欠账清单:" + base_path + "(" + str(len(rows)) + " 条)")
        return 0

    baseline = load_baseline(base_path)
    fresh = [(f, i, v) for f, i, v in hits if (f, digest(v)) not in baseline]
    old = len(hits) - len(fresh)

    if not fresh:
        print("红线门禁:" + str(len(files)) + " 个文件,没有**新增**的真实地址 / 凭据。")
        if old:
            print("  欠账清单里还有 " + str(old) + " 处(等裁决,见 docs/lessons/red-line-audit.md)")
        return 0

    print("红线门禁:发现 " + str(len(fresh)) + " 处**新增**可疑值(欠账清单里另有 " + str(old) + " 处)。")
    seen = set()
    for f, i, v in fresh[:80]:
        if (f, v) in seen:
            continue
        seen.add((f, v))
        print("  " + f + ":" + str(i) + "  " + v)
    if len(fresh) > 80:
        print("  …还有 " + str(len(fresh) - 80) + " 处")
    return 1


if __name__ == "__main__":
    sys.exit(main())
