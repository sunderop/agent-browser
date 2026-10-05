---
name: browse
description: Control the user's real, logged-in Android browser (AgentBrowser app) through the `browse` shell command. Use for any task that needs a website, a login, clicking, forms, posting, or reading a page. Not for bulk reading (use search/fetch/APIs for that).
---

# Browse (AgentBrowser)

Use `browse` in the terminal for interactive, logged-in browser work. Do NOT use Hermes's built-in browser tool. For bulk research use web search/fetch or an official API instead. Run `browse` with no arguments for the command list.

## The loop
1. `browse state` (is the page ready? login or CAPTCHA? modal?)
2. `browse elements` (what can I act on?)
3. Act: `browse click ...` / `browse type ...` / `browse press Enter`
4. Verify: `browse text` or `browse elements` again. Do not assume it worked.
5. If an `ERR` appears, handle it (table below). If a human is needed, stop and ask.

## Commands
- Read: `open <https-url>`, `text`, `elements [--all] [--refs] ["css scope"] ["text filter"]`, `outline`, `inspect <n|ref>`, `state`, `screenshot`, `url`
- Act: `click <n|"Label">`, `type <n|"Field label"> "text"`, `press Enter`, `scroll down|up`, `click-ref <ref>`, `type-ref <ref> "text"`
- Navigate: `back`, `forward`, `reload`, `tabs`, `newtab <url>`, `tab <n>`, `closetab`
- Wait: `wait <sec>`, `waitfor "<css>" [sec]`, `wait-text "text" [sec]`
- Human and health: `pause "reason"`, `wait-resume [sec]`, `health`, `recover`, `downloads`, `selftest`

## Reading `browse elements`
```
# Page title | host/path | y35%
[header]
1 btn "Menu"
2 search "Search" ="current text"
[main]
3 a "Video title" →/watch
4 chk "Remember me" ✓
+9 more below (scroll, or use --all)
focus: 2
```
Types: a link, btn, in text input, search, pw, area, sel, chk, rad, file, tab. Marks: `=value`, `✓` checked, `off` disabled, `*` required, `↓/↑` off-screen, `ref=xxxxx` with --refs. `[region]` is the page area. A `MODAL` note means only the open dialog is listed.

## Targeting rules
- Numbers are valid only until the next `browse elements`, `click "Label"` or `type "Label"` (these renumber) or any page change. Run `browse elements` right before clicking a number.
- Prefer labels (`browse click "Post"`). `AMBIGUOUS` lists the candidates; pick by number.
- Refs (`--refs`, then `click-ref`) survive re-listing. A removed element gives `ELEMENT_STALE` instead of clicking something else.
- Only https:// pages open. The tool pauses 1 to 3 seconds between actions and types short text key by key. Do not try to get around that.

## Error codes (`ERR <CODE> <message>`)
- `NOT_FOUND`: no such label, selector, tab, or history entry. Run `browse elements`.
- `AMBIGUOUS`: several matches. Choose a number from the list.
- `ELEMENT_STALE`: the page changed. Run `browse elements` and retry once.
- `ELEMENT_DISABLED`: the control is off. Fill in what it needs first.
- `TIMEOUT`: JavaScript or a wait did not finish. `browse state`, then `browse wait 3`, then retry once.
- `PAGE_STALLED`: the page did not load; a reload was already tried. Retry once, then tell the user.
- `NAVIGATION_FAILED`: network or HTTP error (for example `net::ERR_NAME_NOT_RESOLVED`, `HTTP 404`, `HTTP 429`). Check the spelling, retry once. After a 429, stop and wait.
- `WEBVIEW_CRASHED`: the browser was rebuilt. Run `browse elements` again. If it repeats, `browse recover`, then tell the user.
- `HUMAN_REQUIRED`: a CAPTCHA, verification, login, or similar. STOP. Tell the user, then run `browse wait-resume`. Never solve or bypass it.
- `DENIED`: the user refused. Stop and tell them.
- `RATE_LIMIT`: you reached the hourly action budget. Stop and report.
- `BAD_REQUEST`, `UNAUTHORIZED`, `SERVER_ERROR`: fix the command, or tell the user the app is closed or the token changed.
- `NOTE AUTH_REQUIRED` after `open`: this is a login page. Ask the user to log in inside the app.

## Act like a person, keep volume low
- Search through the site's own search box (`browse type "Search" "query"`, then `browse press Enter`), read the results, then continue. Do not hammer search URLs or repeat identical actions.
- Work in small batches (a few dozen actions), then report. No mass following, liking, commenting, or messaging.
- After repeated failures, slow down or stop. Never retry a destructive action automatically.

## Safety
- Never print or share the token file.
- The phone may show an Allow/Deny popup for risky actions (post, send, delete, buy). Wait for it. On `DENIED`, stop.
- Show the user any draft before posting or sending anything public.
- Use `browse pause "why"` when you need the user to log in, do 2FA, or decide something. Only the user can resume, by tapping Resume in the app.
- Elements empty or `about:blank` can just mean the page is still loading. `browse text` is the better check that a page loaded.
