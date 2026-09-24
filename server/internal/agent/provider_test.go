package agent

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// validReq 返回一个通过 ValidateRequest 的最小请求（含 1 条 user）。
// 测试中通过调整字段构造各类异常场景。
func validReq() ChatRequest {
	temp := 0.3
	maxTokens := 64
	return ChatRequest{
		Model: "gpt-4o-mini",
		Messages: []ChatMessage{
			{Role: RoleSystem, Content: "你是助手"},
			{Role: RoleUser, Content: "你好"},
		},
		Temperature: &temp,
		MaxTokens:   &maxTokens,
	}
}

// 用例 1：成功响应（HTTP 200 + 标准 OpenAI 协议）。
func TestOpenAICompat_ChatCompletion_Success(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// 验证请求路径。
		if r.URL.Path != "/chat/completions" {
			t.Errorf("路径=%q 期望 /chat/completions", r.URL.Path)
		}
		// 验证 Authorization 头被正确设置。
		if got := r.Header.Get("Authorization"); got != "Bearer test-key" {
			t.Errorf("Authorization=%q 期望 Bearer test-key", got)
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"id":    "chatcmpl-1",
			"model": "gpt-4o-mini",
			"choices": []map[string]any{
				{
					"index": 0,
					"message": map[string]any{
						"role":    "assistant",
						"content": "你好，有什么可以帮你的？",
					},
					"finish_reason": "stop",
				},
			},
			"usage": map[string]int{
				"prompt_tokens":     12,
				"completion_tokens": 8,
				"total_tokens":      20,
			},
		})
	}))
	defer srv.Close()

	p := NewOpenAICompat("openai", srv.URL, "test-key")
	resp, err := p.ChatCompletion(context.Background(), validReq())
	if err != nil {
		t.Fatalf("ChatCompletion 失败: %v", err)
	}
	if resp.ID != "chatcmpl-1" {
		t.Errorf("ID=%q 期望 chatcmpl-1", resp.ID)
	}
	if len(resp.Choices) != 1 {
		t.Fatalf("Choices 长度=%d 期望 1", len(resp.Choices))
	}
	if resp.Choices[0].Message.Role != RoleAssistant {
		t.Errorf("Role=%q 期望 assistant", resp.Choices[0].Message.Role)
	}
	if resp.Choices[0].FinishReason != FinishStop {
		t.Errorf("FinishReason=%q 期望 stop", resp.Choices[0].FinishReason)
	}
	if resp.Usage.TotalTokens != 20 {
		t.Errorf("TotalTokens=%d 期望 20", resp.Usage.TotalTokens)
	}
}

// 用例 2：成功响应 + 工具调用（验证 message.tool_calls 与 choice.tool_calls 双写）。
func TestOpenAICompat_ChatCompletion_ToolCalls(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// 读取请求体以确保 messages 包含 system。
		body, _ := io.ReadAll(r.Body)
		var got struct {
			Model    string        `json:"model"`
			Messages []ChatMessage `json:"messages"`
		}
		if err := json.Unmarshal(body, &got); err != nil {
			t.Errorf("解码请求体失败: %v", err)
		}
		if len(got.Messages) == 0 || got.Messages[0].Role != RoleSystem {
			t.Errorf("Messages[0] 必须是 system，实际=%+v", got.Messages)
		}
		w.Header().Set("Content-Type", "application/json")
		// OpenAI 协议本就 message.tool_calls 与 choice.tool_calls 双写，
		// 这里 mock 同时填充两个字段，验证消费端可以从任一处读到一致结果。
		tc := []map[string]any{
			{
				"id":   "call_1",
				"type": "function",
				"function": map[string]any{
					"name":      "vault.search",
					"arguments": `{"q":"hello"}`,
				},
			},
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"id":    "chatcmpl-2",
			"model": "gpt-4o-mini",
			"choices": []map[string]any{
				{
					"index": 0,
					"message": map[string]any{
						"role":       "assistant",
						"content":    "",
						"tool_calls": tc,
					},
					"finish_reason": "tool_calls",
					"tool_calls":    tc,
				},
			},
			"usage": map[string]int{
				"prompt_tokens":     20,
				"completion_tokens": 5,
				"total_tokens":      25,
			},
		})
	}))
	defer srv.Close()

	p := NewOpenAICompat("openai", srv.URL, "test-key")
	resp, err := p.ChatCompletion(context.Background(), validReq())
	if err != nil {
		t.Fatalf("ChatCompletion 失败: %v", err)
	}
	if len(resp.Choices[0].ToolCalls) != 1 {
		t.Fatalf("ToolCalls 长度=%d 期望 1", len(resp.Choices[0].ToolCalls))
	}
	tc := resp.Choices[0].ToolCalls[0]
	if tc.Function.Name != "vault.search" {
		t.Errorf("tool name=%q 期望 vault.search", tc.Function.Name)
	}
	if tc.Function.Arguments != `{"q":"hello"}` {
		t.Errorf("tool args=%q 期望 {\"q\":\"hello\"}", tc.Function.Arguments)
	}
	// 同时校验 Message.ToolCalls 也已写入。
	if len(resp.Choices[0].Message.ToolCalls) != 1 {
		t.Errorf("Message.ToolCalls 长度=%d 期望 1", len(resp.Choices[0].Message.ToolCalls))
	}
}

// 用例 2.5：服务端响应仅在 choice.tool_calls 提供工具调用，
// OpenAICompat 应自动同步到 message.tool_calls（兼容不同实现）。
func TestOpenAICompat_ChatCompletion_ToolCallsAutoSync(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"id":    "chatcmpl-2b",
			"model": "gpt-4o-mini",
			"choices": []map[string]any{
				{
					"index": 0,
					"message": map[string]any{
						"role":    "assistant",
						"content": "",
					},
					"finish_reason": "tool_calls",
					"tool_calls": []map[string]any{
						{
							"id":   "call_2",
							"type": "function",
							"function": map[string]any{
								"name":      "vault.get",
								"arguments": `{"id":"x"}`,
							},
						},
					},
				},
			},
			"usage": map[string]int{"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
		})
	}))
	defer srv.Close()

	p := NewOpenAICompat("openai", srv.URL, "test-key")
	resp, err := p.ChatCompletion(context.Background(), validReq())
	if err != nil {
		t.Fatalf("ChatCompletion 失败: %v", err)
	}
	if len(resp.Choices[0].ToolCalls) != 1 {
		t.Fatalf("ToolCalls 长度=%d 期望 1", len(resp.Choices[0].ToolCalls))
	}
	if len(resp.Choices[0].Message.ToolCalls) != 1 {
		t.Errorf("Message.ToolCalls 长度=%d 期望 1（自动同步后）", len(resp.Choices[0].Message.ToolCalls))
	}
}

// 用例 3：HTTP 401 → CodeProviderError（鉴权失败）。
func TestOpenAICompat_ChatCompletion_Unauthorized(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusUnauthorized)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"error": map[string]any{
				"message": "Incorrect API key provided",
				"type":    "invalid_request_error",
				"code":    "invalid_api_key",
			},
		})
	}))
	defer srv.Close()

	p := NewOpenAICompat("openai", srv.URL, "wrong-key")
	_, err := p.ChatCompletion(context.Background(), validReq())
	if err == nil {
		t.Fatal("期望返回错误，实际为 nil")
	}
	if code := ErrorCodeOf(err); code != CodeProviderError {
		t.Errorf("Code=%v 期望 CodeProviderError", code)
	}
}

// 用例 4：HTTP 429 → CodeRateLimited。
func TestOpenAICompat_ChatCompletion_RateLimited(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusTooManyRequests)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"error": map[string]any{"message": "rate limit exceeded"},
		})
	}))
	defer srv.Close()

	p := NewOpenAICompat("openai", srv.URL, "key")
	_, err := p.ChatCompletion(context.Background(), validReq())
	if err == nil {
		t.Fatal("期望返回错误，实际为 nil")
	}
	if code := ErrorCodeOf(err); code != CodeRateLimited {
		t.Errorf("Code=%v 期望 CodeRateLimited", code)
	}
}

// 用例 5：HTTP 400 含 context_length → CodeContextTooLong。
func TestOpenAICompat_ChatCompletion_ContextLength(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"error": map[string]any{
				"message": "context_length_exceeded: maximum context length is 4096",
			},
		})
	}))
	defer srv.Close()

	p := NewOpenAICompat("openai", srv.URL, "key")
	_, err := p.ChatCompletion(context.Background(), validReq())
	if err == nil {
		t.Fatal("期望返回错误，实际为 nil")
	}
	if code := ErrorCodeOf(err); code != CodeContextTooLong {
		t.Errorf("Code=%v 期望 CodeContextTooLong", code)
	}
}

// 用例 6：HTTP 413 → CodeContextTooLong。
func TestOpenAICompat_ChatCompletion_PayloadTooLarge(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusRequestEntityTooLarge)
		_, _ = w.Write([]byte(`{"error":{"message":"payload too large"}}`))
	}))
	defer srv.Close()

	p := NewOpenAICompat("openai", srv.URL, "key")
	_, err := p.ChatCompletion(context.Background(), validReq())
	if err == nil {
		t.Fatal("期望返回错误，实际为 nil")
	}
	if code := ErrorCodeOf(err); code != CodeContextTooLong {
		t.Errorf("Code=%v 期望 CodeContextTooLong", code)
	}
}

// 用例 7：HTTP 500 → CodeProviderError（默认错误码）。
func TestOpenAICompat_ChatCompletion_ServerError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte(`{"error":{"message":"internal"}}`))
	}))
	defer srv.Close()

	p := NewOpenAICompat("openai", srv.URL, "key")
	_, err := p.ChatCompletion(context.Background(), validReq())
	if err == nil {
		t.Fatal("期望返回错误，实际为 nil")
	}
	if code := ErrorCodeOf(err); code != CodeProviderError {
		t.Errorf("Code=%v 期望 CodeProviderError", code)
	}
}

// 用例 8：ctx 超时 → CodeTimeout。
func TestOpenAICompat_ChatCompletion_Timeout(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// 模拟慢响应（>10s 触发 ctx deadline）。
		time.Sleep(2 * time.Second)
		_, _ = w.Write([]byte(`{}`))
	}))
	defer srv.Close()

	p := NewOpenAICompat("openai", srv.URL, "key")
	p.HTTPClient = &http.Client{Timeout: 500 * time.Millisecond}
	ctx, cancel := context.WithTimeout(context.Background(), 500*time.Millisecond)
	defer cancel()
	_, err := p.ChatCompletion(ctx, validReq())
	if err == nil {
		t.Fatal("期望超时错误，实际为 nil")
	}
	if code := ErrorCodeOf(err); code != CodeTimeout {
		t.Errorf("Code=%v 期望 CodeTimeout", code)
	}
}

// 用例 9：网络层不可达（连接被拒绝）→ CodeProviderError。
func TestOpenAICompat_ChatCompletion_ConnRefused(t *testing.T) {
	// 监听一个端口后立即关闭，强制触发 ConnectErr。
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := ln.Addr().String()
	_ = ln.Close()

	p := NewOpenAICompat("openai", "http://"+addr, "key")
	p.HTTPClient = &http.Client{Timeout: 2 * time.Second}
	_, err = p.ChatCompletion(context.Background(), validReq())
	if err == nil {
		t.Fatal("期望连接错误，实际为 nil")
	}
	if code := ErrorCodeOf(err); code != CodeProviderError {
		t.Errorf("Code=%v 期望 CodeProviderError", code)
	}
}

// 用例 10：请求体非法（Model 缺）→ 校验失败，不发 HTTP。
func TestOpenAICompat_ChatCompletion_InvalidModel(t *testing.T) {
	hits := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits++
	}))
	defer srv.Close()

	p := NewOpenAICompat("openai", srv.URL, "key")
	req := validReq()
	req.Model = ""
	_, err := p.ChatCompletion(context.Background(), req)
	if err == nil {
		t.Fatal("期望校验错误，实际为 nil")
	}
	if hits != 0 {
		t.Errorf("校验失败时不应发 HTTP，实际 hits=%d", hits)
	}
	if code := ErrorCodeOf(err); code != CodeInvalidRequest {
		t.Errorf("Code=%v 期望 CodeInvalidRequest", code)
	}
}

// 用例 11：Messages[0] 非 system → CodeInvalidRequest。
func TestOpenAICompat_ChatCompletion_FirstMessageNotSystem(t *testing.T) {
	p := NewOpenAICompat("openai", "http://localhost", "key")
	req := ChatRequest{
		Model: "m",
		Messages: []ChatMessage{
			{Role: RoleUser, Content: "first"},
			{Role: RoleUser, Content: "second"},
		},
	}
	_, err := p.ChatCompletion(context.Background(), req)
	if err == nil {
		t.Fatal("期望校验错误")
	}
	if code := ErrorCodeOf(err); code != CodeInvalidRequest {
		t.Errorf("Code=%v 期望 CodeInvalidRequest", code)
	}
}

// 用例 12：响应无 choice → CodeProviderError。
func TestOpenAICompat_ChatCompletion_NoChoices(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"id":    "x",
			"model": "m",
			"choices": []map[string]any{},
			"usage":   map[string]int{"total_tokens": 0},
		})
	}))
	defer srv.Close()

	p := NewOpenAICompat("openai", srv.URL, "key")
	_, err := p.ChatCompletion(context.Background(), validReq())
	if err == nil {
		t.Fatal("期望无 choice 错误")
	}
	if code := ErrorCodeOf(err); code != CodeProviderError {
		t.Errorf("Code=%v 期望 CodeProviderError", code)
	}
}

// 用例 13：响应非 JSON → CodeProviderError。
func TestOpenAICompat_ChatCompletion_BadJSON(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`not json`))
	}))
	defer srv.Close()

	p := NewOpenAICompat("openai", srv.URL, "key")
	_, err := p.ChatCompletion(context.Background(), validReq())
	if err == nil {
		t.Fatal("期望解码错误")
	}
	if code := ErrorCodeOf(err); code != CodeProviderError {
		t.Errorf("Code=%v 期望 CodeProviderError", code)
	}
}

// 用例 14：CountTokens 估算（含 ASCII + CJK）。
func TestOpenAICompat_CountTokens(t *testing.T) {
	p := NewOpenAICompat("openai", "http://x", "k")
	req := ChatRequest{
		Model: "m",
		Messages: []ChatMessage{
			{Role: RoleSystem, Content: "system"},                // 6 ASCII → 2 token
			{Role: RoleUser, Content: "你好"},                      // 2 CJK → 2 token
			{Role: RoleAssistant, Content: "", ToolCalls: []ToolCall{
				{ID: "1", Type: "function", Function: ToolCallFunc{Name: "vault.search", Arguments: `{"q":"x"}`}},
			}},
		},
		Tools: []ToolSpec{{
			Type: "function",
			Function: ToolFunction{
				Name:        "vault.search",
				Description: "搜索保险库",
				Parameters:  json.RawMessage(`{"type":"object"}`),
			},
		}},
	}
	got := p.CountTokens(req)
	if got <= 0 {
		t.Errorf("CountTokens=%d 期望 >0", got)
	}
	// 期望下界：2(system) + 2(你好) + 2(name+args) + 4(tools name+desc+params)
	if got < 8 {
		t.Errorf("CountTokens=%d 期望 >=8", got)
	}
}

// 用例 15：NewOpenAICompat BaseURL 自动去除尾部 /。
func TestNewOpenAICompat_TrimSlash(t *testing.T) {
	p := NewOpenAICompat("ollama", "http://localhost:11434/v1/", "key")
	if !strings.HasSuffix(p.BaseURL, "/v1") {
		t.Errorf("BaseURL=%q 应去除尾 /", p.BaseURL)
	}
	if strings.HasSuffix(p.BaseURL, "//") {
		t.Errorf("BaseURL=%q 不应出现双 /", p.BaseURL)
	}
}

// 用例 16：Name 返回 Provider 标识。
func TestOpenAICompat_Name(t *testing.T) {
	p := NewOpenAICompat("my-provider", "http://x", "k")
	if p.Name() != "my-provider" {
		t.Errorf("Name()=%q 期望 my-provider", p.Name())
	}
}

// 用例 17：AnthropicCompat stub 返回 CodeProviderError。
func TestAnthropicCompat_Stub(t *testing.T) {
	p := NewAnthropicCompat("anthropic", "https://api.anthropic.com", "k", "2023-06-01")
	if p.Name() != "anthropic" {
		t.Errorf("Name()=%q 期望 anthropic", p.Name())
	}
	_, err := p.ChatCompletion(context.Background(), validReq())
	if err == nil {
		t.Fatal("期望返回错误")
	}
	if code := ErrorCodeOf(err); code != CodeProviderError {
		t.Errorf("Code=%v 期望 CodeProviderError", code)
	}
	// CountTokens 仍可工作。
	if n := p.CountTokens(validReq()); n <= 0 {
		t.Errorf("CountTokens=%d 期望 >0", n)
	}
}

// 用例 18：StubProvider 始终返回 CodeProviderError。
func TestStubProvider(t *testing.T) {
	p := NewStubProvider("stub")
	if p.Name() != "stub" {
		t.Errorf("Name()=%q 期望 stub", p.Name())
	}
	_, err := p.ChatCompletion(context.Background(), validReq())
	if err == nil {
		t.Fatal("期望返回错误")
	}
	if code := ErrorCodeOf(err); code != CodeProviderError {
		t.Errorf("Code=%v 期望 CodeProviderError", code)
	}
}

// 用例 19：isValidToolName 边界（合法 / 非法）。
func TestIsValidToolName(t *testing.T) {
	cases := []struct {
		in   string
		want bool
	}{
		{"vault.search", true},
		{"a", true},
		{"a.b.c", true},
		{"a_b", true},
		{"a1", true},
		{"", false},
		{"A.b", false},
		{"a-b", false},
		{"a/b", false},
		{"a b", false},
		{strings.Repeat("a", 65), false},
	}
	for _, c := range cases {
		if got := isValidToolName(c.in); got != c.want {
			t.Errorf("isValidToolName(%q)=%v 期望 %v", c.in, got, c.want)
		}
	}
}

// 用例 20：ValidateRequest Content 超 4 KiB → 拒绝。
func TestValidateRequest_ContentTooLarge(t *testing.T) {
	req := validReq()
	req.Messages[1].Content = strings.Repeat("x", 4097)
	if err := ValidateRequest(req); err == nil {
		t.Fatal("期望 Content 超限错误")
	}
}

// 用例 21：Ollama 模式（APIKey 为空）→ 不发送 Authorization 头。
// Ollama OpenAI 兼容模式默认无鉴权；驱动必须正确处理空 APIKey 场景。
func TestOpenAICompat_Ollama_NoAuthHeader(t *testing.T) {
	gotAuth := ""
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth = r.Header.Get("Authorization")
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"id":"x","model":"qwen2.5:7b","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"total_tokens":1}}`))
	}))
	defer srv.Close()

	p := NewOpenAICompat("ollama", srv.URL, "") // Ollama 占位 / 空 Key
	if _, err := p.ChatCompletion(context.Background(), validReq()); err != nil {
		t.Fatalf("ChatCompletion 失败: %v", err)
	}
	if gotAuth != "" {
		t.Errorf("空 APIKey 时不应发送 Authorization 头，实际=%q", gotAuth)
	}
}

// 用例 22：HTTP 429 含 Retry-After 头（整数秒）→ ParseRetryAfter 返回正确毫秒数。
func TestParseRetryAfter_Seconds(t *testing.T) {
	now := time.Date(2025, 1, 1, 12, 0, 0, 0, time.UTC)
	got := ParseRetryAfter("120", now)
	if got != 120000 {
		t.Errorf("ParseRetryAfter(\"120\")=%d 期望 120000", got)
	}
}

// 用例 23：HTTP 429 含 Retry-After 头（HTTP-date）→ 解析为相对毫秒。
func TestParseRetryAfter_HTTPDate(t *testing.T) {
	now := time.Date(2025, 1, 1, 12, 0, 0, 0, time.UTC)
	future := now.Add(60 * time.Second).Format(time.RFC1123)
	got := ParseRetryAfter(future, now)
	// 允许 ±1000 ms 抖动（HTTP-date 精度秒）。
	if got < 59000 || got > 61000 {
		t.Errorf("ParseRetryAfter(%q)=%d 期望 ~60000", future, got)
	}
}

// 用例 24：ParseRetryAfter 空串 / 非法格式 → 返回 0。
func TestParseRetryAfter_Invalid(t *testing.T) {
	now := time.Now()
	if got := ParseRetryAfter("", now); got != 0 {
		t.Errorf("空串应返 0，实际=%d", got)
	}
	if got := ParseRetryAfter("not-a-number-or-date", now); got != 0 {
		t.Errorf("非法格式应返 0，实际=%d", got)
	}
	if got := ParseRetryAfter("0", now); got != 0 {
		t.Errorf("0 秒应返 0，实际=%d", got)
	}
}

// 用例 25：HTTP 400 非 context_length → CodeProviderError。
func TestOpenAICompat_ChatCompletion_BadRequestGeneric(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"error":{"message":"invalid parameter"}}`))
	}))
	defer srv.Close()

	p := NewOpenAICompat("openai", srv.URL, "key")
	_, err := p.ChatCompletion(context.Background(), validReq())
	if err == nil {
		t.Fatal("期望返回错误")
	}
	if code := ErrorCodeOf(err); code != CodeProviderError {
		t.Errorf("Code=%v 期望 CodeProviderError", code)
	}
}

// 用例 26：ExtraHeaders 注入（Anthropic 兼容模式下的 anthropic-version）。
func TestOpenAICompat_ExtraHeaders(t *testing.T) {
	got := ""
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.Header.Get("X-Provider-Version")
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"id":"x","model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"total_tokens":1}}`))
	}))
	defer srv.Close()

	p := NewOpenAICompat("anthropic_compat", srv.URL, "key")
	p.ExtraHeaders = map[string]string{"X-Provider-Version": "2023-06-01"}
	if _, err := p.ChatCompletion(context.Background(), validReq()); err != nil {
		t.Fatalf("ChatCompletion 失败: %v", err)
	}
	if got != "2023-06-01" {
		t.Errorf("ExtraHeader 未透传，实际=%q 期望 2023-06-01", got)
	}
}

// 用例 27：请求体序列化校验（验证 stream=false、model、messages 等字段）。
func TestOpenAICompat_RequestBodyShape(t *testing.T) {
	var gotBody map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(body, &gotBody)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"id":"x","model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"total_tokens":1}}`))
	}))
	defer srv.Close()

	p := NewOpenAICompat("openai", srv.URL, "key")
	if _, err := p.ChatCompletion(context.Background(), validReq()); err != nil {
		t.Fatalf("ChatCompletion 失败: %v", err)
	}
	if gotBody["stream"] != false {
		t.Errorf("stream 字段=%v 期望 false", gotBody["stream"])
	}
	if gotBody["model"] != "gpt-4o-mini" {
		t.Errorf("model=%v 期望 gpt-4o-mini", gotBody["model"])
	}
	msgs, ok := gotBody["messages"].([]any)
	if !ok || len(msgs) != 2 {
		t.Fatalf("messages 长度/类型异常: %+v", gotBody["messages"])
	}
	first, _ := msgs[0].(map[string]any)
	if first["role"] != RoleSystem {
		t.Errorf("messages[0].role=%v 期望 system", first["role"])
	}
}

// 用例 28：tools / tool_choice 字段序列化校验。
func TestOpenAICompat_RequestBodyTools(t *testing.T) {
	var gotBody map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(body, &gotBody)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"id":"x","model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"total_tokens":1}}`))
	}))
	defer srv.Close()

	p := NewOpenAICompat("openai", srv.URL, "key")
	req := validReq()
	params := json.RawMessage(`{"type":"object","properties":{"q":{"type":"string"}}}`)
	req.Tools = []ToolSpec{{Type: "function", Function: ToolFunction{
		Name: "vault.search", Description: "搜索保险库", Parameters: params,
	}}}
	req.ToolChoice = "auto"
	if _, err := p.ChatCompletion(context.Background(), req); err != nil {
		t.Fatalf("ChatCompletion 失败: %v", err)
	}
	tools, ok := gotBody["tools"].([]any)
	if !ok || len(tools) != 1 {
		t.Fatalf("tools 字段=%+v 期望 1 个工具", gotBody["tools"])
	}
	if gotBody["tool_choice"] != "auto" {
		t.Errorf("tool_choice=%v 期望 auto", gotBody["tool_choice"])
	}
}