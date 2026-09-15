.PHONY: build run test vet fmt tidy cross-check

build:
	CGO_ENABLED=0 go build -ldflags "-X github.com/everything-personal/eve/internal/api.Version=$$(git describe --tags --always 2>/dev/null || echo dev)" -o eve ./cmd/eve

run:
	go run ./cmd/eve

test:
	CGO_ENABLED=0 go test ./...

vet:
	go vet ./...

fmt:
	gofmt -s -w .

tidy:
	go mod tidy

# 快照验证全平台交叉编译（amd64/arm64 × 三大系统）
cross-check:
	GOOS=linux   GOARCH=amd64 CGO_ENABLED=0 go build ./cmd/eve
	GOOS=linux   GOARCH=arm64 CGO_ENABLED=0 go build ./cmd/eve
	GOOS=darwin  GOARCH=arm64 CGO_ENABLED=0 go build ./cmd/eve
	GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -o eve.exe ./cmd/eve
	@echo "cross compile OK"
