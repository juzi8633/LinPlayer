# Spike:goja `Interrupt` 打断死循环的延迟

> 2026-09-20 实测。本机 Windows 11 x64,Go 1.27.0,goja `v0.0.0-20260917113740-793a2a65c13b`,regexp2 `v2.5.2`。
> **失效条件**:goja 或 regexp2 升级;goja 换掉「正则按需落到 regexp2」的做法。

## 测法

每个用例起一个 goja,脚本先调 `ready()` 再进入负载;Go 侧等 50ms 后调 `vm.Interrupt()`,
量从 `Interrupt` 到 `RunString` 返回(且错误是 `*goja.InterruptedError`)的时长,每例 20 次。
Windows 上 `time.Now()` 的单调时钟粒度约 1ms,下表的 `0s` 读作「<1ms」。

## 结果

| 负载 | p50 | max | 结论 |
|---|---|---|---|
| `for(;;){}` | <1ms | <1ms | 打得断 |
| `while` 算术 | <1ms | <1ms | 打得断 |
| RE2 正则(无回溯特性)循环 | <1ms | <1ms | 打得断 |
| 字符串拼接循环 | <1ms | 12.7ms | 打得断 |
| `JSON.parse(JSON.stringify())` 循环 | <1ms | <1ms | 打得断 |
| `Array.sort` 带 JS 比较回调 | <1ms | <1ms | 打得断 |
| 微任务里死循环 | <1ms | <1ms | 打得断 |
| **regexp2 灾难回溯(单次 `.test`)** | — | — | **打不断**:Interrupt 后 10 秒仍未返回 |
| regexp2 灾难回溯 + `regexp2.DefaultMatchTimeout = 1s` | 1.05s | 1.06s | 匹配超时后回到 JS,Interrupt 随即生效 |
| 单次原生大操作(3000 万元素 `join`) | 2.9s | 3.6s | 原生调用中途不查中断标志,**跑完这一步才停** |

## 结论

1. **超时机制够用**:纯 JS 的死循环在 1ms 内被打断,远小于最小预算 300ms。
2. **必须补一处**:goja 遇到回溯类正则(前瞻、反向引用等)会落到 regexp2,而 regexp2 默认
   `MatchTimeout` 是无穷大、匹配中途不看 goja 的中断标志 —— 一条坏规则能把运行时永久卡死。
   `core/plugin/rt` 在包初始化时把 `regexp2.DefaultMatchTimeout` 设为 1 秒。
   代价:超时的那次匹配被 goja 当成「不匹配」返回(goja `regexp.go` 对 `err != nil` 按无匹配处理),
   紧接着 Interrupt 生效、本次调用以超时结束,所以插件看不到错误结果。
3. 单次原生大操作(超大数组 join、超大字符串 repeat)打不断但有限;预算到点后最迟在这一步结束时停。
   不再加额外措施 —— 内存看门狗(D141)兜住的是同一类「一步吃光资源」的情况。

## 未测

- **Android 真机同组基准**:本次没有连接的设备(`adb devices` 为空)。goja 的中断是每条字节码查标志,
  与平台无关,预期同量级;接上真机后用同一段程序(下附)跑 `GOOS=android GOARCH=arm64` 复测。

## 测试程序(核心部分)

```go
regexp2.DefaultMatchTimeout = time.Second // 去掉这行,regexp2 用例打不断
vm := goja.New()
vm.Set("ready", func() { close(start) })
go func() { _, runErr = vm.RunString(src); done <- time.Now() }()
<-start
time.Sleep(50 * time.Millisecond)
t0 := time.Now()
vm.Interrupt("timeout")
lat := (<-done).Sub(t0) // runErr 必须是 *goja.InterruptedError
```
