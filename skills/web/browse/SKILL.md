# Browser Automation Skill (`browse`)

## 🔒 CRITICAL SAFETY RULES
1. **HTTPS ONLY**: Never use `http://`.
2. **Human Approval**: `eval`, `submit`, and clicks on "post/send/delete/buy/share/follow" trigger a 60s prompt. If "DENIED_BY_USER", STOP immediately.
3. **Token Secrecy**: Never log `~/.browse_token`.

## ⚠️ BEST PRACTICES
- **Stale Elements**: Run `browse elements` or `browse find` after ANY page change.
- **Loading**: Use `browse waitfor-load` after navigation.
- **Viewport**: Use `browse scroll-to <n>` if a click fails or to trigger lazy-loading.

## 📚 COMMAND REFERENCE
- `browse open <url>` / `browse url` / `browse back` / `browse forward`
- `browse waitfor-load` / `browse waitfor-text "<text>"` / `browse waitfor-gone "<css>"`
- `browse elements` / `browse find "<text>"` (Returns element index)
- `browse click <n>` / `browse type <n> "<text>"` / `browse select <n> "<option>"`
- `browse scroll-to <n>` / `browse press <key>`
- `browse eval "<js>"` (Runs JS, requires approval)
- `browse submit <n>` (Submits form/presses enter, requires approval)
- `browse html` / `browse meta` / `browse screenshot`
- `browse run <file>` (Executes a workflow file line-by-line)
