package rt

// 由 tools/sdkgen/gen.mjs 从 docs/plugin-system/api/plugin-sdk.d.ts 生成(D514)。
// **不要手改**:改定义源,然后 `pnpm --dir tools/sdkgen gen`。
// 谁在用:sdk_contract_test.go 拿它和运行时真挂上的 __linplayer_sdk 逐名比对。

// SDKSpec 定义源里每个命名空间声明了哪些成员。
var SDKSpec = map[string][]string{
	"app": {"capabilities", "devMode", "formFactor", "getSetting", "locale", "platform", "reducedMotion", "setSetting", "version"},
	"assets": {"readBytes", "readText", "url"},
	"canvas": {"renderToPng"},
	"cast": {"open"},
	"cookies": {"clear", "get", "set"},
	"crypt": {"aesDecrypt", "aesEncrypt", "base64Decode", "base64Encode", "desDecrypt", "desEncrypt", "gbkDecode", "gbkEncode", "hmac", "md5", "rsaDecrypt", "rsaEncrypt", "sha1", "sha256"},
	"desktop": {"setTaskbarBadge", "setTaskbarProgress", "setWindowTitle"},
	"download": {"enqueue"},
	"emby": {"request"},
	"events": {"on"},
	"ext": {"ensure", "status"},
	"files": {"cache", "data", "pick", "save", "saveImage"},
	"html": {"parse", "pd", "pdfa", "pdfh", "xpath"},
	"js": {"bundle", "createContext"},
	"media": {"favorites", "getChildren", "getItem", "getLatest", "history", "search", "setFavorite", "setPlayed", "setProgress"},
	"nav": {"back", "onBack", "push", "replace", "setBadge", "setPageOptions"},
	"oauth": {"authorize", "refresh"},
	"player": {"addSubtitle", "command", "extractFrames", "get", "getSubtitleText", "observe", "openPanel", "play", "playUrl", "screenshot", "set", "setOsdVisible", "state", "tracks", "transcribe"},
	"proxy": {"route", "url"},
	"registry": {"list", "put", "remove", "watch"},
	"secrets": {"get", "remove", "set"},
	"servers": {"current", "list"},
	"settings": {"get", "onChange", "set"},
	"sources": {"add", "list", "remove", "update"},
	"spider": {"compatProxyPort", "load", "supported"},
	"storage": {"get", "keys", "remove", "set"},
	"system": {"clipboard", "openApp", "openUrl", "share"},
	"tvChannels": {"publish"},
	"ui": {"confirm", "notify", "openWindow", "prompt", "select", "toast"},
	"wallpaper": {"set"},
	"webview": {"evaluate", "open", "sniff"},
	"widgets": {"update"},
}
