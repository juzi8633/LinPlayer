package blocklist

import (
	"os"
	"testing"

	"linplayer/core/paths"
)

// ☠ 屏蔽名单必须落盘。
//
// 迁移到 Go 之后这个包只有一个**包级切片**,`Set` 写完就完了 ——
// 重启一次名单全空,而且一声不吭。对「屏蔽媒体库 → 卡片变灰 → 右键恢复」
// 这条闭环更致命:灰卡下次开机自己变回来,用户只会认为恢复键是坏的。
//
// ★ 这条用例是**反向注入验过的**:把 Set 里那句 save(snap) 去掉,它当场变红
// (「重启后名单对不上: []」)。不先验一遍的话它是条恒绿的假门禁 ——
// 名单本来就还在内存里,不把内存清掉怎么读都是对的,所以下面那句
// Replace(nil) 是这条用例的全部要害。
func Test屏蔽名单要落盘(t *testing.T) {
	paths.SetRoot(t.TempDir())
	Replace(nil)

	Set("lib-1", "电影", true)
	Set("lib-2", "剧集", true)
	Set("lib-2", "剧集", false) // 解除过的不能留在盘上

	// 模拟重启:内存清空,只剩磁盘上那一份
	Replace(nil)
	if len(List()) != 0 {
		t.Fatal("前置条件没成立:内存名单没清干净,这条用例测不出落盘")
	}

	Load()

	got := List()
	if len(got) != 1 || got[0].ID != "lib-1" || got[0].Name != "电影" {
		t.Fatalf("重启后名单对不上: %+v", got)
	}
	if got[0].At == 0 {
		t.Fatal("没记屏蔽时间 —— 解除列表要靠它排序")
	}
	if !IsBlockedID("lib-1") {
		t.Fatal("重启后 lib-1 不再算被屏蔽")
	}
	if IsBlockedID("lib-2") {
		t.Fatal("解除过的 lib-2 重启后又回来了")
	}
}

// 名单文件坏了只该让屏蔽失效,不该让调用方拿到半张表或者炸掉。
func Test名单文件坏了就当空(t *testing.T) {
	paths.SetRoot(t.TempDir())
	Replace([]Entry{{ID: "旧的", Name: "旧的"}})

	if err := os.WriteFile(File(), []byte("{不是合法 JSON"), 0o644); err != nil {
		t.Fatal(err)
	}
	Load()

	// 解析失败时 Load 直接返回,不去动内存里那份 —— 坏文件不该顺手把好名单清掉
	if len(List()) != 1 {
		t.Fatalf("坏文件把内存里的名单也弄没了: %+v", List())
	}
}
