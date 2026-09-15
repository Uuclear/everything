package api

import (
	"context"
	"net/http"
	"strings"

	"github.com/everything-personal/eve/internal/auth"
)

type ctxKey string

const claimsCtxKey ctxKey = "claims"

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

func claimsFrom(r *http.Request) auth.Claims {
	return r.Context().Value(claimsCtxKey).(auth.Claims)
}
