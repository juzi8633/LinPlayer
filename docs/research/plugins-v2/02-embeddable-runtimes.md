# Go 可嵌入的插件运行时横评

> 调研日期: 2026-09-19  
> 目标: 为 LinPlayer 插件系统 v2 选择合适的代码执行运行时  
> 约束: 需在 Windows/Linux/Android 四个端交叉编译(不利用 cgo 更佳)

## 调研方法

逐个运行时验证以下 7 个维度:
1. **纯 Go 与交叉编译** — 是否需要 cgo,能否到 android/arm64、android/arm、android/amd64、windows/amd64、linux/amd64
2. **语言支持程度** — 对应语言版本(JS 的 ES 版本、async/await、模块系统等)
3. **性能量级** — 基准数据、启动开销、内存占用、二进制增量
4. **沙箱能力** — CPU 时间限制、内存限制、中断执行
5. **维护活跃度** — 最近 release、star 数、issue 响应
6. **作者体验** — TypeScript 支持、调试能力、开发工具链
7. **生产项目** — 真实使用案例

---

## JavaScript 运行时

### 1. Goja (dop251/goja)

**纯 Go 与交叉编译**  
✓ 纯 Go 实现,无 cgo 依赖,支持所有 Go 编译目标(android/arm64、android/arm、android/amd64、windows/amd64、linux/amd64)  
最低 Go 版本: 1.25  
出处: https://github.com/dop251/goja

**语言支持程度**  
- ECMAScript 5.1 完全支持(包含正则和严格模式)
- 大部分 ES6 特性已实现(但不完整)
- 能运行 Babel、TypeScript 编译器
- **有** Promise(`builtin_promise.go`)与 async/await(issue #460 实现,k6 已随之升级,见 https://github.com/grafana/k6/issues/2842);**无**原生 ES module(k6 自己补,见 https://github.com/grafana/k6/issues/3265)。【主 agent 抽查更正,原稿写"无 async/Promise"是错的】
出处: https://github.com/dop251/goja/blob/master/README.md

**性能量级**  
- 比 otto 快 6-7 倍
- **不能替代 V8 或 SpiderMonkey**,性能档位为"not fast but acceptable"
- 实际基准未公开,数据来自文档定性描述
出处: https://github.com/dop251/goja

**沙箱能力**  
- 支持 `vm.Interrupt()` 中断执行(可用于终止无限循环)
- 无 CPU 时间限制、内存限制、fuel 机制
出处: https://github.com/dop251/goja

**维护活跃度**  
- GitHub star: 3000+
- 2026 年有持续更新
- 问题响应积极
出处: https://github.com/dop251/goja

**作者体验**  
- 无 TypeScript 支持(原生 JavaScript 输入)
- 调试: 基于标准 Go tooling
- 无官方 IDE 支持
出处: https://github.com/dop251/goja

**生产项目**  
未确认有大规模生产部署

**并发限制**  
⚠️ 单个 Runtime 实例只能被单个 goroutine 使用,多个实例间无法传递对象
出处: https://github.com/dop251/goja

---

### 2. goja_nodejs

**纯 Go 与交叉编译**  
✓ 纯 Go,继承 goja 的交叉编译能力  
出处: https://github.com/dop251/goja_nodejs

**语言支持程度**  
- 在 goja 基础上加 Node.js 兼容库
- require("events")、require("node:events") 等 CommonJS 模块加载
- 类型定义发布到 npmjs (@dop251/types-goja_nodejs-MODULE)
- async/await 由底层 goja 提供(见上节更正)
出处: https://github.com/dop251/goja_nodejs

**沙箱能力**  
继承自 goja 的 Interrupt,无额外沙箱
出处: https://github.com/dop251/goja_nodejs

**其它**  
相当于 goja + 模块层,模块内容有限

---

### 3. v8go (rogchap/v8go)

**纯 Go 与交叉编译**  
✗ **需要 cgo**,使用 C++ 绑定 V8  
- Linux x86_64、macOS x86_64、Windows x86_64 有预编译二进制
- **跨编译到 Android 困难**,需要自行编译 V8(未验证是否可行)
出处: https://github.com/rogchap/v8go

**语言支持程度**  
- V8 9.0.257.18(2021 年)
- 支持 ES6 功能(箭头函数、模板字符串等)
- **无 async/await 官方说明**,可能不支持或部分支持
出处: https://github.com/rogchap/v8go

**性能量级**  
- V8 是生产级高性能引擎
- 无公开 benchmark vs goja
- 预期远高于 goja,代价是 cgo 开销
出处: https://github.com/rogchap/v8go

**沙箱能力**  
- Context 隔离(每个 Context 沙箱化)
- 脚本执行中断
- 无 CPU/内存限制
出处: https://github.com/rogchap/v8go

**维护活跃度**  
- 由 rogchap 创建,后由 katallaxie 维护
- 2026 年活跃
- star: 2500+
出处: https://github.com/rogchap/v8go

**作者体验**  
- 标准 V8 JavaScript 支持
- 代码缓存优化(避免重复编译)
- CPU profiler 支持
- 调试: Go + JavaScript 混合困难
出处: https://github.com/rogchap/v8go

**生产项目**  
Shopify 开发了 CPU profiler 扩展

---

### 4. QuickJS Go 绑定

**4.1 传统 CGO 版(quickjs-go、go-quickjs)**

纯 Go 与交叉编译**  
✗ 需要 cgo,需要 C 编译工具  
- Linux、Windows、macOS 可交叉编译(用 gcc-7 或 mingw32)
- **Android 交叉编译**: 未确认
出处: https://github.com/quickjs-go/quickjs-go

**语言支持程度**  
- ES2020 支持
- async/await 支持
- 二进制体积小(相比 V8)
出处: https://github.com/quickjs-go/quickjs-go

**性能**  
- 体积: 比 V8 小得多
- 速度: 介于 goja 和 V8 之间(确切数据未公开)
出处: https://github.com/quickjs-go/quickjs-go

**4.2 纯 Go/WASM 版(fastschema/qjs)**

**纯 Go 与交叉编译**  
✓ 纯 Go,无 cgo,支持完整交叉编译  
基于 Wazero WASM 运行时  
出处: https://github.com/fastschema/qjs

**语言支持**  
- ES2023 支持
- async/await 支持
- 零复制 ProxyValue 机制
出处: https://github.com/fastschema/qjs

**性能**  
- 阶乘计算: 比 goja 快 1.5x,比 ModernQuickJS 快 2.5x
- 字节码编译支持,可缓存预编译脚本
出处: https://github.com/fastschema/qjs

**沙箱**  
- 文件系统隔离
- 无网络访问(除非显式允许)
- WASM 固有内存安全(无缓冲区溢出)
出处: https://github.com/fastschema/qjs

**结论**: WASM 版本(qjs)在交叉编译和安全性上优于 CGO 版本,但性能相比 cgoquickjs 可能略低

---

## WebAssembly 运行时

### 5. Wazero (wazero/wazero)

**纯 Go 与交叉编译**  
✓ **纯 Go,零依赖,无 cgo**  
支持所有 Go 编译目标:android/arm64、android/arm、android/amd64、windows/amd64、linux/amd64  
出处: https://github.com/wazero/wazero

**语言支持程度**  
- WebAssembly Core Spec 1.0 和 2.0 完全遵循
- 可运行任何 WASM 编译的语言(Rust、Go via TinyGo、C、Zig 等)
- 不绑定特定编程语言
出处: https://github.com/wazero/wazero

**执行模式**  
- Interpreter: 通用、跨平台兼容所有架构
- Compiler: JIT 编译,仅支持 amd64 和 arm64,显著加速
出处: https://github.com/wazero/wazero

**性能量级**  
- 1.0 发布于 2023 年 3 月,生产就绪
- 编译模式远快于解释模式
- Tetragon(eBPF)、Cilium(CNI)、Trivy、k6 生产在用
- 确切数字: 未公开
出处: https://github.com/wazero/wazero

**沙箱能力**  
- WASM 原生沙箱(内存隔离、权限分离)
- Context 取消可以中断执行
- 资源限制通过 context 的 fuel/gas 机制(取决于宿主实现)
出处: https://github.com/wazero/wazero

**维护活跃度**  
- GitHub star: 3000+
- 2026 年活跃,定期更新
- Issue 响应积极
出处: https://github.com/wazero/wazero

**作者体验**  
- 支持 Rust、Go(TinyGo)、C/Zig via WASI
- 无直接 TypeScript(但 WASM 工具链可支持)
- 调试: 依赖 WASM 工具链的符号支持
出处: https://github.com/wazero/wazero

**二进制体积**  
- 纯 Go 库,增量相对较小
- 无需嵌入 C++ 运行时或 JVM
出处: https://github.com/wazero/wazero

---

### 6. Extism Go SDK

**纯 Go 与交叉编译**  
✓ 纯 Go SDK,底层基于 Wazero,支持完整交叉编译  
出处: https://github.com/extism/go-sdk

**插件模式**  
- WASM 模块(.wasm 文件)为插件单位
- 可从文件、URL、对象存储加载
- Host functions: Go 应用向插件暴露的自定义功能
- 双向调用(插件可调用宿主)
出处: https://github.com/extism/go-sdk

**语言支持**  
- 多语言 PDK(Plugin Development Kit)
- 任何 WASM 可编译的语言可写插件
- 官方例子: Go via TinyGo
出处: https://github.com/extism/go-sdk

**功能**  
- 预编译、缓存、编译优化
- 文件系统映射(受限路径访问)
- WASI 支持
- 状态管理(插件间持久变量)
出处: https://github.com/extism/go-sdk

**维护活跃度**  
- GitHub star: 185+
- BSD-3-Clause 开源许可
- 2026 年更新(未在搜索结果确认,但项目在用)
出处: https://github.com/extism/go-sdk

**与 Wazero 关系**  
Extism 是 Wazero 之上的框架抽象,用于更快速的插件开发体验

---

### 7. Wasmtime-go (bytecodealliance/wasmtime-go)

**纯 Go 与交叉编译**  
✗ **需要 cgo**,包含预编译 Wasmtime 二进制  
- 仅支持: Linux x86_64、macOS x86_64、Windows x86_64
- **不支持 Android 和 arm 等其它架构**
- 自编译 Wasmtime: 理论可行,但复杂度高
出处: https://github.com/bytecodealliance/wasmtime-go

**语言支持**  
继承 Wasmtime 的完整 WASM 支持  
出处: https://github.com/bytecodealliance/wasmtime-go

**性能**  
- Wasmtime 是 Rust 编写的 JIT 编译器,高性能
- 预编译二进制,开箱即用
出处: https://github.com/bytecodealliance/wasmtime-go

**维护活跃度**  
- GitHub star: 2000+
- 测试于 Go 1.13 及以后
- ByteCode Alliance 官方项目
出处: https://github.com/bytecodealliance/wasmtime-go

**限制**  
cgo + 预编译二进制 = 交叉编译困难,不适合 Android

---

### 8. Wasmer-go (wasmerio/wasmer-go)

**纯 Go 与交叉编译**  
✗ **需要 cgo**,预编译 Wasmer 库  
- Linux amd64/aarch64、Darwin amd64 ✓
- Darwin aarch64、Windows amd64 ⏳(进行中)
- **不支持 Android**
出处: https://github.com/wasmerio/wasmer-go

**语言支持**  
完整 WASM 支持,可运行多种 WASM 工具链编译的代码  
出处: https://github.com/wasmerio/wasmer-go

**编译器选项**  
- Singlepass: 快速编译
- Cranelift: 平衡编译和执行
- LLVM: 慢速编译,快速执行
出处: https://github.com/wasmerio/wasmer-go

**Go 编译限制**  
⚠️ **Go 标准编译器不支持 WASI**,只能生成 JS 依赖的字节码  
解决方案: 使用 TinyGo + `-target wasi` 编译插件
出处: https://github.com/wasmerio/wasmer-go

**维护活跃度**  
- GitHub star: 2900+
- 673 commits
- Windows/aarch64 支持未完成
出处: https://github.com/wasmerio/wasmer-go

**与 Wasmtime 对比**  
两者都需要 cgo,Wasmer 支持更多编译器选项但 Windows 支持不完整

---

## Lua 运行时

### 9. GopherLua (yuin/gopher-lua)

**纯 Go 与交叉编译**  
✓ 纯 Go,支持完整交叉编译  
出处: https://github.com/yuin/gopher-lua

**语言支持**  
- Lua 5.1(+ Lua 5.2 的 goto 语句)
- 标准库支持有限(调试、弱表等缺失)
出处: https://github.com/yuin/gopher-lua

**性能**  
- 自述: "not fast but not too slow"
- 实测: 阶乘递归接近 Python 3 性能
- 比 go-lua 快 20%
出处: https://github.com/yuin/gopher-lua

**沙箱能力**  
- 选择性库加载(可禁用 file/os/net)
- 无 CPU/内存限制
出处: https://github.com/yuin/gopher-lua

**维护活跃度**  
- GitHub star: 7000+
- 402+ commits
- 2026 年活跃
出处: https://github.com/yuin/gopher-lua

**作者体验**  
- 无 TypeScript(Lua 是动态语言)
- 调试: 基于 Go tooling
- 文档完整
出处: https://github.com/yuin/gopher-lua

**生产项目**  
多个游戏引擎和插件系统在用

---

### 10. go-lua (Shopify/go-lua)

**纯 Go 与交叉编译**  
✓ 纯 Go 实现,支持交叉编译  
出处: https://github.com/Shopify/go-lua

**语言支持**  
- Lua 5.2 VM 完全端口到 Go
- 与原版 Lua 二进制文件兼容
出处: https://github.com/Shopify/go-lua

**性能**  
- vs C Lua: 慢 6x
- vs GopherLua: 慢 20%(原因: 调试 hook 和堆分配栈帧)
- 实际基准来自递归 Fibonacci
出处: https://github.com/Shopify/go-lua

**库支持缺陷**  
- 无正则表达式、coroutines、string.dump
- Go 不支持弱引用 table
出处: https://github.com/Shopify/go-lua

**维护活跃度**  
- Shopify 生产在用(Genghis 负载生成工具,2014 年起)
- star: 2400+
- 更新频率: 中等
出处: https://github.com/Shopify/go-lua

**与 GopherLua 对比**  
GopherLua 更快更活跃,go-lua 文档更详细(二进制兼容场景)

---

## 其它嵌入式运行时

### 11. Starlark (google/starlark-go)

**纯 Go 与交叉编译**  
✓ 纯 Go,支持完整交叉编译  
出处: https://github.com/google/starlark-go

**语言特性**  
- Python 方言,不是完整 Python
- 动态类型、高级数据类型、第一类函数
- **独立线程并行执行**(与 Python GIL 不同)
- 不支持: classes、try/except、async/await
出处: https://github.com/google/starlark-go

**设计用途**  
- 配置语言(起源于 Bazel 构建工具)
- 非通用编程,强调安全和确定性
出处: https://github.com/google/starlark-go

**维护活跃度**  
- GitHub star: 2800+
- Google 官方维护
- 2026 年活跃
出处: https://github.com/google/starlark-go

**限制**  
- "保留打破语言和 API 兼容性的权利"
- 非通用编程语言
- 缺少广泛的标准库
出处: https://github.com/google/starlark-go

**与 Python 对比**  
比 Python 更轻量、更安全、更确定,但功能受限,不适合通用脚本

---

### 12. Yaegi (traefik/yaegi)

**纯 Go 与交叉编译**  
✓ 纯 Go,支持完整交叉编译  
出处: https://github.com/traefik/yaegi

**能力**  
- 直接执行 Go 源代码,无编译步骤
- 支持 Go 1.21、1.22
- 简单 API: New()、Eval()、Use()
出处: https://github.com/traefik/yaegi

**安全**  
- 默认排除 `unsafe` 和 `syscall` 包
- 可选择加载特定包
出处: https://github.com/traefik/yaegi

**局限**  
- assembly 文件不支持
- CGO(C 互操作)不可用
- Go modules 不支持
- 解释执行速度远低于编译
出处: https://github.com/traefik/yaegi

**维护活跃度**  
- GitHub star: 3100+
- Traefik 官方维护
- 2026 年活跃
出处: https://github.com/traefik/yaegi

**适用场景**  
REPL、动态配置、快速迭代开发,不适合性能敏感的插件

---

### 13. Expr (antonmedv/expr)

**纯 Go 与交叉编译**  
✓ 纯 Go,支持交叉编译  
出处: https://github.com/antonmedv/expr

**语言范围**  
- **表达式求值专用**(不是通用编程语言)
- 静态类型、编译优化、字节码 VM
- 内置: all、none、any、one、filter、map 等宏
出处: https://github.com/antonmedv/expr

**安全性**  
- 内存安全(no memory unsafe)
- Side-effect-free(仅计算输出,无状态修改)
- Always terminating(无无限循环)
- 确定性执行
出处: https://github.com/antonmedv/expr

**性能**  
- 优化编译 + 字节码 VM
- 线性时间求值(无宏时)
- 生产使用: Google Cloud、GoDaddy、ByteDance、Alibaba、Argo、OpenTelemetry
出处: https://github.com/antonmedv/expr

**维护活跃度**  
- GitHub star: 8000+
- 1000+ commits
- 2026 年活跃
出处: https://github.com/antonmedv/expr

**限制**  
- 不能写完整逻辑(无循环、无变量赋值)
- 适合: 规则引擎、动态过滤、条件计算
出处: https://github.com/antonmedv/expr

---

### 14. CEL (google/cel-go)

**纯 Go 与交叉编译**  
✓ 纯 Go,支持交叉编译  
出处: https://github.com/google/cel-go

**语言**  
- Common Expression Language(Google 设计)
- C 语法风格(类似 C++、Go、Java、TypeScript)
- **非图灵完全**(设计目标: 安全、简单、快速)
出处: https://github.com/google/cel-go

**特点**  
- 表达式求值,无副作用、无状态修改
- 支持原始类型、List、Map、JSON、Protocol Buffers
- 宏支持: all()、exists()、filter()、map()
- 线性时间复杂度(宏关闭时)
出处: https://github.com/google/cel-go

**工作流**  
1. Environment: 定义可用变量和函数
2. Compile: 前置解析和类型检查
3. Evaluate: 线程安全执行
出处: https://github.com/google/cel-go

**部署**  
- GitHub 迁移至 github.com/cel-expr/cel-go(2026 年 6 月)
- 导入路径: cel.dev/cel-go
出处: https://github.com/google/cel-go

**与 Expr 对比**  
两者都是表达式求值,CEL 更规范化、跨语言支持更好;Expr 优化编译效率

---

## 进程外方案

### 15. go-plugin (hashicorp/go-plugin)

**架构**  
- RPC 子进程模式,不是嵌入式
- 支持 net/rpc 和 gRPC 双协议
- HTTP2 多路复用(gRPC)
出处: https://github.com/hashicorp/go-plugin

**隔离与安全**  
- 插件崩溃不影响宿主进程
- 插件只能访问显式暴露的接口(内存隔离)
- TLS mTLS 通信、签名验证支持
- Terraform、Vault、Nomad 生产部署百万级
出处: https://github.com/hashicorp/go-plugin

**多语言**  
- 虽然核心 Go 写,但通过 gRPC 支持任何语言
- 插件可用 Rust、Python、JavaScript 等编写
出处: https://github.com/hashicorp/go-plugin

**功能**  
- 复杂参数支持
- 双向调用(插件回调宿主)
- 日志捕获和 stdio 同步
- 主进程升级后重新连接
出处: https://github.com/hashicorp/go-plugin

**Android 支持**  
❌ **不支持 Android**  
- 设计假设: 本地网络 RPC
- 子进程模式不适合 Android 沙箱模型
- 仅限 Terraform、Vault 等桌面/服务器工具
出处: https://github.com/hashicorp/go-plugin

**维护活跃度**  
- GitHub star: 4500+
- 生产验证(HashiCorp 全线产品)
- 2026 年活跃
出处: https://github.com/hashicorp/go-plugin

**限制**  
- "当前只设计用于本地可靠网络"(不支持远程网络)
- 仅 Go、node-rpc、gRPC 三种协议
- 进程开销(vs 嵌入式)
出处: https://github.com/hashicorp/go-plugin

**结论**  
高度隔离但受限于进程外模式,**不适合 LinPlayer 的多端统一插件需求**

---

## 横向对比表

| 运行时 | cgo | Android 交叉编译 | 语言支持 | 性能 | 沙箱能力 | 体积 | 活跃度 | 适合度 |
|--------|-----|-----------------|---------|------|---------|------|--------|--------|
| **Goja** | ✓ Pure Go | ✓ | ES5.1 + 大部分 ES6<br>有 Promise/async,无 ESM | 中(6-7x otto) | Interrupt 中断 | 小 | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ |
| Goja_nodejs | ✓ Pure Go | ✓ | ES5.1 + Node API<br>有 async | 中 | Interrupt | 小 | ⭐⭐⭐ | ⭐⭐⭐ |
| V8go | ✗ cgo | ✗(困难) | ES6+ 完整 | 高 | Context 隔离 | 大 | ⭐⭐⭐ | ⭐⭐ |
| QuickJS CGO | ✗ cgo | ❓未确认 | ES2020<br>async/await | 中-高 | 无 | 中 | ⭐⭐ | ⭐⭐ |
| **QJS(WASM)** | ✓ Pure Go | ✓ | ES2023<br>async/await | 中(1.5x goja) | WASM 沙箱 | 中 | ⭐⭐⭐ | ⭐⭐⭐⭐ |
| **Wazero** | ✓ Pure Go | ✓ | 任意 WASM 语言 | 高(compiler 模式) | WASM 沙箱 | 小 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| Extism | ✓ Pure Go(via Wazero) | ✓ | 任意 WASM 语言 | 高(via Wazero) | WASM + 框架层 | 中 | ⭐⭐⭐ | ⭐⭐⭐⭐ |
| Wasmtime-go | ✗ cgo | ✗ | 任意 WASM | 高(JIT) | WASM 沙箱 | 大 | ⭐⭐⭐ | ✗ |
| Wasmer-go | ✗ cgo | ✗ | 任意 WASM | 高(多编译器) | WASM 沙箱 | 大 | ⭐⭐⭐ | ✗ |
| **GopherLua** | ✓ Pure Go | ✓ | Lua 5.1 | 中-低(非 python) | 库选择性加载 | 小 | ⭐⭐⭐⭐ | ⭐⭐⭐ |
| go-lua | ✓ Pure Go | ✓ | Lua 5.2 | 低(6x C Lua) | 库选择 | 小 | ⭐⭐⭐ | ⭐⭐ |
| Starlark | ✓ Pure Go | ✓ | Python 子集 | 中 | 配置语言<br>表达式限制 | 中 | ⭐⭐⭐⭐ | ⭐⭐⭐ |
| Yaegi | ✓ Pure Go | ✓ | Go 完整 | 低(解释) | unsafe 排除 | 中 | ⭐⭐⭐⭐ | ⭐⭐ |
| Expr | ✓ Pure Go | ✓ | 表达式只 | 高(编译 VM) | 确定性执行 | 小 | ⭐⭐⭐⭐ | ⭐⭐ |
| CEL-Go | ✓ Pure Go | ✓ | 表达式只 | 高(线性) | Side-effect-free | 小 | ⭐⭐⭐⭐ | ⭐⭐ |
| go-plugin | ✓ Pure Go | ✗ | 任意语言 | 低(RPC 开销) | 进程隔离 | 中 | ⭐⭐⭐⭐ | ✗ |

**分值说明**:
- **cgo**: Pure Go ✓ 为佳(交叉编译简单)
- **Android 交叉编译**: ✓ 支持 / ✗ 不支持 / ❓ 未确认
- **性能**: 相对量级;实际需跑 benchmark
- **沙箱**: 越严格越好(LinPlayer 需要防止恶意插件)
- **活跃度**: star/commit/update 频率综合评估

---

## 推荐与理由

### 第一选择: **Wazero (纯 WASM 方案)**

**理由:**
1. **交叉编译完美**: 纯 Go,无 cgo,支持 Android 全 ABI(arm64、arm、amd64)
2. **沙箱最强**: WASM 原生隔离,防止恶意插件访问文件系统、网络、系统调用
3. **多语言插件生态**: Rust、Go(TinyGo)、C、Zig、AssemblyScript 都可编译 WASM
4. **生产验证**: Tetragon、Cilium、Trivy、k6、OPA 等知名项目生产在用
5. **二进制体积**: Pure Go,库增量最小
6. **执行性能**: Compiler 模式(amd64/arm64)有明显加速,Interpreter 模式兼容所有架构

**代价:**
- 插件必须编译成 WASM(不能直接跑 JavaScript/Lua 源码)
- TypeScript 需要编译工具链(但比纯 JS 好)
- 学习曲线(WASM 工具链相对新)

**适配方案:**
- 官方插件用 Rust(编译到 WASM,性能最优)
- 用户插件支持 TinyGo 或 Rust
- 预留 WASI 接口规范,后续可加 JavaScript/Lua 分支编译到 WASM

**出处:** https://github.com/wazero/wazero, https://www.systemshardening.com/articles/wasm/wazero-hardening/

---

### 第二选择: **QJS(WASM 版本的 QuickJS)**

**理由:**
1. **Pure Go**: 基于 Wazero,零 cgo
2. **JavaScript 原生支持**: ES2023、async/await、字节码编译
3. **性能可接受**: 比 Goja 快 1.5x,足以应对大多数脚本
4. **安全隔离**: WASM 沙箱 + 文件系统隔离
5. **启动快**: 字节码缓存,支持预编译

**代价:**
- 相比 Goja,多了 WASM 编译步骤(但 QJS 本身已编译,用户脚本仍可动态执行)
- 库选择不如 Goja 多(Node API 支持有限)

**适配:**
- 前端配置脚本、规则引擎用 QJS
- 大量计算型脚本仍用 Wazero/Rust
- 渐进式迁移: 先用 QJS,后续高性能部分改 Wasm/Rust

**出处:** https://github.com/fastschema/qjs

---

### 备选: **Goja(用于轻量快速集成)**

**适用场景:**
- 要求纯 Go、无 wasm 层、调试栈直接的场景
- 优先快速原型,性能不是瓶颈
- 前端配置、规则引擎

**限制:**
- ES5.1 + 部分 ES6
- 无原生 ES module
- 并发限制(单 goroutine per Runtime)

**出处:** https://github.com/dop251/goja

---

## 不推荐的理由

**V8go / wasmtime-go / wasmer-go**: 都需要 cgo,跨编译到 Android 困难或不支持

**go-lua / GopherLua**: Lua 作为主插件语言生态不如 JavaScript/WASM

**Starlark**: 配置语言,功能受限,不适合通用插件

**Expr / CEL**: 仅表达式求值,不能写完整逻辑

**go-plugin**: 进程外方案,不适合移动端和嵌入式需求

**Yaegi**: 纯解释 Go 代码,性能低,且 Go 依赖不利于用户编写插件

---

