package player

// 弹幕的**数据**这一半:一条弹幕长什么样、画布多大、一条滚动弹幕走多久。
// 排版在 danmakustyle.go,绘制在两端的 UI 层。
//
// ☠☠ **绘制曾经在这里,走 mpv 的 `osd-overlay`,已经整段删掉。** 三条理由,
// 每一条单独都够:
//   ① 那条路的终点是 mpv。Exo 内核下 mpv 手里没有这一片,而 `player.danmakuSet`
//      照样返回成功、照样报「挂上 N 条」—— 一个装成功能的接口。
//   ② 位置是从 `time-pos` 插出来的,而 time-pos **只在视频帧边界更新**。
//      24fps 片源上弹幕的平滑度被钉死在 24Hz,uosc_danmaku 要靠插一道
//      `vf append @danmaku:fps` 滤镜绕，代价是多一遍解码。
//   ③ 每秒 120 条 `osd-overlay` 命令全都排在 **mpv 的核心线程**上,而那条线程
//      同时在解封装和解字幕。图形字幕(PGS)那种解码量大的最先被挤掉。
// UI 层自己有帧回调、自己知道播放位置,以上三条一条都不存在。

// 一条弹幕。字段照 B 站 XML 的 p 属性(载体格式见 SPEC §7.5.1)。
type danmakuItem struct {
	Time  float64 // 出现时刻(秒)
	Mode  int     // 1=滚动 4=底部 5=顶部
	Color uint32
	Text  string
	// Count 合并重复弹幕后这一条代表几条。未合并恒为 1。
	Count int
	lane  int     // 轨道,布局时算(见 danmakustyle.go 的 layout)
	w     float64 // 估算的文本像素宽,布局和插值都要用
}

// 排版画布。两端拿到的坐标都在这个尺寸里,自己按实际控件尺寸缩。
//
// ★ 定死 1920×1080 而不是跟着窗口走:窗口一变就要重排一次,而重排的输入是
// 上万条弹幕。缩放交给 UI 的一次矩阵变换,那是免费的。
const (
	danmakuResX = 1920
	danmakuResY = 1080
	laneHeight  = 54
	rollSeconds = 8.0 // 一条滚动弹幕从右边走到左边用多久(speed=1 时)
)
