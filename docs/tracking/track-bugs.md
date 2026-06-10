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
| BUG-2026-0096 | MEDIUM | No-LVT slot typing: RESOLVED 2026-06-10 (boolean istore inference + per-slot category-conflict split; C_Unnamed residual 2 errors = 0067 flow-level, see item) | [BUG-2026-0096](items/BUG-2026-0096.md) |
| BUG-2026-0097 | HIGH | Local classes never loaded; anonymous `val$` captures unmapped; outer param leaks: RESOLVED 2026-06-10 (local-class load+emit as `_1Local`, capture-name reconciliation + writer val$ substitution map, member-inner outer param/requireNonNull/call-site `this` stripped; C_InnerClasses 4→0, C_InterfaceMethods 1→0, 6-case regression suite runtime-identical; residuals in item) | [BUG-2026-0097](items/BUG-2026-0097.md) |
| BUG-2026-0098 | COSMETIC | Synthetic `Objects.requireNonNull(receiver)` leaks as statement: RESOLVED 2026-06-10 (pop-handler identity guard on the dup twin; user-written calls preserved; C_FunctionalInterfaces/C_Optional clean) | [BUG-2026-0098](items/BUG-2026-0098.md) |
| BUG-2026-0099 | MEDIUM | module-info `requires transitive`/`static` modifiers dropped | [BUG-2026-0099](items/BUG-2026-0099.md) |

### Pre-existing (re-verified 2026-06-10, still open)

| ID | Severity | Summary | Item File |
|---|---|---|---|
| BUG-2026-0053 | HIGH | Ternary in lambda body (→ consolidated in BUG-2026-0083) | [BUG-2026-0053](items/BUG-2026-0053.md) |
| BUG-2026-0056 | HIGH | Try-catch-finally: catch-conditional body still dropped (post-try part FIXED) | [BUG-2026-0056](items/BUG-2026-0056.md) |
| BUG-2026-0067 | HIGH | Pattern switch: transform folding DONE (exhaustive tail reclaim, `_` components); guarded/unnamed statement-form leak = flow-level (see item residuals 1–2) | [BUG-2026-0067](items/BUG-2026-0067.md) |
| BUG-2026-0068 | HIGH | Modern TWR: RESOLVED 2026-06-10 (single/multi-resource, nested, TWR+catch/finally, single effectively-final all collapse; C_Java7TryResources 3→0). Residuals: multi-resource effectively-final, same-line multi-resource header, parameter resource (see item) | [BUG-2026-0068](items/BUG-2026-0068.md) |
| BUG-2026-0069 | HIGH | Local decl/type loss: erasure Stages A+B+C DONE 2026-06-10 (foreach back-prop, factory table, aaload component type, indy instantiatedMethodType SAM unification — C_VarInference/C_Optional/C_StreamsAdvanced/C_FunctionalInterfaces/C_VarLambdaParams cleared); remaining: (a) branch-scoped declaration hoisting (see item) | [BUG-2026-0069](items/BUG-2026-0069.md) |
| BUG-2026-0080 | HIGH | Construct-matrix umbrella (56/57 clean after wave 6: erasure Stage C + 0098 requireNonNull + cluster-22 inner classes; remaining C_Unnamed = 0067; full plan in report-construct-matrix-gap-analysis.md) | [BUG-2026-0080](items/BUG-2026-0080.md) |

Closed 2026-06-10 (verified fixed in v1.9.0, files in docs/releases/v1.9.0/): BUG-2026-0052, BUG-2026-0054, BUG-2026-0055.
