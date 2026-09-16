package api

// 阶段 4a 位置轨迹三端点（tasks.md Task 2 / spec FR-5、FR-7、AC-5、AC-6）。
// 零知识红线（NFR-1）：handler 只透传密文块与最小元数据（时间范围/点数/大小），
// 绝不接触明文坐标；审计 detail 仅含计数与范围，禁止出现块 id/坐标/密文字段。

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"

	"github.com/everything-personal/eve/internal/sync"
	"github.com/everything-personal/eve/internal/vault"
)

// 位置轨迹端点限额（spec Constraints：服务端强制）。
const (
	// maxLocationBlocksPerBatch 单批最多块数（FR-5：≤50 块/批）。
	maxLocationBlocksPerBatch = 50
	// maxLocationCipherBytes 单块密文解码后的字节上限（FR-5：≤256KB）。
	maxLocationCipherBytes = 256 * 1024
	// maxLocationRangeMillis GET/DELETE 允许的最大时间跨度：62 天（UTC 毫秒）。
	maxLocationRangeMillis = int64(62) * 24 * 60 * 60 * 1000
	// maxLocationBatchBodyBytes 批量上行请求体上限。
	// 契约内最大合法批量 = 50 块 × 256KB 密文，经 base64 膨胀（×4/3）约 17MB，
	// 取 20MB 余量，避免最大合法批量被通用 4MB JSON 限制（decodeJSON）误杀。
	maxLocationBatchBodyBytes = 20 << 20
)

// locationBlockInput 是上行块的客户端形态。
// cipher 以 base64 字符串接收并显式解码，以便对"非 base64"与"超 256KB"
// 分别给出精确 400 原因（而非笼统的 JSON 解析失败）。
// device_id 即使客户端携带也不予定义——反序列化时自然忽略，
// 入库一律以 token claims 覆盖（不信任客户端自声明）。
type locationBlockInput struct {
	ID         string `json:"id"`
	StartTs    int64  `json:"start_ts"`    // 块首点 UTC 毫秒
	EndTs      int64  `json:"end_ts"`      // 块末点 UTC 毫秒
	PointCount int    `json:"point_count"` // 块内点数（客户端自声明元数据，服务端不验证真实性）
	Cipher     string `json:"cipher"`      // XChaCha20-Poly1305 密文信封的 base64
}

// locationBatchRequest 是 POST /api/v1/locations/batch 的请求体。
type locationBatchRequest struct {
	Blocks []locationBlockInput `json:"blocks"`
}

// uploadLocationBlocks 处理 POST /api/v1/locations/batch：批量写入轨迹密文块。
// 按块 id 幂等（INSERT OR IGNORE），重复提交计 skipped；成功后审计并广播
// locations_changed（Live 模式预留，本期 Web 不订阅）。
func (s *Server) uploadLocationBlocks(w http.ResponseWriter, r *http.Request) {
	claims := claimsFrom(r)
	var req locationBatchRequest
	defer r.Body.Close()
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxLocationBatchBodyBytes)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "bad_request", "请求体不是合法 JSON 或超过大小上限")
		return
	}
	// 块数契约 1..50，越界一律 400。
	if len(req.Blocks) == 0 {
		writeError(w, http.StatusBadRequest, "bad_request", "blocks 为空")
		return
	}
	if len(req.Blocks) > maxLocationBlocksPerBatch {
		writeError(w, http.StatusBadRequest, "bad_request", "单批最多 50 块")
		return
	}
	blocks := make([]vault.LocationBlock, 0, len(req.Blocks))
	for i := range req.Blocks {
		in := &req.Blocks[i]
		// 逐块校验：id 非空、0 < start_ts <= end_ts、point_count > 0、
		// cipher 为合法 base64 且解码后 ≤256KB。
		if in.ID == "" {
			writeError(w, http.StatusBadRequest, "bad_request", "块缺少 id")
			return
		}
		if in.StartTs <= 0 || in.StartTs > in.EndTs {
			writeError(w, http.StatusBadRequest, "bad_request", "块时间范围非法（要求 0 < start_ts <= end_ts）")
			return
		}
		if in.PointCount <= 0 {
			writeError(w, http.StatusBadRequest, "bad_request", "point_count 必须为正整数")
			return
		}
		cipher, err := base64.StdEncoding.DecodeString(in.Cipher)
		if err != nil {
			writeError(w, http.StatusBadRequest, "bad_request", "cipher 不是合法 base64")
			return
		}
		if len(cipher) == 0 {
			// 空密文块无意义（轨迹块没有 records 的墓碑语义），一并拒绝。
			writeError(w, http.StatusBadRequest, "bad_request", "cipher 为空")
			return
		}
		if len(cipher) > maxLocationCipherBytes {
			writeError(w, http.StatusBadRequest, "bad_request", "单块密文超过 256KB")
			return
		}
		blocks = append(blocks, vault.LocationBlock{
			ID:         in.ID,
			UserID:     claims.UserID,   // 用户隔离以 token 为准
			DeviceID:   claims.DeviceID, // device_id 以 token claims 覆盖，不信任客户端值
			StartTs:    in.StartTs,
			EndTs:      in.EndTs,
			PointCount: in.PointCount,
			Cipher:     cipher,
			// created_at 由存储层以服务端权威时间统一覆盖（FU-1），客户端值忽略。
		})
	}
	applied, skipped, err := s.locations.UpsertBlocks(claims.UserID, blocks, timeMillis())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "写入失败")
		return
	}
	// 审计 detail 仅计数：块数/入库数/幂等跳过数（TR-2.2，无任何块 id/密文）。
	s.audit(claims.UserID, "location.upload",
		fmt.Sprintf("blocks=%d applied=%d skipped=%d", len(blocks), applied, skipped), r.RemoteAddr)
	// 写库成功后广播变更（Live 模式预留口，FR-7）。
	s.hub.Publish(claims.UserID, sync.Event{Type: "locations_changed"})
	writeJSON(w, http.StatusOK, map[string]any{"applied": applied, "skipped": skipped})
}

// listLocationBlocks 处理 GET /api/v1/locations?from&to：
// 跨月表按 start_ts 升序返回该用户的密文块（cipher 由 JSON 层自动转 base64）。
func (s *Server) listLocationBlocks(w http.ResponseWriter, r *http.Request) {
	claims := claimsFrom(r)
	from, to, ok := parseLocationRange(w, r)
	if !ok {
		return // parseLocationRange 已写出 400
	}
	blocks, err := s.locations.ListRange(claims.UserID, from, to)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "读取失败")
		return
	}
	// 审计 detail 仅含查询范围与返回块数（TR-2.2）。
	s.audit(claims.UserID, "location.download",
		fmt.Sprintf("from=%d to=%d blocks=%d", from, to, len(blocks)), r.RemoteAddr)
	writeJSON(w, http.StatusOK, map[string]any{"blocks": blocks})
}

// deleteLocationBlocks 处理 DELETE /api/v1/locations?from&to：
// 跨月表范围删除该用户的轨迹块，返回实际删除数（NFR-6 用户可控删除入口）。
func (s *Server) deleteLocationBlocks(w http.ResponseWriter, r *http.Request) {
	claims := claimsFrom(r)
	from, to, ok := parseLocationRange(w, r)
	if !ok {
		return
	}
	deleted, err := s.locations.DeleteRange(claims.UserID, from, to)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "删除失败")
		return
	}
	// 审计 detail 仅含范围与删除块数（TR-2.2）。
	s.audit(claims.UserID, "location.delete",
		fmt.Sprintf("from=%d to=%d deleted=%d", from, to, deleted), r.RemoteAddr)
	writeJSON(w, http.StatusOK, map[string]any{"deleted": deleted})
}

// parseLocationRange 解析并校验 GET/DELETE 共用的 ?from&to（UTC 毫秒整数）。
// 解析失败（含缺参）、from<=0、to<from、跨度 >62 天均写出 400 并返回 ok=false。
func parseLocationRange(w http.ResponseWriter, r *http.Request) (from, to int64, ok bool) {
	q := r.URL.Query()
	from, errFrom := strconv.ParseInt(q.Get("from"), 10, 64)
	to, errTo := strconv.ParseInt(q.Get("to"), 10, 64)
	if errFrom != nil || errTo != nil {
		writeError(w, http.StatusBadRequest, "bad_request", "from/to 必须是 UTC 毫秒整数")
		return 0, 0, false
	}
	if from <= 0 || to < from {
		writeError(w, http.StatusBadRequest, "bad_request", "时间范围非法（要求 from > 0 且 to >= from）")
		return 0, 0, false
	}
	if to-from > maxLocationRangeMillis {
		writeError(w, http.StatusBadRequest, "bad_request", "时间跨度最大 62 天")
		return 0, 0, false
	}
	return from, to, true
}
