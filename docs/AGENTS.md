When running on Windows through OpenCode and 9Router:
- Use the actual `shell` tool for terminal commands.
- Do not call `shell_client`; it may not be registered.
- If a shell call fails, inspect the tool error before retrying.
- Do not claim a command succeeded unless its output confirms success.