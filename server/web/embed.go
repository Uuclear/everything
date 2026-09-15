// Package web 通过 go:embed 内嵌网页端构建产物（web/ 目录 build 后输出到 dist/）。
// 未构建时 dist/ 仅含 .gitkeep，服务仍可启动，API 正常，根路径返回构建提示。
package web

import "embed"

// Dist 是嵌入的静态资源文件系统。
//
//go:embed all:dist
var Dist embed.FS
