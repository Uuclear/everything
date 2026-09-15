// Package attachments 负责按用户隔离保存客户端已加密的附件字节。
package attachments

import (
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

const maxUploadSize = 64 << 20

// Store 管理附件元数据和内容寻址的密文文件。
type Store struct {
	db   *sql.DB
	root string
}

// New 创建附件存储，并确保数据目录存在。
func New(database *sql.DB, root string) (*Store, error) {
	if err := os.MkdirAll(root, 0o700); err != nil {
		return nil, fmt.Errorf("创建附件目录: %w", err)
	}
	return &Store{db: database, root: root}, nil
}

// Upload 保存客户端加密字节，校验 SHA-256，并按用户与摘要去重。
func (s *Store) Upload(userID, id, contentType, expectedHash string, body io.Reader) (int64, error) {
	if !validID(id) || !validHash(expectedHash) {
		return 0, errors.New("附件 id 或摘要无效")
	}
	data, err := io.ReadAll(io.LimitReader(body, maxUploadSize+1))
	if err != nil {
		return 0, err
	}
	if len(data) > maxUploadSize {
		return 0, errors.New("附件超过 64 MiB 限制")
	}
	sum := sha256.Sum256(data)
	actualHash := hex.EncodeToString(sum[:])
	if !strings.EqualFold(actualHash, expectedHash) {
		return 0, errors.New("附件摘要不匹配")
	}

	path := filepath.Join(s.root, userID, actualHash[:2], actualHash)
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return 0, err
	}
	if _, err := os.Stat(path); errors.Is(err, os.ErrNotExist) {
		tmp, err := os.CreateTemp(filepath.Dir(path), ".upload-*")
		if err != nil {
			return 0, err
		}
		tmpName := tmp.Name()
		defer os.Remove(tmpName)
		if _, err = tmp.Write(data); err == nil {
			err = tmp.Chmod(0o600)
		}
		if closeErr := tmp.Close(); err == nil {
			err = closeErr
		}
		if err != nil {
			return 0, err
		}
		if err = os.Rename(tmpName, path); err != nil && !errors.Is(err, os.ErrExist) {
			return 0, err
		}
	}

	if _, err := s.db.Exec(`INSERT INTO attachments
		(id, user_id, content_hash, content_type, size, created_at)
		VALUES (?, ?, ?, ?, ?, unixepoch('now') * 1000)
		ON CONFLICT(id, user_id) DO UPDATE SET
		content_hash=excluded.content_hash, content_type=excluded.content_type,
		size=excluded.size`, id, userID, actualHash, contentType, len(data)); err != nil {
		return 0, err
	}
	return int64(len(data)), nil
}

// Open 返回用户拥有的附件文件及其安全的媒体类型。
func (s *Store) Open(userID, id string) (*os.File, string, int64, error) {
	if !validID(id) {
		return nil, "", 0, errors.New("附件 id 无效")
	}
	var hash, contentType string
	var size int64
	if err := s.db.QueryRow(`SELECT content_hash, content_type, size
		FROM attachments WHERE user_id = ? AND id = ?`, userID, id).
		Scan(&hash, &contentType, &size); err != nil {
		return nil, "", 0, err
	}
	file, err := os.Open(filepath.Join(s.root, userID, hash[:2], hash))
	if err != nil {
		return nil, "", 0, err
	}
	return file, contentType, size, nil
}

func validID(id string) bool {
	return id != "" && len(id) <= 128 && !strings.ContainsAny(id, `/\`)
}

func validHash(value string) bool {
	if len(value) != sha256.Size*2 {
		return false
	}
	_, err := hex.DecodeString(value)
	return err == nil
}
