package shaders

// 超分链的算力档。**平台定,不给用户选**【用户定 2026-09-07:
// 「移动端最高到 medium,PC 最高到 high」】。
//
// 卡的不是解码(安卓这边 hwdec 走 MediaCodec,和桌面一样有硬解),
// 是着色器:手机 GPU 是集显且有温度墙,VL 那条链在它上面就是
// 用户说的「超级无敌卡」。给一个「明知会卡」的选项等于把调参外包给用户。
type Tier int

const (
	// TierMedium 移动端封顶:放大只到 Anime4K CNN x2 (M)。
	TierMedium Tier = iota
	// TierHigh 桌面封顶:最高一档可以上 CNN x2 (VL) 重链。
	TierHigh
)

func (t Tier) String() string {
	if t == TierHigh {
		return "high"
	}
	return "medium"
}

// CurrentTier 本次构建所在平台的算力档。安卓 medium,其余 high。
func CurrentTier() Tier { return platformTier }
