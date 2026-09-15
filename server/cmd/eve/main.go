// Eve 是 Everything 人生操作系统的服务端入口：单二进制 + 内嵌 SQLite + 内嵌网页。
package main

import (
	"context"
	"errors"
	"flag"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/everything-personal/eve/internal/api"
	"github.com/everything-personal/eve/internal/attachments"
	"github.com/everything-personal/eve/internal/auth"
	"github.com/everything-personal/eve/internal/config"
	"github.com/everything-personal/eve/internal/db"
	"github.com/everything-personal/eve/internal/sync"
	"github.com/everything-personal/eve/internal/vault"
)

func main() {
	configPath := flag.String("config", "", "配置文件路径（默认 <data_dir>/config.yaml）")
	addr := flag.String("addr", "", "监听地址，覆盖配置，如 :8787")
	dataDir := flag.String("data-dir", "", "数据目录，覆盖配置")
	flag.Parse()

	cfg, err := config.Load(*configPath)
	if err != nil {
		slog.Error("加载配置失败", "err", err)
		os.Exit(1)
	}
	if *addr != "" {
		cfg.Addr = *addr
	}
	if *dataDir != "" {
		cfg.DataDir = *dataDir
		if err := os.MkdirAll(cfg.DataDir, 0o700); err != nil {
			slog.Error("创建数据目录失败", "err", err)
			os.Exit(1)
		}
	}

	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	slog.SetDefault(logger)

	database, err := db.Open(cfg.DataDir)
	if err != nil {
		logger.Error("初始化数据库失败", "err", err)
		os.Exit(1)
	}
	defer database.Close()

	authSvc, err := auth.New(database, cfg)
	if err != nil {
		logger.Error("初始化认证服务失败", "err", err)
		os.Exit(1)
	}
	records := vault.New(database)
	files, err := attachments.New(database, cfg.DataDir+"/attachments")
	if err != nil {
		logger.Error("初始化附件存储失败", "err", err)
		os.Exit(1)
	}
	hub := sync.New()
	srv := &http.Server{
		Addr:              cfg.Addr,
		Handler:           api.New(cfg, database, authSvc, records, files, hub).Handler(),
		ReadHeaderTimeout: 10 * time.Second,
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() {
		logger.Info("Everything 服务端启动", "addr", cfg.Addr, "data", cfg.DataDir, "registration", cfg.Registration)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			logger.Error("HTTP 服务异常", "err", err)
			os.Exit(1)
		}
	}()

	<-ctx.Done()
	logger.Info("正在关闭服务…")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		logger.Error("优雅关闭失败", "err", err)
	}
}
