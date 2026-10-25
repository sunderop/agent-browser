# Browser Automation Skill (`browse`)

You control a real Android WebView via the `browse` CLI tool. 
The browser runs on the user's phone. You MUST follow all safety and operational rules.

## 🔒 CRITICAL SAFETY RULES
1. **HTTPS ONLY**: Never use `http://`. The WebView blocks plain HTTP. Always use `https://`.
2. **Human Approval**: Actions involving "post", "send", "delete", "buy", "share", or "follow" will trigger a 60-second Allow/Deny prompt on the user's phone. 
   - If the output says "denied by user" or "DENIED_BY_USER", you MUST STOP immediately and inform the user. Do not retry.
3. **Token Secrecy**: Never print, log, or echo the `X-Token` or `~/.browse_token`.
4. **One Agent**: Do not run multiple concurrent `browse` sessions.

## ⚠️ KNOWN LIMITS & BEST PRACTICES
- **Stale Elements**: Element numbers become INVALID after any page change, click, or navigation. You MUST run `browse elements` again before every `click` or `type`.
- **Loading States**: `browse elements` may return empty if the page is loading. Always run `browse waitfor-load` after navigation before checking elements.
- **No Iframes**: You cannot see or interact with elements inside iframes.
- **Background Pauses**: Keep the app visible on the phone screen. Screenshots may be blank if the app is hidden.

## 📚 COMMAND REFERENCE
- `browse open <https://url>` : Load a URL.
- `browse waitfor-load` : Wait for page to finish loading.
- `browse waitfor-text "<text>"` : Wait up to 10s for text to appear.
- `browse elements` : Get numbered list of clickable/typeable items. 
- `browse click <n>` : Click element #n. (Triggers approval prompt for sensitive actions).
- `browse type <n> "<text>"` : Type text into element #n.
- `browse select <n> "<option>"` : Select dropdown option.
- `browse run <file>` : Execute a sequence of commands from a file. Halts on "denied by user".

## 🔄 OPTIMAL WORKFLOW
1. `browse open https://example.com`
2. `browse waitfor-load`
3. `browse elements` (Find the target number, e.g., "5")
4. `browse click 5`
5. `browse waitfor-load` (Page changed, numbers are now stale!)
6. `browse elements` (Find the new target number)
