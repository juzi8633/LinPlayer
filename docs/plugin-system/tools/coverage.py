"""生成 docs/plugin-system/COVERAGE.md:每条决定(D1~Dn)在 SPEC 哪一章、API 哪个文件落地。

用法:python docs/plugin-system/tools/coverage.py
退出码非 0 = 有决定在 SPEC 里没有落点,或正文/API 引用了不存在的编号。
"""
import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REF = re.compile(r'D(\d{1,3})(?!\d)')
RANGE = re.compile(r'D(\d+)\s*~\s*D(\d+)')

# 没有 API 落点时,按它在 SPEC 的首个章节给出「为什么不需要 API」
WHY = {
    '01': '范围/原则,无接口',
    '02': '宿主架构内部实现',
    '03': '宿主强制的安全规则',
    '04': '宿主生命周期行为',
    '05': '运行时内部行为',
    '06': '宿主对贡献点的调度规则',
    '07': '壳/渲染器行为',
    '08': '宿主数据源页面行为',
    '09': '宿主播放器行为',
    '10': '宿主对提供者结果的处理',
    '11': '主题加载与宿主设置',
    '12': '宿主系统集成行为',
    '13': '宿主存储/同步/备份行为',
    '14': '插件页与市场 UI',
    '15': '官方仓库、CI 与 Pages 站',
    '16': '工具链与文档',
    '17': '官方插件内部实现',
    '18': '宿主内置功能',
    '19': '实施计划与验收',
    '20': '附录',
}


def refs(text):
    out = set(int(x) for x in REF.findall(text))
    for a, b in RANGE.findall(text):
        out.update(range(int(a), int(b) + 1))
    return out


def main():
    dec = open(os.path.join(ROOT, 'DECISIONS.md'), encoding='utf-8').read()
    rows = {int(m.group(1)): m.group(2) for m in re.finditer(r'^\| D(\d+) \| (.+?) \| \d{4}-\d{2}-\d{2} \|$', dec, re.M)}
    total = max(rows)
    spec, api = {}, {}
    for f in sorted(glob.glob(os.path.join(ROOT, 'spec', '*.md'))):
        ch = os.path.basename(f)[:2]
        for n in refs(open(f, encoding='utf-8').read()):
            spec.setdefault(n, []).append(ch)
    for f in sorted(glob.glob(os.path.join(ROOT, 'api', '**', '*.*'), recursive=True)):
        if f.endswith(('.ts', '.tsx', '.json')):
            name = os.path.relpath(f, os.path.join(ROOT, 'api')).replace('\\', '/')
            for n in refs(open(f, encoding='utf-8').read()):
                api.setdefault(n, []).append(name)

    bad = []
    missing = [n for n in range(1, total + 1) if n not in spec]
    unknown = sorted(n for n in set(spec) | set(api) if n not in rows)
    if missing:
        bad.append(f'SPEC 没有落点:{missing}')
    if unknown:
        bad.append(f'引用了不存在的编号:{unknown}')

    out = ['# 决定落点核对表', '',
           '> 由 `tools/coverage.py` 生成,不要手改。每条决定都必须在 SPEC 正文有落点;',
           '> 需要接口的决定在 `api/` 里有 `@see Dnnn`;没有接口的写明为什么不需要。', '',
           f'- 决定总数:{total}',
           f'- SPEC 有落点:{total - len(missing)}',
           f'- API 有落点:{len([n for n in range(1, total + 1) if n in api])}',
           f'- 结论:{"全部通过" if not bad else ";".join(bad)}', '',
           '| # | 决定(摘要) | SPEC 章 | API 落点 / 不需要 API 的原因 |', '|---|---|---|---|']
    for n in range(1, total + 1):
        text = re.sub(r'\*\*|`', '', rows.get(n, '?'))
        text = text.replace('|', '/')
        short = text if len(text) <= 60 else text[:60] + '…'
        chs = sorted(set(spec.get(n, [])))
        if n in api:
            where = ' '.join(sorted(set(api[n])))
        else:
            where = '—(' + WHY.get(chs[0], '?') + ')' if chs else '**缺**'
        out.append(f'| D{n} | {short} | {" ".join(chs) or "**缺**"} | {where} |')
    open(os.path.join(ROOT, 'COVERAGE.md'), 'w', encoding='utf-8').write('\n'.join(out) + '\n')
    print('\n'.join(out[6:10]))
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
