package api

import (
	"context"
	"net/http"
	"strings"

	"github.com/everything-personal/eve/internal/auth"
)

type ctxKey string

const claimsCtxKey ctxKey = "claims"

// requireAccessToken 从 Authorization: Bearer 解析 typ=access 令牌并放入上下文；
// 具体 scope 校验由后续 requireScope 中间件按端点完成（最小权限分层）。
func (s *Server) requireAccessToken(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		header := r.Header.Get("Authorization")
		token, ok := strings.CutPrefix(header, "Bearer ")
		if !ok || token == "" {
			writeError(w, http.StatusUnauthorized, "unauthorized", "缺少访问令牌")
			return
		}
		claims, err := s.auth.ParseAccessToken(token)
		if err != nil {
			writeError(w, http.StatusUnauthorized, "unauthorized", "访问令牌无效或已过期")
			return
		}
		ctx := context.WithValue(r.Context(), claimsCtxKey, claims)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

// requireScope 要求当前 access 令牌的 scope 属于允许集合。
// pending 设备访问 approved-only 端点时返回 403 device_pending，
// 客户端据此引导用户前往"等待审批"页而非当成未登录重新登录。
func (s *Server) requireScope(allowed ...string) func(http.Handler) http.Handler {
	allow := make(map[string]struct{}, len(allowed))
	for _, sc := range allowed {
		allow[sc] = struct{}{}
	}
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			claims := claimsFrom(r)
			if _, ok := allow[claims.Scope]; !ok {
				if claims.Scope == auth.ScopePending {
					writeError(w, http.StatusForbidden, "device_pending", "设备尚未通过审批")
					return
				}
				writeError(w, http.StatusForbidden, "forbidden", "当前会话无权访问该接口")
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

// requireTypedToken 校验非 access 类短期令牌（recovery/mfa/events），
// 令牌可经 Authorization 头或 ?token= 查询参数传递（后者仅为 EventSource 无法
// 自定义请求头的 SSE 场景保留，events 令牌 5 分钟有效且只能建连）。
func (s *Server) requireTypedToken(typ, scope string) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			token := r.URL.Query().Get("token")
			if token == "" {
				if t, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer "); ok {
					token = t
				}
			}
			if token == "" {
				writeError(w, http.StatusUnauthorized, "unauthorized", "缺少会话令牌")
				return
			}
			claims, err := s.auth.ParseTyped(token, typ)
			if err != nil || claims.Scope != scope {
				writeError(w, http.StatusUnauthorized, "unauthorized", "会话令牌无效或已过期")
				return
			}
			ctx := context.WithValue(r.Context(), claimsCtxKey, claims)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

// requireEventsAccess 是 /events SSE 的专用双入口鉴权（AC-8）：
//   - 携带 ?token= 查询参数（EventSource 无法自定义请求头）：只接受 typ=events/scope=events
//     的 5 分钟短期令牌，且该令牌无法用于任何其他端点；
//   - 否则使用 Authorization: Bearer：只接受 scope=approved 的正式 access 令牌。
//
// 两种入口严格互斥，防止 pending/recovery/mfa 等会话借道 SSE。
func (s *Server) requireEventsAccess(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var claims auth.Claims
		if q := r.URL.Query().Get("token"); q != "" {
			// events 令牌 typ 与 scope 同名（同 recovery/mfa 的既有约定）。
			c, err := s.auth.ParseTyped(q, auth.ScopeEvents)
			if err != nil || c.Scope != auth.ScopeEvents {
				writeError(w, http.StatusUnauthorized, "unauthorized", "事件令牌无效或已过期")
				return
			}
			claims = c
		} else {
			header := r.Header.Get("Authorization")
			token, ok := strings.CutPrefix(header, "Bearer ")
			if !ok || token == "" {
				writeError(w, http.StatusUnauthorized, "unauthorized", "缺少访问令牌")
				return
			}
			c, err := s.auth.ParseAccessToken(token)
			if err != nil {
				writeError(w, http.StatusUnauthorized, "unauthorized", "访问令牌无效或已过期")
				return
			}
			if c.Scope != auth.ScopeApproved {
				writeError(w, http.StatusForbidden, "forbidden", "仅已批准设备可订阅事件")
				return
			}
			claims = c
		}
		ctx := context.WithValue(r.Context(), claimsCtxKey, claims)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func claimsFrom(r *http.Request) auth.Claims {
	return r.Context().Value(claimsCtxKey).(auth.Claims)
}
