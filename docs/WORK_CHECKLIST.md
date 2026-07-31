# Work checklist

## Current objective

Optimize hardware preset configuration tiers (`SMALL_LOCAL` vs `LARGE_LOCAL`) for low latency and zero Ollama VRAM swapping.

## Checklist

- [x] Update `.cast/harness.local.json` to use `qwen2.5-coder:1.5b` for `SMALL_LOCAL` and `qwen2.5-coder:7b` for `LARGE_LOCAL`.
- [x] Pull `qwen2.5-coder:1.5b` via Ollama.
- [x] Update hardware preset configs in `config/` (`harness.vram-8gb.json`, `harness.vram-12gb.json`, `harness.vram-16gb.json`, `harness.apple-silicon.json`).
- [x] Verify latency drop (from 9.4s down to ~1.4s–2.7s per request) and run `.\gradlew.bat test` and `.\gradlew.bat check`.

## Blockers and open decisions

None.

## Next action

No follow-up required.
