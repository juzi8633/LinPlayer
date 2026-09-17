package bus

import (
	"errors"
	"testing"
)

// 带占位符的要格式化;不带的第一个 string 参数当 Detail(老调法不能坏)。
func TestNewErr两种调法(t *testing.T) {
	if e := NewErr(EInternal, "%v", errors.New("磁盘满了")); e.Msg != "磁盘满了" {
		t.Fatalf("没格式化:%q", e.Msg)
	}
	if e := NewErr(EInvalid, "默认倍速只支持 %.2f~%.2f×", 0.25, 4.0); e.Msg != "默认倍速只支持 0.25~4.00×" {
		t.Fatalf("没格式化:%q", e.Msg)
	}
	if e := NewErr(ENotFound, "没有这个服务器", "srv-1"); e.Msg != "没有这个服务器" || e.Detail != "srv-1" {
		t.Fatalf("老调法坏了:%+v", e)
	}
}
