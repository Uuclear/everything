// Package sync 提供用户级变更事件的进程内发布订阅，供 SSE 推送使用。
// 多副本部署时将替换为 NATS/Postgres LISTEN（阶段 8）。
package sync

import (
	"sync"
)

// Event 是推送给客户端的变更通知。
type Event struct {
	Type   string `json:"type"`             // 如 "records_changed"
	Module string `json:"module,omitempty"` // 涉及的模块
}

type subscriber struct {
	ch     chan Event
	closed bool
}

// Hub 按用户维护订阅者集合。
type Hub struct {
	mu   sync.RWMutex
	subs map[string]map[*subscriber]struct{}
}

// New 创建事件总线。
func New() *Hub { return &Hub{subs: make(map[string]map[*subscriber]struct{})} }

// Subscribe 订阅某用户的事件；返回事件通道与取消函数。
func (h *Hub) Subscribe(userID string) (<-chan Event, func()) {
	s := &subscriber{ch: make(chan Event, 16)}
	h.mu.Lock()
	if h.subs[userID] == nil {
		h.subs[userID] = make(map[*subscriber]struct{})
	}
	h.subs[userID][s] = struct{}{}
	h.mu.Unlock()
	return s.ch, func() {
		h.mu.Lock()
		defer h.mu.Unlock()
		if set, ok := h.subs[userID]; ok {
			if _, ok := set[s]; ok {
				delete(set, s)
				close(s.ch)
			}
			if len(set) == 0 {
				delete(h.subs, userID)
			}
		}
	}
}

// Publish 向某用户的全部在线订阅者非阻塞投递事件。
func (h *Hub) Publish(userID string, e Event) {
	h.mu.RLock()
	set := h.subs[userID]
	subs := make([]*subscriber, 0, len(set))
	for s := range set {
		subs = append(subs, s)
	}
	h.mu.RUnlock()
	for _, s := range subs {
		select {
		case s.ch <- e:
		default: // 客户端消费慢时丢弃，同步轮询 since= 仍是可靠兜底
		}
	}
}
