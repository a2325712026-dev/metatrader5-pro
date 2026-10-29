# Changelog

## v0.15 — 2026-10-29
- Partial close of open positions (server `/close` accepts a `volume` parameter).
- Live USD / points labels while dragging SL/TP lines.
- MT5-style buttons: inactive SL/TP buttons are shown gray.
- Connection stability: one shared, certificate-pinned HTTP client with retries.
- Server: `/health` reports `version`; symbol info includes tick value / tick size; `--setup` installs a watchdog scheduled task.

## v0.14 — 2026-10-28
- Trust-on-first-use (TOFU) certificate pinning: setup needs only the server address and API key.
- Connection code accepted with or without the `MT5PRO1:` prefix.
- Input fields never auto-clear.
- Draggable TP/SL lines on open positions, with a confirm bar.

## v0.13
- HTTPS only (no cleartext fallback); one-step `--setup` with a connection code.

## v0.12
- HTTPS support with certificate pinning, `--make-cert`, docs.

## v0.11
- Initial release.
