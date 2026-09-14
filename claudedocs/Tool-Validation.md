
Required Fields

1. Name is required — must be non-empty
2. Name format — should be lowercase with hyphens or underscores (valid identifier for MCP tool naming)
3. Name uniqueness — already enforced at DB level, but could validate earlier in the UI
4. Script template has content — must be non-empty/non-blank
5. Description is recommended — warn (not error) if empty, since it's shown to AI agents to understand the tool

Parameter Definitions

6. No duplicate parameter names — each parameter name must be unique within the tool
7. Parameter names are non-empty — each parameter must have a name
8. Parameter names are valid identifiers — should match [a-zA-Z_][a-zA-Z0-9_]* (since they become {{placeholders}})
9. Parameter type is valid — must be one of string, number, boolean
10. At least one required parameter should exist — warn if all parameters are optional (tool may not be useful without inputs)

Placeholder Consistency (the ones you mentioned)

11. All {{placeholders}} in the script template have matching parameter definitions — catches typos and forgotten parameter definitions
12. All defined parameters are used in the script template — either as {{name}} or {{name_file}} — catches dead parameters
13. {{name_file}} placeholders have matching parameters — the _file suffix should correspond to a real parameter name (stripping _file)

Script Template Quality

14. No unresolved placeholder syntax errors — detect malformed placeholders like {{, }}, or {{ name }} (spaces inside braces)
15. Script starts with a shebang or valid bash — warn if the first line doesn't look like bash (e.g. starts with #!/bin/bash or a valid command)
16. No obvious security concerns — warn if script contains rm -rf, eval, or other dangerous patterns with parameter substitution

Labels

17. No duplicate labels — each label should appear only once
18. Labels are non-empty strings — no blank labels

