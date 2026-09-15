// Package api 组装 HTTP 路由：/api/v1 下的 JSON API、SSE，以及内嵌网页静态资源。
package api

import (
	"context"
	"database/sql"
	"io/fs"
	"net/http"
	"strings"
	"time"

	"github.com/everything-personal/eve/internal/attachments"
	"github.com/everything-personal/eve/internal/auth"
	"github.com/everything-personal/eve/internal/config"
	"github.com/everything-personal/eve/internal/sync"
	"github.com/everything-personal/eve/internal/vault"
	"github.com/everything-personal/eve/web"
	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
)

// Version 在发布时由 -ldflags 注入。
var Version = "dev"

// Server 持有全部 HTTP 依赖。
type Server struct {
	cfg         config.Config
	db          *sql.DB
	auth        *auth.Service
	vault       *vault.Store
	attachments *attachments.Store
	hub         *sync.Hub
}

// New 创建 API 服务器。
func New(cfg config.Config, database *sql.DB, authSvc *auth.Service, records *vault.Store, files *attachments.Store, hub *sync.Hub) *Server {
	return &Server{cfg: cfg, db: database, auth: authSvc, vault: records, attachments: files, hub: hub}
}

// Handler 返回完整路由。
func (s *Server) Handler() http.Handler {
	r := chi.NewRouter()
	r.Use(middleware.RealIP)
	r.Use(middleware.Recoverer)
	r.Use(middleware.Timeout(60 * time.Second))

	r.Route("/api/v1", func(r chi.Router) {
		r.Get("/health", s.health)
		r.Post("/auth/register", s.register)
		r.Post("/auth/login", s.login)
		r.Get("/auth/parameters", s.loginParameters)
		r.Post("/auth/refresh", s.refresh)

		r.Group(func(r chi.Router) {
			r.Use(s.requireAccessToken)
			r.Post("/records/batch", s.upsertRecords)
			r.Get("/records", s.listRecords)
			r.Put("/attachments/{id}", s.uploadAttachment)
			r.Get("/attachments/{id}", s.downloadAttachment)
			r.Get("/events", s.events)
		})
	})

	r.Mount("/", s.spaHandler())
	return r
}

func (s *Server) health(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"status":  "ok",
		"version": Version,
		"time":    time.Now().UnixMilli(),
	})
}

func (s *Server) spaHandler() http.Handler {
	dist, err := fs.Sub(web.Dist, "dist")
	if err != nil {
		panic(err)
	}
	fileServer := http.FileServer(http.FS(dist))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/api/") {
			writeError(w, http.StatusNotFound, "not_found", "接口不存在")
			return
		}
		// 资源存在则直接返回；否则回退 index.html（SPA 路由）。
		name := strings.TrimPrefix(r.URL.Path, "/")
		if name != "" {
			if f, err := dist.Open(name); err == nil {
				f.Close()
				fileServer.ServeHTTP(w, r)
				return
			}
		}
		index, err := dist.Open("index.html")
		if err != nil {
			// 网页尚未构建（dist/ 为空占位）。
			w.Header().Set("Content-Type", "text/plain; charset=utf-8")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte("Everything 服务端运行中。网页端尚未构建：请在 web/ 目录执行 npm run build，" +
				"或在开发模式运行 npm run dev 并访问 Vite 地址。API 入口：/api/v1\n"))
			return
		}
		index.Close()
		// SPA 回退到 /index.html。
		r2 := r.Clone(context.Background())
		r2.URL.Path = "/"
		fileServer.ServeHTTP(w, r2)
	})
}
