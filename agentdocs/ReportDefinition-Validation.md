
Required Fields

1. Name is required — must be non-empty ✅
2. Name uniqueness — already enforced at DB level, but could validate earlier in the UI
3. Schedule is required — must be one of `none`, `hourly`, `daily`, `weekly`, `monthly`, or a valid cron expression ✅
4. Time window is required — must be one of `since-last-run`, `last-24h`, `last-7d`, `last-30d` ✅
5. Prompt template has content — must be non-empty/non-blank ✅
6. Description is recommended — warn (not error) if empty, since it helps identify the report's purpose ✅

Schedule Configuration

7. Schedule time format — if provided, must be valid `HH:MM` 24-hour format (00:00–23:59) ✅
8. Schedule time is recommended — warn if schedule is not `none` but no time is specified (defaults to 08:00) ✅
9. Day of week is valid — if schedule is `weekly`, day must be one of `monday`–`sunday` (case-insensitive) ✅
10. Day of week is recommended — warn if schedule is `weekly` but no day is specified ✅
11. Cron expression validation — if schedule contains spaces (cron format), validate the cron syntax
12. Schedule/enabled consistency — if schedule is `none`, `enabled` must be false (enforced at REST layer)

Prompt Template Quality

13. No malformed placeholders — detect `{{ name }}` (spaces inside braces) or unclosed `{{`
14. No unrecognized placeholders — error if template contains `{{something}}` that is not one of the four recognized placeholders (`repositories`, `timeRangeStart`, `timeRangeEnd`, `timeWindow`) ✅

Timeout

16. Timeout must be positive — if provided, must be greater than zero ✅
17. Timeout not too low — warn if less than 30 seconds ✅
18. Timeout not too high — warn if greater than 3600 seconds (one hour) ✅

Allowed Tools

19. Tool references are well-formed — each entry must match `mcp__*__*`, `@ToolsetName`, or a built-in tool pattern; blank entries are errors ✅
20. Referenced toolsets exist — error if a `@ToolsetName` reference does not match any configured toolset ✅
21. Referenced custom tools exist — error if a `mcp__axiom-tools__<name>` reference does not match any configured tool ✅
21b. Referenced SDK tools exist — error if a `mcp__axiom-sdk__<name>` reference is not a recognized Axiom SDK tool ✅

Initial Labels

22. No blank labels — each label must be a non-empty string ✅
23. No duplicate labels — warn on case-insensitive duplicates ✅

Environment Variables

24. Environment keys are valid — if environment is provided, keys must match `[a-zA-Z_][a-zA-Z0-9_]*` format ✅
25. Secret references are well-formed — detects `${secret:}`, `${secret:FOO` (missing `}`), and `${secret}` (missing colon) ✅
26. Referenced secrets exist — error if a `${secret:NAME}` reference does not match any configured secret ✅

Legend: ✅ = implemented, no marker = suggested for future implementation
