# Spike:goja `Interrupt` 打断死循环的延迟

> 2026-09-20 实测。本机 Windows 11 x64,Go 1.27.0,goja `v0.0.0-20260917113740-793a2a65c13b`,regexp2 `v2.5.2`。
> **失效条件**:goja 或 regexp2 升级;goja 换掉「正则按需落到 regexp2」的做法。
> Android 部分 2026-09-20 补测(Android 16 模拟器 x86_64),见下文。

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

## Android 同组基准(2026-09-20 补测)

设备:Android 16 模拟器(AVD `lp-phone`),**x86_64**,4 核。二进制 `CGO_ENABLED=0 GOOS=linux GOARCH=amd64`
静态编译后 `adb push` 到 `/data/local/tmp` 直接跑(`GOOS=android` 要求外部 cgo 链接,纯计算基准没必要)。

| 负载 | p50 | max | 桌面 p50 | 结论 |
|---|---|---|---|---|
| `for(;;){}` | <1ms | 0.2ms | <1ms | 打得断 |
| `while` 算术 | <1ms | 1.0ms | <1ms | 打得断 |
| RE2 正则循环 | <1ms | 0.9ms | <1ms | 打得断 |
| 字符串拼接循环 | 0.2ms | 2.0ms | <1ms | 打得断 |
| JSON 往返循环 | <1ms | 2.2ms | <1ms | 打得断 |
| `Array.sort` 带 JS 回调 | <1ms | 2.5ms | <1ms | 打得断 |
| 微任务里死循环 | <1ms | 0.1ms | <1ms | 打得断 |
| **regexp2 灾难回溯** | — | — | — | **打不断**:Interrupt 后 10 秒仍未返回 |
| regexp2 灾难回溯 + `MatchTimeout = 1s` | 1.06s | 1.07s | 1.05s | 匹配超时后 Interrupt 生效 |
| **单次原生大 `join`(3000 万)** | **13.1s** | **35.0s** | 2.8s | **比桌面慢 4.6~10 倍** |

三条结论:

1. **纯 JS 的打断延迟与平台无关**:最大 2.5ms,仍远小于最小预算 300ms。goja 每条字节码查中断标志,
   这个结果符合预期。
2. **regexp2 那一条在移动端一模一样**:不设 `MatchTimeout` 就是永久卡死,设了 1 秒就是 1 秒后生效。
   `core/plugin/rt` 的包初始化钉 1 秒这件事在两个平台都必要。
3. ⚠️ **单次原生大操作在慢设备上能超过 30 秒的数据预算**:桌面 2.8s 的那一步在这台上 p50 13.1s、
   max 35.0s。也就是说 `BudgetData = 30s` 在低端盒子上**挡不住一次超大 `join`/`repeat`** ——
   它不是「30 秒后停」,是「这一步跑完才停」。兜底仍然只有内存看门狗(D141):
   3000 万元素的数组本身就先撞内存线。不为此再加机制,但这条要写进文档,别以为预算是硬上限。

### 这次补测顺带修掉的一个坑

原报告那一行 regexp2 用例如果写成 `/^(a+)+$/` 是**测不到 regexp2 的** —— 这个模式没有前瞻、
没有反向引用,goja 用 Go 自带的 RE2 就能编,而 RE2 不回溯,结果是「秒回、打得断」,看起来一切正常。
必须带上 regexp 编不了的语法(前瞻 / 反向引用)才会落到 regexp2:本次用的是 `/^(?=a)(a+)+$/`。

## 未测

- **arm64 真机**:手上只有 x86_64 模拟器。中断机制与指令集无关,但上面第 3 条那个倍数
  在真机 arm64 上会是多少没量过 —— 要量的是**设备快慢**,不是架构。

## 测试程序(核心部分)

不进仓库:一次性 spike,留在文档里够复现。放进 `core/cmd/` 跑一次就建一个包,
而它和产品代码没有任何调用关系。

```go
regexp2.DefaultMatchTimeout = time.Second // 去掉这行(设成 math.MaxInt64),regexp2 用例打不断
vm := goja.New()
vm.Set("ready", func() { close(start) })
go func() { _, runErr = vm.RunString(src); done <- time.Now() }()
<-start
time.Sleep(50 * time.Millisecond)
t0 := time.Now()
vm.Interrupt("timeout")
lat := (<-done).Sub(t0) // runErr 必须是 *goja.InterruptedError
// lat < 0 = 脚本在 Interrupt 发出之前就自己跑完了,那不是打断延迟,别记进去
```

九个用例的脚本(`ready()` 之后进负载):

```js
for(;;){}
var x=0; while(true){ x = (x*31+7)%1000003; }
var re=/[a-z]+[0-9]+/; var s="abc123"; while(true){ re.test(s); }
var s=""; while(true){ s+="x"; if(s.length>1e6) s=""; }
var o={a:1,b:[1,2,3],c:"x"}; while(true){ JSON.parse(JSON.stringify(o)); }
var a=[]; for(var i=0;i<2000;i++)a.push((i*7919)%2000); while(true){ a.sort(function(p,q){return p-q;}); a.reverse(); }
Promise.resolve().then(function(){ for(;;){} });
var re=/^(?=a)(a+)+$/; re.test("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!");   // 前瞻是必须的,见上
var a=new Array(30000000); a.fill("x"); a.join("");
```

Android 跑法:`CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build` → `adb push` 到 `/data/local/tmp` → `adb shell` 直接执行。
