# Everything 顶层构建入口（各子项目另有独立 Makefile / 脚本）
.PHONY: web server test dev-web dev-server docker tidy

web:
	cd web && npm install && npm run build

server: web
	cd server && CGO_ENABLED=0 go build -o eve ./cmd/eve

test:
	cd server && CGO_ENABLED=0 go test ./...

dev-web:
	cd web && npm run dev

dev-server:
	cd server && go run ./cmd/eve

tidy:
	cd server && go mod tidy

docker:
	docker buildx build --platform linux/amd64,linux/arm64 -f deploy/Dockerfile -t everything-eve:latest .
