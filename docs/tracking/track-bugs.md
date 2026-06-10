# Bug Tracking

Index shows ONLY open items.

---

## Open Bugs

### Silent miscompilation (recompiles cleanly, wrong semantics — highest priority)

| ID | Severity | Summary | Item File |
|---|---|---|---|
| BUG-2026-0081 | CRITICAL | `super.m()` decompiled as `this.m()` → infinite recursion | [BUG-2026-0081](items/BUG-2026-0081.md) |
| BUG-2026-0082 | CRITICAL | Post-increment/decrement in expression position returns wrong value (`return a++`) | [BUG-2026-0082](items/BUG-2026-0082.md) |
| BUG-2026-0083 | CRITICAL | Ternary value-merge drops condition/else-branch or consumer (silent wrong values) | [BUG-2026-0083](items/BUG-2026-0083.md) |
| BUG-2026-0085 | HIGH | Switch `break` emission: missing breaks → silent fall-through cascade | [BUG-2026-0085](items/BUG-2026-0085.md) |
| BUG-2026-0086 | HIGH | Loop-exit branch drops inner loop body (labeled continue) | [BUG-2026-0086](items/BUG-2026-0086.md) |
| BUG-2026-0087 | HIGH | `dup`+store alias re-evaluates expression (`(b=in.read())!=-1` reads stream twice) | [BUG-2026-0087](items/BUG-2026-0087.md) |
| BUG-2026-0088 | HIGH | Text block trailing whitespace (`\s`) lost → different string constant | [BUG-2026-0088](items/BUG-2026-0088.md) |
| BUG-2026-0089 | HIGH | Record canonical-ctor user code (validation/clamping) silently deleted | [BUG-2026-0089](items/BUG-2026-0089.md) |
| BUG-2026-0090 | HIGH | Annotation metadata dropped: @Retention/@Target/AnnotationDefault | [BUG-2026-0090](items/BUG-2026-0090.md) |

### Recompile failures

| ID | Severity | Summary | Item File |
|---|---|---|---|
| BUG-2026-0084 | HIGH | `wide` opcode not decoded (`i += 1000` vanishes) | [BUG-2026-0084](items/BUG-2026-0084.md) |
| BUG-2026-0091 | HIGH | Count-based finally dedup deletes trailing returns | [BUG-2026-0091](items/BUG-2026-0091.md) |
| BUG-2026-0092 | HIGH | Nested synchronized flattened; `static synchronized` modifier dropped | [BUG-2026-0092](items/BUG-2026-0092.md) |
| BUG-2026-0093 | HIGH | Cast operand printed without precedence parens (`(T) a >= 0 ? x : y`) | [BUG-2026-0093](items/BUG-2026-0093.md) |
| BUG-2026-0095 | HIGH | `&&`/`||` merge discards second condition block's statements | [BUG-2026-0095](items/BUG-2026-0095.md) |
| BUG-2026-0096 | MEDIUM | No-LVT slot typing: boolean as int; incompatible slot reuse shares one var | [BUG-2026-0096](items/BUG-2026-0096.md) |
| BUG-2026-0097 | HIGH | Local classes never loaded; anonymous `val$` captures unmapped; outer param leaks | [BUG-2026-0097](items/BUG-2026-0097.md) |
| BUG-2026-0098 | COSMETIC | Synthetic `Objects.requireNonNull(receiver)` leaks as statement | [BUG-2026-0098](items/BUG-2026-0098.md) |
| BUG-2026-0099 | MEDIUM | module-info `requires transitive`/`static` modifiers dropped | [BUG-2026-0099](items/BUG-2026-0099.md) |

### Pre-existing (re-verified 2026-06-10, still open)

| ID | Severity | Summary | Item File |
|---|---|---|---|
| BUG-2026-0053 | HIGH | Ternary in lambda body (→ consolidated in BUG-2026-0083) | [BUG-2026-0053](items/BUG-2026-0053.md) |
| BUG-2026-0056 | HIGH | Try-catch-finally: catch-conditional body still dropped (post-try part FIXED) | [BUG-2026-0056](items/BUG-2026-0056.md) |
| BUG-2026-0066 | HIGH | Switch-expressions: enum/yield-block/nested-merge arms still unreconstructed | [BUG-2026-0066](items/BUG-2026-0066.md) |
| BUG-2026-0067 | HIGH | Pattern switch: MatchException default + empty tail case still bail folding | [BUG-2026-0067](items/BUG-2026-0067.md) |
| BUG-2026-0068 | HIGH | Modern TWR: nested/multi-resource/effectively-final shapes still uncollapsed | [BUG-2026-0068](items/BUG-2026-0068.md) |
| BUG-2026-0069 | HIGH | Local decl/type loss (ternary part → 0083; slot part → 0096; generics → erasure plan) | [BUG-2026-0069](items/BUG-2026-0069.md) |
| BUG-2026-0071 | LOW | sealed/permits/non-sealed dropped on nested types (prereq for 0067) | [BUG-2026-0071](items/BUG-2026-0071.md) |
| BUG-2026-0080 | HIGH | Construct-matrix umbrella (37/57 clean; full plan in report-construct-matrix-gap-analysis.md) | [BUG-2026-0080](items/BUG-2026-0080.md) |

Closed 2026-06-10 (verified fixed in v1.9.0, files in docs/releases/v1.9.0/): BUG-2026-0052, BUG-2026-0054, BUG-2026-0055.
