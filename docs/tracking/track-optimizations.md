# Optimization Tracking

Format: OPT-NNNN
Index shows ONLY open items. Resolved items are in docs/releases/v{X.Y.Z}/.

---

## Open Optimizations

| ID | Summary | Impact | Effort |
|---|---|---|---|
| OPT-0001 | Reduce bytecode scanning from 4 to 2 passes | LOW-MEDIUM | MEDIUM |
| OPT-0008 | `ObjectType(String)` recomputes qualified/simple names per construction (~4%) | LOW | LOW |
| OPT-0009 | Back-edge search is a repeated DFS per conditional block (8.3% inclusive, O(B^2)) | MEDIUM | MEDIUM |
| OPT-0010 | Redundant full bytecode scans per method (confirms OPT-0001 with anchors) | LOW-MEDIUM | MEDIUM |
| OPT-0011 | Small allocation / boxing churn | LOW | LOW |
| OPT-0012 | Converter and writer extraction seams (confirms ROADMAP section 3) | - | MEDIUM |

Details and measurements: [docs/reports/report-java25-plus-audit.md](../reports/report-java25-plus-audit.md) sections 2.5 and 3.
