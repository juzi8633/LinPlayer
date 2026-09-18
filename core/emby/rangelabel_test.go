package emby

import "testing"

// 列表小字的动态范围:DV 要认得出来(只看 VideoRange 会把它说成 HDR),SDR 不写。
func Test动态范围短名(t *testing.T) {
	s := func(v string) *string { return &v }
	cases := []struct {
		in   rawMediaStream
		want string
	}{
		{rawMediaStream{VideoRange: s("HDR"), VideoRangeType: s("DOVI")}, "DV"},
		{rawMediaStream{VideoRange: s("HDR"), Codec: s("hevc"), Profile: s("Dolby Vision Profile 8")}, "DV"},
		{rawMediaStream{VideoRange: s("HDR"), VideoRangeType: s("HDR10Plus")}, "HDR10+"},
		{rawMediaStream{VideoRange: s("HDR"), VideoRangeType: s("HDR10")}, "HDR10"},
		{rawMediaStream{VideoRange: s("HDR")}, "HDR"},
		{rawMediaStream{VideoRange: s("SDR")}, ""},
	}
	for _, c := range cases {
		got := ""
		if p := rangeLabel(c.in); p != nil {
			got = *p
		}
		if got != c.want {
			t.Errorf("%+v → %q,要 %q", c.in, got, c.want)
		}
	}
}
