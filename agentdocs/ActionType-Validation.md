
Required Fields

1. Name is required — must be non-empty ✅
2. Name uniqueness — already enforced at DB level, but could validate earlier in the UI
3. Execution mode is required — must be `actor` or `script` (enforced by enum) ✅
4. Description is recommended — warn (not error) if empty, since the AI Manager uses it to decide when to trigger this action ✅

Prompt Template (actor mode only)

5. Prompt template is required — must be non-empty when execution mode is `actor` ✅
6. No unrecognized placeholders — error if template contains `{{something}}` not in the recognized set: `input`, `managerInput`, `actionType`, `issueRef`, `repository`, `projectName`, `workDir` ✅
7. No malformed placeholders — detect `{{ name }}` (spaces inside braces) or unclosed `{{` ✅

Script Template (script mode only)

8. Script template is required — must be non-empty when execution mode is `script` ✅
9. No unrecognized placeholders — error if template contains `{{something}}` not in the recognized set: `projectId`, `eventId`, `taskId`, `issueRef`, `repository`, `projectName`, `managerInput`, `apiBaseUrl`, `workDir` ✅
10. No malformed placeholders — detect `{{ name }}` (spaces inside braces) or unclosed `{{` ✅
11. Script starts with a shebang or valid bash — warn if the first line doesn't look like bash

Allowed Tools (actor mode only)

12. No blank entries — each allowed tool entry must be non-empty ✅
13. Tool references are well-formed — each entry must match `mcp__*__*`, `@ToolsetName`, or a built-in tool pattern ✅
14. Referenced toolsets exist — error if `@ToolsetName` does not match any configured toolset ✅
15. Referenced custom tools exist — error if `mcp__axiom-tools__<name>` does not match any configured tool ✅
16. Referenced SDK tools exist — error if `mcp__axiom-sdk__<name>` is not a recognized Axiom SDK tool ✅

Environment Variables

17. Environment keys are valid — must match `[a-zA-Z_][a-zA-Z0-9_]*` format ✅
18. Secret references are well-formed — detects `${secret:}`, `${secret:FOO` (missing `}`), and `${secret}` (missing colon) ✅
19. Referenced secrets exist — error if `${secret:NAME}` does not match any configured secret ✅

Model and Engine

20. Model is valid — if provided, warn if not in the list of available models
21. Engine is valid — if provided, warn if not in the list of available engines (`claude-code`, `opencode`)

Input Schema

22. Input schema is valid JSON — if provided, must be parseable as valid JSON
23. Input schema is valid JSON Schema — if provided, should conform to JSON Schema structure

Legend: ✅ = implemented, no marker = suggested for future implementation
