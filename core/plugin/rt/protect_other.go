//go:build !windows

package rt

// ponytail: Android Keystore / Linux Secret Service 要走 JNI / D-Bus,先用「应用密钥加密文件」
// 这条 SPEC 13.1 写明的退路:主密钥明文放在应用私有目录。接系统密钥库时只改这两个函数。

func protect(b []byte) ([]byte, error)   { return append([]byte(nil), b...), nil }
func unprotect(b []byte) ([]byte, error) { return append([]byte(nil), b...), nil }
