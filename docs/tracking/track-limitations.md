# Limitation Tracking

Format: LIM-NNNN
Index shows ONLY open and permanent limitations. Resolved items are in docs/releases/v{X.Y.Z}/.

---

## Open Limitations

| ID | Summary | Impact | Item File |
|---|---|---|---|
| LIM-0004 | Type annotations (Java 8+) parsed but not rendered | LOW | [LIM-0004](items/LIM-0004.md) |
| LIM-0005 | Pattern matching for switch (Java 21+) not detected | LOW | [LIM-0005](items/LIM-0005.md) |
| LIM-0008 | Multi-resource try-with-resources not reconstructed | MEDIUM | [LIM-0008](items/LIM-0008.md) |

## Permanent Limitations (cannot be fixed)

| ID | Summary | Reason | Item File |
|---|---|---|---|
| LIM-0003 | @Override not reconstructable | RetentionPolicy.SOURCE - not in class files | [LIM-0003](items/LIM-0003.md) |
