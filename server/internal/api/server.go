// Package api 组装 HTTP 路由：/api/v1 下的 JSON API、SSE，以及内嵌网页静态资源。
package api

import (
	"context"
	"database/sql"
	"io/fs"
	"net/http"
	"strings"
	"time"

	"github.com/everything-personal/eve/internal/agent"
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
	cfg       config.Config
	db        *sql.DB
	auth      *auth.Service
	vault     *vault.Store
	locations *vault.LocationStore // 阶段 4a 轨迹块月表存储（与 records 同库注入）
	hub       *sync.Hub
	limiter   *authLimiter

	// agent* 阶段 6 字段：均为 nil 时 Agent 路由自动不挂载（保留向后兼容）。
	agentRegistry    *agent.Registry
	agentProxy       *agent.Proxy
	agentSession     *agent.SessionManager // 阶段 6 Task 4：解锁 token 签发 / 校验
}

// New 创建 API 服务器（不含 Agent；既有调用方行为完全保持）。
func New(cfg config.Config, database *sql.DB, authSvc *auth.Service, records *vault.Store, hub *sync.Hub) *Server {
	return &Server{
		cfg:       cfg,
		db:        database,
		auth:      authSvc,
		vault:     records,
		locations: vault.NewLocationStore(database),
		hub:       hub,
		limiter:   newAuthLimiter(),
	}
}

// WithAgent 注入 Agent 依赖，返回 Server 自身便于链式调用。
//
// 调用时机：主入口（main.go）在调用 New() 后再 WithAgent(...)；不在 New 入参列表
// 增加字段，避免阶段 6 之前的调用方（含测试 harness）需要改动。
//
// agentProxy 为 nil 时调用方需自行构造；传入 nil Registry 则视为禁用 Agent。
func (s *Server) WithAgent(reg *agent.Registry, proxy *agent.Proxy) *Server {
	s.agentRegistry = reg
	s.agentProxy = proxy
	return s
}

// WithAgentSession 注入 Agent 会话管理器（阶段 6 Task 4：解锁 token）。
// 独立于 WithAgent，便于解锁 token 密钥派生与 auth JWT 解耦（密钥从
// cfg.DataDir/agent_session.key 派生，不与 auth.Service.jwt.key 共用）。
//
// agentSession 为 nil 时 /agent/unlock /agent/refresh /agent/lock 路由自动不挂载。
func (s *Server) WithAgentSession(mgr *agent.SessionManager) *Server {
	s.agentSession = mgr
	return s
}

// Handler 返回完整路由。
func (s *Server) Handler() http.Handler {
	r := chi.NewRouter()
	r.Use(middleware.RealIP)
	r.Use(middleware.Recoverer)

	r.Route("/api/v1", func(r chi.Router) {
		// SSE 长连接独立于 60s 通用请求超时，使用双入口专用鉴权
		//（Authorization approved access 或 ?token= events 短期令牌）。
		r.With(s.requireEventsAccess).Get("/events", s.events)

		// 其余 JSON 接口统一 60s 请求超时。
		r.Group(func(r chi.Router) {
			r.Use(middleware.Timeout(60 * time.Second))

			r.Get("/health", s.health)
			r.Post("/auth/register", s.register)
			r.Post("/auth/login", s.login)
			r.Get("/auth/parameters", s.loginParameters)
			r.Post("/auth/refresh", s.refresh)
			r.Post("/auth/recovery/start", s.recoveryStart)
			// 恢复重置：仅接受 10 分钟 recovery 短期会话（不与 access 混用）。
			r.With(s.requireTypedToken(auth.ScopeRecovery, auth.ScopeRecovery)).
				Post("/auth/recovery/reset", s.recoveryReset)
			// TOTP 验证：仅接受 5 分钟 mfa 短期会话接续登录。
			r.With(s.requireTypedToken(auth.ScopeMFA, auth.ScopeMFA)).
				Post("/auth/totp/verify", s.totpVerify)

			r.Group(func(r chi.Router) {
				r.Use(s.requireAccessToken)

				// 已批准设备：完整资料库、设备与配对管理。
				r.Group(func(r chi.Router) {
					r.Use(s.requireScope(auth.ScopeApproved))
					r.Post("/records/batch", s.upsertRecords)
					r.Get("/records", s.listRecords)
					// 阶段 4a 位置轨迹：批量上行 / 范围查询 / 范围删除（FR-7）。
					r.Post("/locations/batch", s.uploadLocationBlocks)
					r.Get("/locations", s.listLocationBlocks)
					r.Delete("/locations", s.deleteLocationBlocks)
					r.Post("/auth/events-token", s.eventsToken)
					r.Post("/auth/password/change", s.changePassword)
					r.Get("/auth/totp", s.totpStatus)
					r.Post("/auth/totp/setup", s.totpSetup)
					r.Post("/auth/totp/enable", s.totpEnable)
					r.Post("/auth/totp/disable", s.totpDisable)
					r.Get("/auth/devices", s.listDevices)
					r.Post("/auth/devices/{id}/revoke", s.revokeDevice)
					r.Get("/auth/pairings", s.listPairings)
					r.Post("/auth/pairings/{id}/approve", s.approvePairing)
					r.Post("/auth/pairings/{id}/reject", s.rejectPairing)
				})

				// 待审批设备：只能查询自身配对状态（直到获批换发正式令牌）。
				r.Group(func(r chi.Router) {
					r.Use(s.requireScope(auth.ScopePending))
					r.Get("/auth/pairing/status", s.pairingStatus)
				})
			})

			// 阶段 6 路由：AI Agent（仅 approved 设备可用）。
			// 当 agentProxy==nil 时不挂载（保留旧版服务端继续运行）。
			if s.agentProxy != nil || s.agentSession != nil {
				r.Group(func(r chi.Router) {
					r.Use(s.requireScope(auth.ScopeApproved))
					r.Route("/agent", func(r chi.Router) {
						// Task 4：解锁 token 路由（仅在 agentSession 非 nil 时挂载）。
						if s.agentSession != nil {
							r.Post("/unlock", s.agentUnlock)
							r.Post("/refresh", s.agentRefresh)
							r.Post("/lock", s.agentLock)
						}
						// Task 2：LLM 调用路由（仅在 agentProxy 非 nil 时挂载）。
						if s.agentProxy != nil {
							r.Post("/chat", s.agentChat)
							r.Post("/tool-result", s.agentToolResult)
							r.Post("/cancel", s.agentCancel)
							r.Get("/sessions", s.agentListSessions)
						}
					})
				})
			}
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
