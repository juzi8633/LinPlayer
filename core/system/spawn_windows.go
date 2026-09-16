//go:build windows

package system

import (
	"os/exec"
	"syscall"
)

// createNoWindow = CREATE_NO_WINDOW。让子进程压根不分配控制台。
const createNoWindow = 0x08000000

// hideConsole 不给覆盖脚本弹黑框。
//
// ☠ `powershell.exe` 是**控制台子系统**程序:传给它的 `-WindowStyle Hidden`
// 是脚本跑起来之后自己去藏窗口,而窗口在那之前已经由系统建出来了。
// 覆盖脚本第一件事是 `Wait-Process -Timeout 120` 等本进程退出 ——
// 于是那个黑框从更新开始一直挂到装完,用户看到的就是
// 「更新完弹出一个命令行,还不会自己关」。
// 唯一管用的是在**创建进程那一刻**就不给它控制台。
func hideConsole(c *exec.Cmd) {
	c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: createNoWindow}
}
