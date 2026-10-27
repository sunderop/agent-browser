# Browser Automation Skill (`browse`)

## 🔒 CRITICAL SAFETY RULES
1. **HTTPS ONLY**: Never use `http://`.
2. **Human Approval**: `eval`, `submit`, `upload`, and clicks on "post/send/delete/buy/share/follow" trigger a 60s prompt. If "DENIED_BY_USER", STOP immediately.
3. **Token Secrecy**: Never log `~/.browse_token`.
4. **Pacing**: Use `browse pace 1000` for sensitive sites to avoid rate limits.

## ⚠️ BEST PRACTICES
- **Stale Elements**: Run `browse elements` or `browse find` after ANY page change.
- **Loading**: Use `browse waitfor-load` after navigation.
- **Viewport**: Use `browse scroll-to <n>` if a click fails or to trigger lazy-loading.
- **iframes**: Use `browse frames` to detect iframes, then `browse frame <n>` to switch context.
- **Downloads**: Use `browse download-wait <id>` to block until download completes.

## 📚 COMMAND REFERENCE

### Navigation & State
- `browse open <url>` / `browse url` / `browse back` / `browse forward`
- `browse waitfor-load` / `browse waitfor-text "<text>"` / `browse waitfor-gone "<css>"`

### Discovery
- `browse elements` / `browse find "<text>"` (Returns element index)
- `browse outline` / `browse inspect <n>`

### Interaction
- `browse click <n>` / `browse type <n> "<text>"` / `browse select <n> "<option>"`
- `browse scroll-to <n>` / `browse press <key>`
- `browse eval "<js>"` (Runs JS, requires approval)
- `browse submit <n>` (Submits form, requires approval)

### File Operations
- `browse upload <n> "<path>"` (Upload file to input, requires approval)
- `browse download "<url>"` (Trigger download, returns ID)
- `browse download-status <id>` (Get progress)
- `browse download-wait <id>` (Block until complete)
- `browse downloads` (List all downloads)

### iframe Support
- `browse frames` (List all iframes)
- `browse frame <n>` (Switch to iframe context)
- `browse frame-main` (Return to main page)

### Tab Management
- `browse tabs` / `browse newtab <url>` / `browse tab <n>` / `browse closetab`
- `browse tab-url "<partial-url>"` (Switch by URL match)
- `browse tab-title "<text>"` (Switch by title match)

### Pacing & Delays
- `browse pace <ms>` (Set minimum delay between commands)
- `browse wait-random <min> <max>` (Random delay in ms)

### Data Extraction
- `browse html` / `browse meta` / `browse screenshot`

### Automation
- `browse run <file>` (Executes workflow file line-by-line)
