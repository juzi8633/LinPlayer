//go:build android

package shaders

// 安卓封顶在 medium:手机 GPU 跑 VL 那条链会把帧率打到个位数。
const platformTier = TierMedium
