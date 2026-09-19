package rt

import (
	"syscall"
	"unsafe"
)

// 主密钥用 DPAPI(CryptProtectData)保护:换 Windows 账号或换机器就解不开(SPEC 13.1)。

var (
	crypt32     = syscall.NewLazyDLL("crypt32.dll")
	kernel32    = syscall.NewLazyDLL("kernel32.dll")
	procProtect = crypt32.NewProc("CryptProtectData")
	procUnprot  = crypt32.NewProc("CryptUnprotectData")
	procFree    = kernel32.NewProc("LocalFree")
)

type dataBlob struct {
	n    uint32
	data *byte
}

func blobOf(b []byte) *dataBlob {
	if len(b) == 0 {
		return &dataBlob{}
	}
	return &dataBlob{n: uint32(len(b)), data: &b[0]}
}

func (b *dataBlob) bytes() []byte {
	out := make([]byte, b.n)
	copy(out, unsafe.Slice(b.data, b.n))
	return out
}

const cryptProtectUIForbidden = 0x1

func protect(b []byte) ([]byte, error) {
	var out dataBlob
	r, _, err := procProtect.Call(uintptr(unsafe.Pointer(blobOf(b))), 0, 0, 0, 0, cryptProtectUIForbidden, uintptr(unsafe.Pointer(&out)))
	if r == 0 {
		return nil, err
	}
	defer procFree.Call(uintptr(unsafe.Pointer(out.data)))
	return out.bytes(), nil
}

func unprotect(b []byte) ([]byte, error) {
	var out dataBlob
	r, _, err := procUnprot.Call(uintptr(unsafe.Pointer(blobOf(b))), 0, 0, 0, 0, cryptProtectUIForbidden, uintptr(unsafe.Pointer(&out)))
	if r == 0 {
		return nil, err
	}
	defer procFree.Call(uintptr(unsafe.Pointer(out.data)))
	return out.bytes(), nil
}
