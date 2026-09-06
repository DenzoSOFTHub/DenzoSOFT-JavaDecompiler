# Request Tracking

Format: REQ-YYYY-NNNN
Status flow: TO_ANALYZE -> IN_ANALYSIS -> IN_PROGRESS -> RESOLVED -> CLOSED -> RELEASED
Each request has a dedicated file in docs/tracking/items/.

---

## Open Requests

| ID | Severity | Summary |
|---|---|---|
| REQ-2026-0001 | MEDIUM | `--semantic-check` CLI: decompile -> recompile -> compare structural census (catch/finally/synchronized/lambda statement counts) and exception-table sizes against the original class, using the project's own deserializer |

Details: [docs/reports/report-java25-plus-audit.md](../reports/report-java25-plus-audit.md) section 3.
