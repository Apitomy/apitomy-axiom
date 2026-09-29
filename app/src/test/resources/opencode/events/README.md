# Captured OpenCode event streams

Real `GET /event` streams from `opencode serve` 1.18.33 (model `github-copilot/claude-sonnet-5`), one SSE `data:`
JSON object per line. They are used by `OpenCodeEventNormalizerTest` and `OpenCodeInteractiveSessionDriverTest`.

| File | Prompt |
|---|---|
| `1.18.33-tool-calls.jsonl` | Read `hello.txt`, run `echo captured` (bash set to `ask`, answered `once`), reply `DONE` |
| `1.18.33-tool-error.jsonl` | Read `does-not-exist.txt` (fails), reply `DONE` |

To capture streams for a new opencode version, run `python3 capture.py`. It needs `opencode` on the `PATH` and a
working `github-copilot` provider, and it makes one short model call. Edit `OUT` and the prompt in the script as
needed, then save the result under a new version-prefixed name.
