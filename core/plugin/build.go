package plugin

// 插件打包:lp build 与应用内开发模式共用这一份(D42 D145),打包结果一致。

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/evanw/esbuild/pkg/api"

	"linplayer/core/plugin/rt"
)

// SDKModule 插件 import 的 SDK 包名(D207)。
const SDKModule = "@linplayer/plugin-sdk"

// BuildResult 打包产物。
type BuildResult struct {
	JS        []byte
	SourceMap []byte
	Warnings  []string
}

// EntryOf 找源码入口:src/main.tsx > src/main.ts > src/main.js > main.js(已打好的包)。
func EntryOf(dir string) (string, error) {
	for _, p := range []string{"src/main.tsx", "src/main.ts", "src/main.jsx", "src/main.js"} {
		if _, err := os.Stat(filepath.Join(dir, p)); err == nil {
			return filepath.Join(dir, p), nil
		}
	}
	return "", errors.New("找不到源码入口(src/main.tsx 或 src/main.ts)")
}

// Build 把插件源码打成单文件 ES2017 IIFE(SPEC 4.1)。SDK 映射到宿主注入的全局,不打进包。
func Build(dir string) (*BuildResult, error) {
	dir, err := filepath.Abs(dir) // esbuild 要求工作目录是绝对路径
	if err != nil {
		return nil, err
	}
	entry, err := EntryOf(dir)
	if err != nil {
		return nil, err
	}
	sdk := api.Plugin{Name: "lp-sdk", Setup: func(b api.PluginBuild) {
		b.OnResolve(api.OnResolveOptions{Filter: `^@linplayer/plugin-sdk$`}, func(api.OnResolveArgs) (api.OnResolveResult, error) {
			return api.OnResolveResult{Path: SDKModule, Namespace: "lp-sdk"}, nil
		})
		b.OnLoad(api.OnLoadOptions{Filter: ".*", Namespace: "lp-sdk"}, func(api.OnLoadArgs) (api.OnLoadResult, error) {
			src := "module.exports = globalThis." + rt.SDKGlobal + ";"
			return api.OnLoadResult{Contents: &src, Loader: api.LoaderJS}, nil
		})
	}}
	res := api.Build(api.BuildOptions{
		EntryPoints:       []string{entry},
		Bundle:            true,
		Format:            api.FormatIIFE,
		Target:            api.ES2017,
		Platform:          api.PlatformNeutral,
		MainFields:        []string{"module", "main"},
		JSXFactory:        "h",
		JSXFragment:       "Fragment",
		Sourcemap:         api.SourceMapExternal,
		SourcesContent:    api.SourcesContentInclude,
		Outfile:           filepath.Join(dir, "dist", "main.js"),
		AbsWorkingDir:     dir,
		Plugins:           []api.Plugin{sdk},
		Write:             false,
		LogLevel:          api.LogLevelSilent,
		LegalComments:     api.LegalCommentsEndOfFile,
		Charset:           api.CharsetUTF8,
		MinifySyntax:      false,
		MinifyWhitespace:  false,
		MinifyIdentifiers: false,
	})
	if len(res.Errors) > 0 {
		var msgs []string
		for _, e := range res.Errors {
			if e.Location != nil {
				msgs = append(msgs, fmt.Sprintf("%s:%d:%d: %s", e.Location.File, e.Location.Line, e.Location.Column, e.Text))
			} else {
				msgs = append(msgs, e.Text)
			}
		}
		return nil, errors.New("打包失败:\n" + strings.Join(msgs, "\n"))
	}
	out := &BuildResult{}
	for _, f := range res.OutputFiles {
		if strings.HasSuffix(f.Path, ".map") {
			out.SourceMap = f.Contents
		} else {
			out.JS = f.Contents
		}
	}
	for _, w := range res.Warnings {
		out.Warnings = append(out.Warnings, w.Text)
	}
	return out, nil
}
