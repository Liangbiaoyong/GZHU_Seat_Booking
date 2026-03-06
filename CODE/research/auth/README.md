# Auth Research Assets

This folder stores privacy-safe authentication research artifacts.

## Files

- `har/libbooking.sanitized.har`: Sanitized HAR capture with sensitive fields redacted.
- `sanitize_har.py`: Script used to sanitize a raw HAR into `har/libbooking.sanitized.har`.
- `analyze_har_flow.py`: HAR analysis script that reads sanitized HAR and generates a report.
- `libbooking_auth_flow_analysis.md`: Curated authentication flow notes.
- `non_webview_login.py`: Pure HTTP/HTTPS login prototype script.
- `monitor_token_cookie_lifetime.py`: Token/cookie lifetime monitor script.
- `non_webview_login_technical_spec.md`: Technical specification for non-WebView login.

## Output Convention

Transient outputs should be written under `CODE/research/auth/output/` and not committed by default.
