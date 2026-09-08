# 备份文件格式(`.lpbak`)

> 一个文件装走「服务器 + 设置」。PC 端和移动端读写的是**同一份**;
> 第三方播放器只要能读 CommonConfig 容器,就能拿到服务器和凭据。
>
> 实现:`core/config/backup.go`(内容)· `core/config/transfer.go`(容器与加密)
> · 命令 `prefs.backupExport` / `prefs.backupPreview` / `prefs.backupImport`。

---

## 1. 为什么不是自己发明一种格式

容器**原样复用** Richasy/Rodel 的 `CommonConfig` —— 也就是 LinPlayer 扫码搬配置
(`LPSYNC1:` 那条路)一直在用的那一个,一个字节都没改。

自己发明一种的成本不是「多写一个解析器」,是**互通这件事从此没有对手**:
用户换播放器时只能一台一台手输服务器。共用容器换来的是，任何已经实现
CommonConfig 的客户端把 `.lpbak` 当配置文件喂进去就能用，它只需要无视
一个多出来的键。

二维码那条路和这条路的区别只有两点：二维码 **gzip + base64url + `LPSYNC1:` 前缀**
（要塞进码里），且**只装账号**；备份文件是**明文 JSON**（用户会打开看一眼），
且多一个 `linplayer_settings` 段。

## 2. 文件长什么样

```jsonc
{
  "from": "LinPlayer",
  "version": "1.0",
  "export_time": 1757300000,          // Unix 秒
  "configs": [                        // 一个账号一条，AES-256-CBC/PKCS7 后 base64
    "3q2+7wAAAAA...",
    "..."
  ],
  "_key": "TGluUGxheWVyLWNvbW1vbi1jb25maWcta2V5LXYxIQA=",   // base64，32 字节
  "linplayer_settings": { /* 见 §4，非本 App 可整段忽略 */ }
}
```

顶层键**就这六个**。多出来的第七个键会被我们的导入器接受但忽略；
少了 `configs` 且少了 `linplayer_settings` 会被拒（那不是一份备份）。

## 3. 解开一条 `configs`

1. base64 解码。
2. AES-256-**CBC** 解密，**IV = 密钥的前 16 字节**（Richasy 的约定，不是全零也不是随机）。
3. 去 PKCS#7 填充。**每一个填充字节都要校验** —— 只看最后一个的话，
   任意一段乱码有 1/16 的概率被当成合法明文，解出来的是垃圾账号。
4. 得到一段 JSON：

```jsonc
{
  "type": "emby",
  "id": "https://example.invalid",     // 以 server 为身份，导入时按它去重
  "name": "用户名",
  "url": "https://example.invalid",
  "username": "用户名",
  "user_id": "…",
  "access_token": "…",
  "linplayer": {                        // 我们的扩展段，别家可以整段忽略
    "name": "备注名", "remark": null, "icon_url": null, "password": "…",
    "lines": [...], "active_line": 0, "allow_insecure_tls": false,
    "source_kind": "emby",              // 网盘 / 局域网账号靠它和 source 才完整
    "source": { }
  }
}
```

**别家客户端只需要 `url` / `username` / `access_token` 这三项**；
`linplayer` 段缺失时我们这边全部走默认值。

### 密钥这件事要说清楚

`_key` **随文件一起走**。所以这不是加密，是**混淆**：它挡的是「随手打开文件
看见明文密码」，挡不住「照着这份文档解开」。真正的保护只有一条 ——
**别把带账号的备份文件发出去**。导出界面上那句警示就是为这个存在的。

想分享设置又不想给凭据：导出时把「包含服务器地址与账号密码」取消勾选，
`configs` 会是空数组。

## 4. `linplayer_settings`

我们自己的设置。第三方**应当整段忽略**。

```jsonc
{
  "theme": "dark",
  "companion_enabled": true,
  "plugin_official_enabled": true,
  "prefs": { /* core/config/prefs.go 的 Prefs，原样 */ },
  "danmaku_sources": [...], "proxy": {...},
  "sync_trakt": {...}, "sync_bangumi": {...}, "plugin_sources": [...]
}
```

### 里面**故意没有**的两样

| 字段 | 为什么不带 |
|---|---|
| `device_id` | 这台机器的身份。两台机器顶着同一个 id 连同一台 Emby，会话会互相踢掉——而报出来的现象是「一登录另一台就掉线」，谁也想不到是备份带的。 |
| `active` | 账号列表的**下标**。导入是合并不是覆盖，合并之后同一个下标指的不是同一台服务器了。 |

### 还原时会被摘掉的三个键

`prefs` 里的 `ui_font` / `screenshot_dir` / `external_player` 装的是**绝对路径**。
还原时判一下**路径还在不在**：

- 还原到**同一台机器**（备份的主要用途）→ 路径都在，一个都不摘。
- 跨设备 → 摘掉。留着的表现是：设置页上明明写着一个外部播放器路径，点了没反应。

## 5. 导入的规矩

**合并，不是覆盖。** 按 `url`（server）去重：同一台服务器用备份里的那份更新，
这台机器上原有的其它服务器保留。

覆盖的话，用户在新机器上已经加好的服务器会被静默抹掉 —— 而他以为自己
只是「把老机器上的搬过来」。

解不开的**单条跳过**，不让整次导入失败：文件里可能混着别家客户端写的条目。

## 6. 命令

| 命令 | 参数 | 返回 |
|---|---|---|
| `prefs.backupExport` | `accounts?` `settings?`（都默认 true）、`path?`（给了就核心层直接落盘） | `{content?, path?, filename, bytes, accounts?, warning?}` |
| `prefs.backupPreview` | `content?` 或 `path?` | `{from, export_time, accounts, has_settings}` |
| `prefs.backupImport` | `content?` 或 `path?`、`accounts?` `settings?` | `{imported, total, settings_restored}` |

`backupPreview` 存在的理由：**还原是不可逆的**。用户选错一个文件，
合并进来的账号只能一台一台删回去，所以写之前先让他看清楚要还原什么。

导入器也认二维码那条路的 `LPSYNC1:` 文本 —— 用户把出码时复制的那段
存成 `.txt` 再来导入是完全会发生的事，而报「不是备份文件」帮不了他。
