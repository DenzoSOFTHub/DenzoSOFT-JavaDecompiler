# Improvement Tracking

Format: IMP-YYYY-NNNN
Index shows ONLY open items. Resolved items are in docs/releases/v{X.Y.Z}/.

---

## Open Improvements

| ID | Severity | Summary | Item File |
|---|---|---|---|
| IMP-2026-0070 | MEDIUM | `DenzoDecompiler` shares a stateful `JavaSourceWriter`: one instance per thread is required | report section 3 |
| IMP-2026-0071 | HIGH | Test-gate blind spots: two matrix fixtures absent from the repo, single debug mode, recompile-only oracle | report section 3 |
| IMP-2026-0072 | MEDIUM | Dual pipeline decision: freeze `cfg/jd` as a narrow fallback, port the record-switch fold to legacy, then delete (~3,842 lines) | report section 5 |
| IMP-2026-0073 | LOW | Report class-file version and preview flag (minor 0xFFFF) in the output header; pairs with BUG-2026-0118 (released in v1.11.0) | report section 3 |
| IMP-2026-0074 | MEDIUM | Single `TransformPipeline` for both flow paths (the chain is currently written twice and the two copies have diverged) | report section 3 |

Details: [docs/reports/report-java25-plus-audit.md](../reports/report-java25-plus-audit.md).
