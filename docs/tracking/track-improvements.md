# Improvement Tracking

Format: IMP-YYYY-NNNN
Index shows ONLY open items. Resolved items are in docs/releases/v{X.Y.Z}/.

---

## Open Improvements

| ID | Severity | Summary | Item File |
|---|---|---|---|
| IMP-2026-0071 | HIGH | Test-gate blind spots: two matrix fixtures absent from the repo, single debug mode, recompile-only oracle | report section 3 |
| IMP-2026-0072 | HIGH | **Retire the JD pipeline** - decision taken, approved 2026-09-07: freeze the record-switch fold, port it to legacy (BUG-2026-0128), then delete `cfg/jd` (~3,800 lines) | [IMP-2026-0072.md](items/IMP-2026-0072.md) |
| IMP-2026-0073 | LOW | Report class-file version and preview flag (minor 0xFFFF) in the output header; pairs with BUG-2026-0118 (released in v1.11.0) | report section 3 |
| IMP-2026-0074 | MEDIUM | Single `TransformPipeline` for both flow paths (the chain is currently written twice and the two copies have diverged) | report section 3 |

Details: [docs/reports/report-java25-plus-audit.md](../reports/report-java25-plus-audit.md).
