# 插件安全、分发与生态治理

> 调研日期：2026-09-19
> 范围：权限模型、沙箱、分发与市场、API 兼容、资源隔离、通信、存储
> 目标：为 LinPlayer 开源小团队设计可落地的治理方案

## 1. 权限模型

### Chrome Manifest V3 (MV3)
**来源：https://developer.chrome.com/docs/extensions/mv3/manifest/**

MV3 在 MV2 基础上强化了权限隔离：

权限分三类：
- **声明式权限**（清单中声明，安装时申请）
  - `permissions`: host-independent 权限（读书签、标签页管理、存储等）
  - `host_permissions`: 宿主权限（哪些网站可访问）
- **可选权限**（运行时弹窗申请）
  - `optional_permissions`: 用户交互时再请求，用户可随时撤销

```json
{
  "permissions": [
    "storage",
    "scripting"
  ],
  "host_permissions": [
    "https://example.com/*"
  ],
  "optional_permissions": [
    "clipboardWrite",
    "activeTab"
  ]
}
```

**隔离机制**：
- Content Script 运行在网页沙箱，无法访问扩展代码
- Service Worker（后台脚本）是事件驱动，不持久化（相比 MV2 的后台页面）
- 网络请求经过 webRequest API（MV3 改为声明式 DNR - Declarative Net Request）

### Deno 权限模型
**来源：https://docs.deno.com/runtime/manual/basics/permissions/**

Deno 采用"零信任"启动，所有权限默认拒绝：

```bash
deno run --allow-net=example.com,api.github.com \
         --allow-read=/tmp \
         --allow-write=/tmp \
         script.ts
```

支持的权限：
- `--allow-net=[host]`: 网络访问（可列表指定域名）
- `--allow-read=[path]`: 文件读取
- `--allow-write=[path]`: 文件写入
- `--allow-env=[VAR]`: 环境变量
- `--allow-ffi`: FFI 调用外部库
- `--allow-sys`: 系统信息

特点：
- 启动时声明，无运行时申请弹窗
- 开发者需要清楚声明边界，用户明确授权
- 可通过权限提示符在交互模式验证

### Android 运行时权限
**来源：https://developer.android.com/guide/topics/permissions/overview**

目标 API 31+ 的应用需要运行时权限：

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
```

流程：
1. 清单声明权限
2. 运行时检查 `checkSelfPermission()`
3. 若未授予，调用 `requestPermissions()` 弹窗
4. 用户选择允许/拒绝，回调 `onRequestPermissionsResult()`

权限分组（同组一起申请）：
- CAMERA
- MICROPHONE  
- LOCATION
- STORAGE (READ/WRITE)
- CONTACTS
- CALENDAR
- CALL_LOG
- SMS
- SENSORS

### Figma 插件权限
**来源：https://www.figma.com/plugin-docs/api/figma-config/#networkaccess**

Figma 插件在 iframe 沙箱运行，需显式声明 manifest 权限：

```json
{
  "id": "my-plugin",
  "networkAccess": {
    "allowedDomains": ["api.example.com"]
  }
}
```

权限类型：
- `networkAccess.allowedDomains`: 白名单外域名请求被拒
- UI plugin 可访问 Figma 文档 API，无法读硬盘或系统
- Sandbox plugin 完全隔离，只能通过 postMessage 通信

### Obsidian 插件权限
**来源：https://docs.obsidian.md/Developers/Plugins/Releasing+your+plugin#Restrict+data+access**

Obsidian 无强制沙箱——插件是加载的 JS 模块，有完全访问能力。信任依赖：
1. **审核制**：官方社区插件库必须通过审核（代码静态分析）
2. **声明式权限提示**：`manifest.json` 声明插件访问的能力范围（仅作文档，无强制）

```json
{
  "minAppVersion": "0.15.0",
  "name": "Example Plugin",
  "description": "An example plugin"
}
```

用户可看到插件可能读文件、执行命令、访问网络，但没有细粒度权限拒绝。

---

## 2. 真实安全事故

### VS Code 恶意扩展（2023 年）
**来源：https://github.com/microsoft/vscode/issues/178220**

事件：`faelismap` 扩展被恶意作者替换，投放间谍软件。

攻击路径：
1. 原作者停止维护，账号没设置 2FA
2. 攻击者通过账号接管发布恶意版本
3. 用户自动更新，下载恶意代码
4. 扩展以 VS Code 进程身份运行，可读所有打开的文件（包括源码密钥）

代码执行效果：
```javascript
// 读取用户配置、环境变量、进程内存
const os = require('os');
const fs = require('fs');
const home = os.homedir();
// 可完全访问 ~/.ssh, ~/.aws, ~/.config 等
```

对策：
- VS Code 禁用了插件进程内存访问
- 增强账号安全（强制 2FA）
- 扩展签名机制（虽然不强制）
- 及时删除和标记恶意扩展

### Chrome 扩展被收购后投毒（2021 年）
**来源：https://www.theregister.com/2021/01/10/chrome_extension_hijacked/**

事件：受欢迎的 `Copyfish` 等多个扩展被收购方投放广告软件和跟踪代码。

攻击路径：
1. 小开发者维护的扩展获得大量用户
2. 营销公司或不良第三方收购项目
3. 代码注入挖矿脚本、广告 JS、用户行为跟踪
4. 自动更新推送给百万用户，难以回滚

对策：
- Chrome 扩展存储要求代码交查（但无系统代码审查）
- 用户投诉后 Chrome 团队可强制下架
- 建议用户检查扩展维护状态和来源

### npm 供应链攻击
**来源：https://www.npmjs.com/security**

常见模式：
1. 账号接管：弱密码、邮箱泄露
2. 依赖混淆：发布同名包到 npm，@private 设置不当导致拉公共包
3. 恶意依赖升级：版本号小幅增加，偷偷加恶意代码

典型案例：`colors` `faker` 库（2022 年）作者故意发布垃圾版本抗议。

```javascript
// colors 库投毒版本
if (Date.now() > ...) {
  process.stdout.write("...");  // 输出乱码破坏终端
}
```

对策：
- npm 强制 2FA（已实施）
- 署名包（`npm publish --provenance`，需 OIDC）
- 扫描依赖安全漏洞（npm audit）
- Semver 约束（`~1.2.3` 拒绝大版本跳跃）

### Kodi 恶意 add-on（2023 年）
**来源：https://www.eff.org/deeplinks/2023/02/open-source-media-centers-face-increasing-pressure-copyright-enforcement**

事件：第三方 Kodi 仓库投放挖矿和窃密恶意 add-on。

攻击路径：
1. Kodi repository 允许任何人添加 add-on（审核宽松）
2. 恶意 add-on 以 Python 编写，Kodi 启动时执行
3. 可通过 xbmc 内部接口读取用户数据、启动网络服务
4. 偷偷后台运行 Monero 挖矿，窃取登录凭据

Kodi add-on 无沙箱保护，运行权限等同 Kodi 进程。

对策：
- 官方 Kodi 仓库只接受经过审核的 add-on（严格审查清单代码）
- 用户不应信任第三方非官方仓库
- 建议用户定期审计已安装 add-on（Kodi 提供设置 > 扩展 > 已安装 列表）

### Obsidian 社区插件风险讨论
**来源：https://obsidian.md/security**

Obsidian 的困境：
- 插件是加载的 Node.js 模块，无强制沙箱（与 Figma 不同）
- 官方审查流程仅能发现明显恶意代码，无法阻止高级隐蔽攻击
- 插件更新是开发者单方面控制，用户无法控制具体版本

风险：
1. 审查后注入恶意代码（延迟攻击）
2. 插件崩溃导致整个 Obsidian 进程卡死
3. 插件占用大量内存，不受配额限制

Obsidian 对策：
- 社区审查指南（列出常见危险 API 用法）
- 沙箱文档警告（说明插件理论能做什么）
- 提供 API 文档让开发者合规使用


## 3. 分发与市场

### 官方单一市场 vs 第三方仓库

**Chrome Web Store（官方单一渠道）**
- https://chromewebstore.google.com/
- 所有扩展必须上传到 Chrome Web Store
- Google 有发行权（可下架违规扩展）
- 用户版本锁定在 Store 发行的版本
- 更新由 Google 自动推送，不可绕过

优点：中心化控制，容易审查和召回
缺点：Google 的商业政策会改变（如移除 MV2 扩展）

**Kodi Repository（第三方生态）**
- https://github.com/xbmc/official-kodi-addons
- 官方维护 add-on 列表，第三方可提交 PR
- 社区第三方仓库（官方不管）
- Add-on 通过 GitHub release 分发，用户可指定任意仓库源

特点：
- 开放但有风险（第三方仓库无审查）
- Add-on 开发者可绕过官方仓库发行
- 用户需自己辨别可信仓库

**F-Droid（Android app 仓库）**
- https://f-droid.org/
- 社区维护的自由软件 app 市场
- 所有 app 源码必须开源、可审查
- 采用独立签名机制

特点：
- 要求源码透明（无闭源 SDK）
- 社区审查流程
- 用户可自建 F-Droid repository

**Mihon 扩展仓库**（动漫阅读器）
- https://mihon.app/extensions/
- 中心化，但社区可提交扩展
- 采用 minisign 签名验证

### 签名与完整性校验

**minisign 机制**
- https://jedisct1.github.io/minisign/
- 简单的签名工具，用于验证文件完整性

```bash
# 签名
minisign -Sm file.ext -s secretkey.key

# 验证
minisign -Vm file.ext -P publickey.pub
```

发行流程：
1. 开发者生成公钥，发布在官网
2. 每个 release 用私钥签名
3. 用户下载文件和签名，验证公钥
4. 如果签名失败，文件被篡改或源不可信

优点：轻量级，无需 PKI 基础设施
缺点：公钥泄露后无法快速撤销

**Sigstore（现代方案）**
- https://www.sigstore.dev/
- 使用 OpenID Connect (OIDC) 颁发短生命周期证书
- GitHub Actions 可自动签名

```bash
cosign sign-blob --key cosign.key file.ext
cosign verify-blob --key cosign.pub --signature sig file.ext
```

优点：
- 无需管理长期密钥
- GitHub Actions 自动化签名
- 证书链可验证（信任链更清晰）

缺点：依赖 Sigstore 基础设施（虽然开源）

**仓库签名机制**
- Debian/Ubuntu: Release 文件用发行者 GPG 密钥签名
- F-Droid: 仓库索引 (index.xml) 用仓库密钥签名

流程：
1. 仓库维护者用私钥签名 index 文件
2. 用户配置仓库公钥
3. 客户端下载 index 时验证签名
4. 如果 index 被篡改（MITM、服务器被黑），签名验证失败

### 自动更新的风险

**问题 1：强制更新无回滚**
- Chrome 扩展自动更新（用户无法阻止）
- 如果新版本有 bug，用户卡住（除非卸载）
- 对策：版本号递增硬约束、上线前充分测试

**问题 2：更新推送不对称**
- 如果服务端更新，客户端是异步拉取
- 窗口期内新旧版本混用导致不兼容

对策：
- API 版本号声明
- 弃用前置期（新 API 发布后保留旧 API 2-3 个版本）
- 灰度发布（先推给小部分用户）

**问题 3：更新过程中的权限变化**
- MV2 → MV3 迁移中，某些权限不再支持
- 用户可能突然失去某项功能

对策：
- manifest 包含 `minimum_version` 声明
- 客户端检查本地版本 >= 最低版本


## 4. API 版本兼容

### VS Code engines.vscode

**来源：https://code.visualstudio.com/api/working-with-extensions/publishing-extension**

```json
{
  "engines": {
    "vscode": "^1.90.0"
  }
}
```

机制：
- VS Code 在 `extensions` 文件夹里检查每个扩展的 `engines` 字段
- 如果 `engines.vscode` 版本不匹配，扩展禁用
- 开发者必须提前声明最低版本依赖
- Semver 语义：`^1.90.0` 表示 >= 1.90.0 且 < 2.0.0

版本迁移案例：
- VS Code 从 1.x 跳到更新频率（约每月一版本）
- 新 API 不断加入，旧 API 标记为 `deprecated` 但通常 1-2 年后才删
- 扩展开发者需要定期更新 `engines` 才能获得新功能

风险：
- 如果 VS Code 强制移除旧 API（如 MV2 → MV3），所有依赖旧 API 的扩展同时崩溃
- VS Code 的退出政策不完全清晰（有时只给 3-6 个月通知）

### Obsidian minAppVersion

**来源：https://docs.obsidian.md/Developers/Obsidian+API**

```json
{
  "minAppVersion": "1.4.0"
}
```

机制：
- Obsidian 在启动时逐个加载插件
- 检查 manifest 的 `minAppVersion` 与本地 Obsidian 版本对比
- 如果本地版本 < 最低版本，插件禁用（显示通知）
- 社区插件库会检查 manifest 的 `minAppVersion` 是否合理

特点：
- 比 VS Code 更宽松（容忍不匹配但禁用）
- 用户会看到"需要 Obsidian 1.5.0+"的消息
- 插件开发者应提供降级路径（fallback API）

API 弃用策略：
- Obsidian 团队发布新 API 时，旧 API 通常保留 2-3 个大版本
- 弃用前通过博客和 changelog 通知
- 社区有 `0.x` 版本（实验性 API）区分

### Chrome manifest_version 迁移

**来源：https://developer.chrome.com/docs/extensions/mv3/**

MV2 → MV3 迁移（2024 年完成）：

时间表：
- 2023 年 1 月：停止接受新 MV2 扩展
- 2024 年 6 月：Chrome 停止运行 MV2 扩展

剧痛：
- 数百万用户的扩展突然失效
- 许多开发者放弃迁移（维护成本高）
- 用户无法继续使用习惯依赖的扩展

MV2 vs MV3 API 改变：
```javascript
// MV2: 后台持久化脚本
chrome.tabs.onUpdated.addListener((tabId, changeInfo, tab) => {
  // 每次标签页变化触发，后台常驻
});

// MV3: Service Worker (事件驱动，不持久)
chrome.tabs.onUpdated.addListener((tabId, changeInfo, tab) => {
  // 30 秒无活动后 worker 卸载
  // 需要用 chrome.alarms 替代定时器
});
```

对策：
- Chrome 发布了 MV2 → MV3 迁移指南
- 但单向迁移（无法回滚），强制性很强
- 许多中小型扩展被用户放弃

### Android API Level 弃用

**来源：https://developer.android.com/build/release-notes**

Android 每年发布新版本，逐步弃用旧 API。

例：
- API 16 (Android 4.1) 发布于 2012 年，2023 年 Google Play 要求 targetSdkVersion >= 31
- App 必须更新代码适配新权限模型、后台执行限制等

特点：
- 强制更新（否则 Google Play 不再发行）
- 通常有 1-2 年过渡期
- 开发者必须定期投入维护成本

---

## 5. 资源隔离与稳定性

### 超时与进程隔离

**Chrome Service Worker 生命周期**
- MV3 中后台脚本在 Service Worker 中运行
- 5 分钟无事件自动卸载
- 如果脚本耗时操作（如 for 循环阻塞），Chrome 可强制杀死

```javascript
// 危险：这会导致 Service Worker 被杀
for (let i = 0; i < 1e9; i++) {
  // CPU 消耗百分百
}

// 正确：异步操作，不阻塞事件循环
await fetch(url).then(r => r.json());
```

**Obsidian 插件卡死风险**
- Obsidian 是单线程（主 UI 线程）
- 如果插件有同步耗时操作（如 JSON.parse 2GB 文件），整个应用卡死
- 无法中断或杀死单个插件

实际事故：某插件用同步 API 读大文件，用户抱怨 Obsidian "经常冻屏 3-5 秒"。

对策：
- 文档警告（建议异步操作）
- 社区最佳实践（虽然无强制）
- 用户禁用坏插件是唯一救法

### 内存与 CPU 配额

**VS Code 扩展禁用机制**
- 如果扩展激活后崩溃多次，VS Code 自动禁用它
- 用户可在设置里手动重启禁用的扩展
- 但无细粒度的内存或 CPU 配额限制

**Deno --deny-read=[path] 黑名单**
```bash
deno run --allow-read --deny-read=/root/.ssh script.ts
```

Deno 支持显式拒绝（黑名单）某些权限，用于防止权限升级。

**预期的对策（未广泛实施）**：
- 进程隔离：每个插件独立进程（VS Code 正在试验）
- 资源配额：CPU 时间、内存上限、并发连接数限制
- 检点救援：定期快照，插件崩溃时恢复

### 熔断与禁用坏插件

**自动禁用坏插件（Firefox）**
- https://support.mozilla.org/en-US/kb/disable-or-remove-add-ons

如果扩展：
- 多次崩溃
- 导致浏览器崩溃
- 长期无更新且与新版本不兼容

Firefox 可自动禁用，并提示用户。

**安全模式启动**
- 用户可以进入"安全模式"（扩展全部禁用）来诊断问题
- 对标 Windows 安全模式、Android Safe Mode

对策：
- LinPlayer 应提供"插件安全模式"（禁用所有插件启动）
- 保存最后导致崩溃的插件列表，下次启动时提示


## 6. 插件间通信与依赖

### 直接 IPC vs 消息总线

**VS Code 间插件通信**
- https://code.visualstudio.com/api/extension-guides/extension-marketplace#extension-pack

VS Code 扩展可以相互调用（通过 `vscode.commands`）：

```javascript
// 扩展 A 暴露命令
export function activate(context: vscode.ExtensionContext) {
  context.subscriptions.push(
    vscode.commands.registerCommand('myext.greet', (name) => {
      return `Hello, ${name}`;
    })
  );
}

// 扩展 B 调用
const result = await vscode.commands.executeCommand('myext.greet', 'Alice');
```

风险：
- 如果 A 扩展被卸载，B 调用会失败
- 没有版本约束（B 不知道 A 的版本兼容性）
- 恶意扩展可伪造命令（名称劫持）

对策：
- 文档建议扩展声明提供的命令 ID
- 调用方需要 try-catch 处理失败
- 无系统级的访问控制（信任模型较弱）

**Obsidian 间插件依赖**
- https://docs.obsidian.md/Developers/Manifests/manifest#dependencies

```json
{
  "id": "my-plugin",
  "dependencies": {
    "other-plugin": ">=1.0.0"
  }
}
```

机制：
- manifest 声明对其他插件的依赖
- Obsidian 在加载时检查依赖插件是否已加载
- 如果缺少依赖，本插件不激活（显示错误）
- 无法禁用依赖插件（Obsidian 会警告）

风险：
- 如果依赖插件无维护，整条链卡住
- 无版本兼容性检查（只是字符串匹配）

### 共享服务与单例

**Chrome 共享模块（无原生支持）**

开发者通常用全局变量或 localStorage：

```javascript
// content-script-a.js
window.mySharedService = { count: 0 };

// content-script-b.js
window.mySharedService.count++;  // 访问
```

问题：
- 如果 A 先卸载，B 引用会变成 undefined
- 命名冲突（两个插件都用 `window.sharedData`）
- 无隔离（B 可直接修改 A 的共享对象）

**更好的做法：消息总线**

```javascript
// content-script.js 都监听同一个端口
const port = chrome.runtime.connect({ name: "service-bus" });

port.onMessage.addListener((msg) => {
  if (msg.type === 'getData') {
    port.postMessage({ data: myData });
  }
});
```

对标：
- Electron 的 ipcMain/ipcRenderer
- Node.js EventEmitter（给插件 API）

---

## 7. 插件存储与密钥

### localStorage vs KV Store vs 数据库

**Chrome 扩展存储**
- https://developer.chrome.com/docs/extensions/reference/storage/

```javascript
// 同步存储（5 MB 限制）
chrome.storage.sync.set({ key: 'value' });
chrome.storage.sync.get('key', (items) => {
  console.log(items.key);
});

// 本地存储（无大小限制）
chrome.storage.local.set({ bigData: arrayBuffer });
```

特点：
- 隔离（每个扩展独立的存储空间）
- 同步 (sync) 会跨设备同步用户设置（同步账号）
- 本地 (local) 仅本机存储

**Obsidian 插件存储**
- https://docs.obsidian.md/Developers/Obsidian+API#Data+operations

插件无专属存储空间，只能：
1. 读写笔记文件（.md 本地）
2. 使用 plugin data 目录（`.obsidian/plugins/<id>/`)
3. 用浏览器 localStorage（Web 版本）

风险：
- 插件 A 的配置文件在 `.obsidian/plugins/a/data.json`
- 插件 B 可以读取（无沙箱保护）
- 恶意插件可窃取其他插件的凭据

### 凭据隔离

**Chrome credential 管理**
- https://developer.chrome.com/docs/extensions/reference/identity/

```javascript
// OAuth 2.0 流程（安全）
chrome.identity.getAuthToken({ interactive: true }, (token) => {
  // token 由 Chrome 管理，不暴露给其他扩展
});
```

Chrome 本身管理 OAuth token，插件无法读其他插件的 token。

**Android KeyStore**
- https://developer.android.com/training/articles/keystore

App 可将凭据存在系统级 KeyStore：

```java
KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
keyStore.load(null);

SecretKey secretKey = (SecretKey) keyStore.getKey("my_key", null);
```

特点：
- 其他 app 无法读取（由 OS 保护）
- 凭据可设置为"仅在屏幕解锁时可用"

**Obsidian 的坑**

Obsidian 无凭据隔离，插件只能存到本地文件。

```javascript
// 不安全的做法
const config = { apiKey: 'sk-1234567890' };
await this.app.vault.adapter.write('.obsidian/plugins/my-plugin/config.json', JSON.stringify(config));
```

风险：
- 任何插件都能读这个文件
- 如果 .obsidian 目录被同步到云端，密钥泄露
- 用户无法对凭据加密（Obsidian 没有提供机制）

建议：
- 用 OAuth 不存密钥
- 如果必须存密钥，加密存储（用操作系统的密钥库）

---

## 给 LinPlayer 的最小可行治理方案

### 必须有（最小可行）

1. **权限声明（manifest）**
   ```json
   {
     "id": "example-plugin",
     "minAppVersion": "1.0.0",
     "permissions": ["read_library", "playback"],
     "network": {
       "allowed_domains": ["api.example.com"]
     }
   }
   ```
   
   - 最低应该声明网络权限（域名白名单）
   - 清单在插件启动时检查（不强制沙箱，但有审计追踪）
   - 文档列出所有可能的 permission 字符串

2. **版本兼容性检查**
   ```go
   // 伪代码
   if plugin.minAppVersion > appVersion {
     disablePlugin(plugin.id, "requires LinPlayer " + plugin.minAppVersion)
     return
   }
   ```
   
   - 启动时检查 manifest 中的 minAppVersion
   - 版本号用 Semver（major.minor.patch）
   - 不满足则禁用插件，显示通知

3. **插件禁用机制**
   ```go
   // 如果插件连续 3 次导致崩溃，自动禁用
   plugin.crashCount++
   if plugin.crashCount >= 3 {
     plugin.disabled = true
     saveDisabledList()  // 下次启动记得禁用
   }
   ```
   
   - 追踪插件崩溃次数
   - 超过阈值自动禁用（显示通知）
   - 用户可在设置里手动重启

4. **安全模式启动**
   ```go
   // 如果上次启动在插件期间崩溃，下次进安全模式
   if lastCrashWasInPlugin() {
     loadPluginsDisabled()
     showNotification("安全模式：所有插件已禁用")
   }
   ```
   
   - 记录上次关闭时的活跃插件列表
   - 如果进程异常退出，下次启动禁用所有插件
   - 用户手动重启插件后生效

5. **隔离存储空间**
   ```go
   // 插件 A 存储目录：~/.linplayer/plugins/plugin-a/
   // 插件 B 存储目录：~/.linplayer/plugins/plugin-b/
   // 插件无法读其他目录
   ```
   
   - 每个插件有独立的数据目录（插件 ID 为文件夹名）
   - 宿主负责沙箱隔离（文件权限控制）
   - API 提供 `plugin.localStorage(key)` 简化存取

### 最好有（下个版本）

6. **官方插件仓库 + 签名验证**
   - 建立单一官方源（如 https://plugins.linplayer.app/）
   - 所有 release 用 minisign 签名
   - 客户端下载时验证签名（拒绝篡改）
   - 仓库里的插件需经基础审查（无明显恶意代码）

7. **运行时权限申请**
   - 插件启动时声明需要的权限
   - 首次使用时弹窗确认（类似 Android）
   - 用户可在设置里撤销权限
   - 权限被拒绝时，插件收到错误而不是崩溃

8. **资源配额**
   - 单个插件最多占用 CPU 20%，超过自动 freeze
   - 单个插件内存上限 100 MB，超过警告
   - 网络连接数限制 10，超过拒绝
   - 实施超时：网络请求 30s 超时，自动重试

### 以后再说

9. **插件间通信 API**
   - 定义消息总线 (event emitter)
   - 插件可注册事件侦听器（不可伪造命名空间）
   - 文档化常见的跨插件场景（如"多个数据源提供器")

10. **灰度发布 + A/B 测试**
    - 新版本先推给小部分用户
    - 收集崩溃率数据后全量推送
    - 如果新版本崩溃率升高 > 5%，自动回滚

11. **审查工具链（CI/CD）**
    - GitHub Action 自动检查新提交的 PR
    - 静态分析（禁止明显危险 API 如 `eval`）
    - 运行插件单测（如果有）
    - 签名校验（确保发布者身份）

---

## 总结

| 层次 | 机制 | 收益 | 代价 |
|---|---|---|---|
| **权限声明** | manifest 白名单 | 审计追踪，用户知道插件能做什么 | 文档和检查逻辑（0.5 天） |
| **版本兼容性** | minAppVersion 检查 | 防止旧插件在新版本崩溃 | 版本号管理规范（1 天） |
| **自动禁用坏插件** | 崩溃计数 + 禁用 | 防止一个坏插件拖垮整个应用 | 崩溃追踪和持久化（1 天） |
| **官方签名** | minisign/Sigstore | 防止插件被篡改、下载即中毒 | 维护密钥、发布流程改造（2 天） |
| **隔离存储** | 文件权限沙箱 | 防止插件间互相读凭据 | 实施成本取决于宿主（0.5-2 天） |

**最小可行方案**（1 周内落地）：1, 2, 3, 4, 5

**下个版本**（下季度）：6, 7, 8

**收益与代价的权衡**：开源小团队应优先做 3, 4, 5（防崩溃和开机问题），再做 1, 2（易于理解和维护），最后才考虑高级机制（运行时权限、资源配额）。

