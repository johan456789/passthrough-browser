.PHONY: server test apk install-server
server:
	cd server && CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o passthrough-browser .
test:
	cd server && go vet ./... && go test ./...
	cd android && ./gradlew --no-daemon testDebugUnitTest
apk:
	cd android && ./gradlew --no-daemon assembleDebug
install-server: server
	install -Dm755 server/passthrough-browser $(HOME)/.local/bin/passthrough-browser
	install -Dm644 server/systemd/passthrough-browser.service $(HOME)/.config/systemd/user/passthrough-browser.service
	systemctl --user daemon-reload
	systemctl --user enable --now passthrough-browser.service
