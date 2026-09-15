// Package config 负责加载服务端配置：配置文件 + 环境变量覆盖 + 默认值。
package config

import (
	"fmt"
	"os"
	"path/filepath"

	"gopkg.in/yaml.v3"
)

// Registration 模式。
const (
	// RegFirst 仅允许注册第一个用户（默认，单用户自托管）。
	RegFirst = "first"
	// RegOpen 允许任意注册（家庭可信部署时手动开启）。
	RegOpen = "open"
	// RegClosed 完全关闭注册。
	RegClosed = "closed"
)

// Config 是服务端全部可配置项。
type Config struct {
	// Addr 是 HTTP 监听地址，如 ":8787"。
	Addr string `yaml:"addr"`
	// DataDir 是数据库、JWT 密钥、附件等数据的存放目录。
	DataDir string `yaml:"data_dir"`
	// Registration 控制注册策略：first / open / closed。
	Registration string `yaml:"registration"`
	// AccessTokenTTL 访问令牌有效期（分钟）。
	AccessTokenTTL int `yaml:"access_token_ttl_minutes"`
	// RefreshTokenTTL 刷新令牌有效期（天）。
	RefreshTokenTTL int `yaml:"refresh_token_ttl_days"`
}

// Default 返回带默认值的配置。
func Default() Config {
	return Config{
		Addr:            ":8787",
		DataDir:         "./data",
		Registration:    RegFirst,
		AccessTokenTTL:  15,
		RefreshTokenTTL: 90,
	}
}

// Load 从 dataDir/config.yaml（若存在）读取配置，再用环境变量覆盖。
// 配置文件缺失不算错误，直接使用默认值。
func Load(path string) (Config, error) {
	cfg := Default()
	if path == "" {
		path = filepath.Join(cfg.DataDir, "config.yaml")
	}
	if b, err := os.ReadFile(path); err == nil {
		if err := yaml.Unmarshal(b, &cfg); err != nil {
			return cfg, fmt.Errorf("解析配置文件 %s: %w", path, err)
		}
	} else if !os.IsNotExist(err) {
		return cfg, fmt.Errorf("读取配置文件: %w", err)
	}
	applyEnv(&cfg)
	if cfg.Registration != RegFirst && cfg.Registration != RegOpen && cfg.Registration != RegClosed {
		return cfg, fmt.Errorf("非法 registration 取值: %q", cfg.Registration)
	}
	if err := os.MkdirAll(cfg.DataDir, 0o700); err != nil {
		return cfg, fmt.Errorf("创建数据目录: %w", err)
	}
	return cfg, nil
}

func applyEnv(cfg *Config) {
	if v := os.Getenv("EVE_ADDR"); v != "" {
		cfg.Addr = v
	}
	if v := os.Getenv("EVE_DATA_DIR"); v != "" {
		cfg.DataDir = v
	}
	if v := os.Getenv("EVE_REGISTRATION"); v != "" {
		cfg.Registration = v
	}
}
