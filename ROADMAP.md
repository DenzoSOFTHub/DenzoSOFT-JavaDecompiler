# DenzoSOFT Java Decompiler - Roadmap

Documento di pianificazione per ottimizzazioni, miglioramenti strutturali, stabilita, compatibilita e manutenibilita.

Stato attuale: **v1.0.0-SNAPSHOT** - 153+ classi decompilate, 16 test, 0 errori, ~4,000 classi/sec.

---

## 0. LIMITAZIONI DA AFFRONTARE (POST v1.0)

### 0.1 String switch reconstruction
Il pattern compilato (hashCode → equals → index → switch) non viene ricostruito come `switch(string)`.
L'output mostra il pattern a 2 fasi che non e direttamente ricompilabile come switch su String.
**Workaround**: l'output e funzionalmente corretto ma con if-else chain anziche switch.

### 0.2 Try-with-resources
Il pattern compilato (addSuppressed, nested try-catch) non viene ricostruito come `try(resource)`.
**Workaround**: il decompiler emette codice lineare (senza try-catch wrapper) quando rileva il pattern TWR, garantendo compilabilita.

### 0.3 Multi-catch body
In alcuni casi il body dei catch condivisi (es. `e.printStackTrace()`) viene perso quando il handler block e gia stato visitato dal CFG.

### 0.4 Classi anonime e inner classes inline
Le classi interne e anonime vengono decompilate separatamente. Devono essere inlined nel sorgente della classe padre per produrre un .java completo e ricompilabile.

### 0.5 API Javassist-like
API per navigazione programmatica dei .class: CtClass, CtMethod, CtConstructor, CtField con accesso a attributi, constant pool, bytecode, annotazioni.

---

## 1. OTTIMIZZAZIONI PERFORMANCE RIMANENTI

### 1.1 Ridurre scansioni bytecode da 4 a 2
**Stato**: Non implementato
**Impatto**: BASSO-MEDIO | **Effort**: MEDIO

Il bytecode di ogni metodo viene attualmente scansionato 4 volte:
1. `ControlFlowGraph.build()` - identifica i leader (block boundaries)
2. `ControlFlowGraph.classifyBlock()` - ri-scansiona ogni blocco per trovare l'ultima istruzione
3. Pre-declaration scan - cerca le istruzioni store per il tracking variabili
4. `decodeBasicBlock()` - decodifica ogni istruzione per generare AST

**Soluzione**: Combinare i pass 1+2+3 in un singolo pass durante `build()`. Raccogliere leader, ultimo opcode per blocco, e store-locations simultaneamente.

**File coinvolti**: `ControlFlowGraph.java`, `ClassFileToJavaSyntaxConverter.java`

---

### 1.2 Unified SignatureParser
**Stato**: Non implementato
**Impatto**: BASSO | **Effort**: MEDIO

`SignatureParser` ha 6 metodi statici indipendenti (`parseFieldSignature`, `parseClassTypeParameters`, `parseClassSuperType`, `parseClassInterfaceTypes`, `parseMethodParameterTypes`, `parseMethodReturnType`) che ri-parsano la stessa stringa da zero. Il writer chiama 3 di questi per ogni metodo con signature generica.

**Soluzione**: Creare una classe `ParsedSignature` che parsa una volta e espone tutte le viste:
```java
ParsedSignature sig = SignatureParser.parse(signature);
sig.getTypeParameters();    // "<T>"
sig.getReturnType();        // "List<String>"
sig.getParameterTypes();    // ["T", "int"]
```

**File coinvolti**: `SignatureParser.java`, `JavaSourceWriter.java`

---

### 1.3 Estendere cache flyweight a -128..127
**Stato**: Parziale (cache -1..5)
**Impatto**: BASSO | **Effort**: BASSO

Attualmente `IntegerConstantExpression.valueOf()` cachea solo i valori -1 attraverso 5. Estendere a -128..127 (come `Integer.valueOf` del JDK) coprirebbe tutti i `bipush` e la maggior parte dei `sipush`.

**File coinvolti**: `IntegerConstantExpression.java`

---

### 1.4 Lazy AST / rappresentazione compatta
**Stato**: Non implementato
**Impatto**: MEDIO | **Effort**: ALTO

Attualmente ogni istruzione bytecode genera 1-3 oggetti Expression/Statement completi. Per metodi con 10K+ istruzioni, questo significa 10K-30K oggetti short-lived.

**Soluzione**: Rappresentazione intermedia compatta (es. array di int con opcode + operand indices) materializzata in AST nodes solo durante la fase di write. Ridurrebbe drasticamente le allocazioni.

**File coinvolti**: Architettura converter/writer, nuovo modulo IR

---

### 1.5 Parallelizzazione decompilazione JAR
**Stato**: Abilitato (thread safety fixato) ma non implementato
**Impatto**: ALTO | **Effort**: MEDIO

Il fix thread-safety (converter per-call) abilita la decompilazione parallela. Implementare un mode batch nel `Main.java` che usa un `ExecutorService` per decompilare classi in parallelo.

**Soluzione**:
```java
ExecutorService pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
for (ClassEntry entry : classes) {
    pool.submit(() -> decompiler.decompile(loader, printer, entry.name));
}
```

**File coinvolti**: `Main.java`, nuovo `BatchDecompiler.java`

---

### 1.6 Cache risultati per integrazione IDE
**Stato**: Non implementato
**Impatto**: ALTO (per use-case IDE) | **Effort**: MEDIO

Cache LRU dei `JavaSyntaxResult` keyed by `className + bytecodeHash`. Le IDE richiedono la stessa classe multiple volte durante la navigazione.

**File coinvolti**: Nuovo `DecompilationCache.java`, `DenzoDecompiler.java`

---

### 1.7 Iterativizzare ricorsione in StructuredFlowBuilder
**Stato**: Non implementato
**Impatto**: BASSO (previene crash) | **Effort**: MEDIO

`buildFromBlock` e `hasDirectBackEdge` sono ricorsivi. Metodi con nesting molto profondo (>100 livelli) possono causare `StackOverflowError`. Convertire in iterativo con stack esplicito.

**File coinvolti**: `StructuredFlowBuilder.java`

---

## 2. MIGLIORAMENTI STRUTTURALI E MANUTENIBILITA

### 2.1 Decomposizione del God Class Converter
**Stato**: Non implementato
**Impatto**: CRITICO per manutenibilita | **Effort**: ALTO

`ClassFileToJavaSyntaxConverter.java` e un god class di **2742 righe** con **58 metodi** che mescola **6 responsabilita** diverse:

| Responsabilita | Metodi | Righe stimate |
|---|---|---|
| Orchestrazione pipeline | 3 | ~100 |
| Bytecode decoding (opcodes) | 7 | ~900 |
| Boolean simplification | 6 | ~200 |
| For-each pattern detection | 15+ | ~400 |
| Try-catch reconstruction | 10+ | ~300 |
| Utilita (parseType, etc.) | 8 | ~150 |

**Soluzione**: Estrarre in classi dedicate:
```
service/converter/
  ClassFileToJavaSyntaxConverter.java  ← orchestratore (~200 righe)
  bytecode/
    BytecodeDecoder.java               ← switch opcode (~900 righe)
    BranchConditionExtractor.java      ← condizioni branch (~200 righe)
  transform/
    BooleanSimplifier.java             ← post-processing boolean
    ForEachDetector.java               ← pattern detection for-each
    TryCatchReconstructor.java         ← exception table analysis
    VariableDeclarationTracker.java    ← pre-declaration logic
  cfg/
    ControlFlowGraph.java              ← (gia separato)
    StructuredFlowBuilder.java         ← (gia separato)
    BasicBlock.java                    ← (gia separato)
```

---

### 2.2 AST Transformer Pipeline
**Stato**: Non implementato
**Impatto**: ALTO per estensibilita | **Effort**: MEDIO

I post-processing passes (boolean simplify, for-each detect, try-catch wrap) sono hardcoded e chiamati in sequenza fissa. Non e possibile aggiungere nuovi pass senza modificare il converter.

**Soluzione**: Interfaccia `AstTransformer` con pipeline configurabile:
```java
public interface AstTransformer {
    List<Statement> transform(List<Statement> statements, TransformContext context);
}

// Pipeline:
List<AstTransformer> pipeline = Arrays.asList(
    new TryCatchReconstructor(),
    new ForEachDetector(),
    new BooleanSimplifier(),
    new TernaryDetector(),        // facile da aggiungere
    new UnusedVarRemover()        // facile da aggiungere
);
```

**File coinvolti**: Nuova interfaccia + refactoring dei pass esistenti

---

### 2.3 Visitor Pattern per AST
**Stato**: Parziale (ExpressionVisitor/StatementVisitor esistono ma non usati)
**Impatto**: MEDIO | **Effort**: MEDIO

Le interfacce `ExpressionVisitor` e `StatementVisitor` esistono nel modello ma non sono usate da nessuno. Il `JavaSourceWriter` usa catene di `if/else instanceof` (1147 righe).

**Soluzione**: Implementare un `AbstractExpressionVisitor` e `AbstractStatementVisitor` con metodi default, e usarli nel writer e nei transformer.

**File coinvolti**: `JavaSourceWriter.java`, nuove classi visitor

---

### 2.4 Eliminare duplicazione skipOperands
**Stato**: Non implementato
**Impatto**: BASSO | **Effort**: BASSO

`ControlFlowGraph.skipOperands()` e `ClassFileToJavaSyntaxConverter.skipOpcodeOperands()` contengono la stessa logica di skip degli operandi bytecode (~200 righe duplicate).

**Soluzione**: Estrarre in una classe condivisa `OpcodeInfo.java`:
```java
public final class OpcodeInfo {
    public static int operandSize(int opcode, byte[] bytecode, int pc) { ... }
    public static boolean isBranch(int opcode) { ... }
    public static boolean isReturn(int opcode) { ... }
}
```

**File coinvolti**: `ControlFlowGraph.java`, `ClassFileToJavaSyntaxConverter.java`, nuovo `OpcodeInfo.java`

---

### 2.5 Separazione modello da logica
**Stato**: Non implementato
**Impatto**: MEDIO | **Effort**: MEDIO

`JavaSyntaxResult` contiene sia i dati (campi) che la logica di query (`isInterface()`, `isRecord()`, `isSealed()`). I `FieldDeclaration` e `MethodDeclaration` inner classes hanno campi public final e metodi di query mescolati.

**Soluzione**: Rendere i result objects puri DTO senza logica, spostando le query in utility methods o nel writer.

---

## 3. MIGLIORAMENTI STABILITA

### 3.1 Gestione robusta di class file malformati
**Stato**: Parziale
**Impatto**: ALTO | **Effort**: MEDIO

Il parser attualmente lancia eccezioni non gestite su class file corrotti o troncati. `ByteReader` non verifica i bounds prima di leggere.

**Soluzione**:
- Aggiungere bounds checking in `ByteReader.readUnsignedByte/Short/Int`
- Wrappare le eccezioni di parsing in `ClassFileFormatException` con contesto (offset, expected vs actual)
- Aggiungere validazione del constant pool (indici validi, tipi corretti)
- Timeout per decompilazione di singolo metodo (prevenire loop infiniti nel CFG)

**File coinvolti**: `ByteReader.java`, `ConstantPool.java`, `ClassFileDeserializer.java`

---

### 3.2 Limiti di sicurezza per input adversariale
**Stato**: Non implementato
**Impatto**: ALTO | **Effort**: BASSO

Un class file malevolo potrebbe causare:
- Stack overflow nel CFG ricorsivo (nesting >1000 livelli)
- OutOfMemoryError con constant pool gigante
- Loop infinito nel StructuredFlowBuilder

**Soluzione**:
- Limite massimo di bytecode per metodo (es. 64KB come da JVM spec)
- Limite massimo di profondita ricorsione nel CFG (es. 200)
- Limite massimo di nodi AST per metodo (es. 100K)
- Timeout configurabile per decompilazione singola classe

```java
public class DecompilerLimits {
    public int maxMethodBytecodeSize = 65535;
    public int maxRecursionDepth = 200;
    public int maxAstNodes = 100000;
    public long maxDecompileTimeMs = 30000;
}
```

---

### 3.3 Logging strutturato
**Stato**: Non implementato
**Impatto**: MEDIO | **Effort**: BASSO

Nessun sistema di logging. Gli errori vengono emessi come commenti nel codice o swallowed silenziosamente. Non c'e modo di diagnosticare problemi senza debugger.

**Soluzione**: Interfaccia `DecompilerLogger` leggera (nessuna dipendenza esterna):
```java
public interface DecompilerLogger {
    void debug(String message);
    void warn(String message);
    void error(String message, Throwable cause);
}
```
Con implementazioni: `NullLogger`, `ConsoleLogger`, `StringLogger`.

**File coinvolti**: Nuova interfaccia, propagazione nel converter e writer

---

### 3.4 Gestione corretta degli attributi sconosciuti
**Stato**: Parziale
**Impatto**: MEDIO | **Effort**: BASSO

L'`AttributeParser` skippa gli attributi sconosciuti silenziosamente. Se la lunghezza dell'attributo e errata, il reader si disallinea e tutto il parsing successivo fallisce.

**Soluzione**:
- Salvare l'offset prima di parsare un attributo
- Dopo il parsing, verificare che esattamente `length` bytes sono stati consumati
- Se no, resettare l'offset a `savedOffset + length` e loggare un warning

```java
int savedOffset = reader.getOffset();
Attribute attr = parseAttributeContent(name, length, reader, pool);
int consumed = reader.getOffset() - savedOffset;
if (consumed != length + 6) { // 6 = name_index(2) + length(4)
    reader.setOffset(savedOffset + length + 6);
    logger.warn("Attribute " + name + " consumed " + consumed + " bytes but declared " + length);
}
```

---

### 3.5 Test di regressione automatici
**Stato**: Parziale (16 test manuali)
**Impatto**: ALTO | **Effort**: MEDIO

I test attuali verificano solo la presenza di keyword nell'output, non la correttezza strutturale. Non c'e CI/CD, non ci sono test per edge cases.

**Soluzione**:
- Aggiungere test per ogni opcode JVM (almeno i 50 piu comuni)
- Test per ogni versione Java (1.0, 5, 8, 11, 17, 21, 25) con feature specifiche
- Test per class file malformati (fuzzing basico)
- Test di round-trip: compila → decompila → verifica che il risultato compila
- Integrazione con JUnit (aggiungere come test dependency nel POM)

---

### 3.6 Gestione OutOfMemoryError
**Stato**: Non implementato
**Impatto**: MEDIO | **Effort**: BASSO

La decompilazione di classi molto grandi puo esaurire la memoria senza diagnostica utile.

**Soluzione**:
- Stimare la memoria necessaria prima di iniziare (es. 10x la dimensione del bytecode)
- Se la stima supera un limite configurabile, emettere un warning
- Wrappare `OutOfMemoryError` in `DecompilationException` con info sulla classe che ha causato il problema

---

## 4. MIGLIORAMENTI COMPATIBILITA

### 4.1 Supporto completo Java 9+ Modules
**Stato**: Parziale (attributo Module parsato, non nel writer)
**Impatto**: MEDIO | **Effort**: MEDIO

Il `ModuleAttribute` viene parsato ma il writer non genera mai `module-info.java`.

**Soluzione**: Aggiungere un path speciale nel writer per `module-info.class`:
```java
module com.example.myapp {
    requires java.base;
    requires transitive java.logging;
    exports com.example.api;
    opens com.example.internal to com.example.test;
    uses com.example.spi.Service;
    provides com.example.spi.Service with com.example.impl.ServiceImpl;
}
```

**File coinvolti**: `JavaSourceWriter.java`, `JavaSyntaxResult.java`

---

### 4.2 Supporto completo annotazioni con valori
**Stato**: Parziale
**Impatto**: MEDIO | **Effort**: BASSO

Le annotazioni con valori complessi (array, nested, enum) sono parsate ma la serializzazione nel writer potrebbe avere edge cases.

**Verifiche necessarie**:
- `@Table(name = "users", schema = "public")` - multi-value
- `@Roles({"ADMIN", "USER"})` - array value
- `@Config(timeout = @Duration(value = 30, unit = TimeUnit.SECONDS))` - nested annotation
- `@Retention(RetentionPolicy.RUNTIME)` - enum value

---

### 4.3 Supporto text blocks (Java 15+)
**Stato**: Modello AST presente, detection non implementata
**Impatto**: BASSO | **Effort**: MEDIO

`TextBlockExpression` esiste nel modello ma il converter non lo genera mai. Le text blocks nel bytecode sono stringhe normali con newline embedded.

**Soluzione**: Nel converter, quando una `StringConstantExpression` contiene `\n` e supera una certa lunghezza, convertirla in `TextBlockExpression`.

---

### 4.4 Supporto pattern matching completo (Java 21+)
**Stato**: Modello AST presente, detection parziale
**Impatto**: BASSO | **Effort**: ALTO

- `instanceof` con pattern variable: modello presente, bytecode detection non implementata
- `switch` con pattern: non implementato
- Record patterns: non implementato
- Guarded patterns (`when`): non implementato

**Soluzione**: Richiede analisi dei pattern bytecode generati da javac per instanceof+cast+store e per il nuovo switch con type checking.

---

### 4.5 Supporto preview features flag
**Stato**: Non implementato
**Impatto**: BASSO | **Effort**: BASSO

Quando `minor_version == 0xFFFF`, la classe usa feature preview della sua major version. Il decompiler dovrebbe:
- Emettere un commento `// Compiled with preview features enabled`
- Eventualmente adattare il comportamento per feature preview note

**File coinvolti**: `JavaSourceWriter.java`

---

### 4.6 Compatibilita output con diverse versioni Java
**Stato**: Non implementato
**Impatto**: MEDIO | **Effort**: MEDIO

L'output decompilato usa sempre la sintassi piu moderna possibile. Sarebbe utile un'opzione per targetizzare una specifica versione Java dell'output:
- `--target 8`: non usare `var`, text blocks, switch expressions
- `--target 11`: non usare text blocks, switch expressions, records
- `--target 17`: non usare pattern matching switch

**File coinvolti**: `JavaSourceWriter.java`, nuova classe `OutputOptions`

---

## 5. MIGLIORAMENTI QUALITA OUTPUT

### 5.1 Ricostruzione operatore || e &&
**Stato**: Non implementato
**Impatto**: ALTO | **Effort**: MEDIO

Il bytecode compila `if (a || b)` come due branch separati:
```
ifne label   // if a goto label
ifne label   // if b goto label
```
Il decompiler attualmente mostra due `if` separati invece di un singolo `if (a || b)`.

**Soluzione**: Pattern detection nel `StructuredFlowBuilder`: quando due conditional blocks consecutivi hanno lo stesso target e il primo fall-through va al secondo, e un OR. Quando hanno fall-through diversi ma branch allo stesso target, e un AND.

---

### 5.2 Ricostruzione ternary annidati
**Stato**: Parziale (singolo livello funziona)
**Impatto**: BASSO | **Effort**: MEDIO

`a < b ? a : (b > c ? c : b)` - ternary annidati non vengono ricostruiti. Il primo livello funziona ma il nested fallisce.

**Soluzione**: Applicare ricorsivamente il ternary detection nei blocchi "value producer".

---

### 5.3 Ricostruzione for classico da while
**Stato**: Non implementato
**Impatto**: MEDIO | **Effort**: MEDIO

Attualmente `for (int i = 0; i < n; i++)` viene decompilato come:
```java
int i = 0;
while (i < n) {
    // body
    i++;
}
```

**Soluzione**: Post-processing che rileva il pattern: `decl + while(cond) { body; update; }` e lo converte in `for (decl; cond; update) { body; }`. Il criterio: l'ultimo statement del body e un incremento/decremento della variabile usata nella condizione.

---

### 5.4 Soppressione metodi auto-generati nei record
**Stato**: Non implementato
**Impatto**: BASSO | **Effort**: BASSO

Per i record, `equals()`, `hashCode()`, `toString()` sono auto-generati dal compilatore. Dovrebbero essere soppressi nell'output se corrispondono all'implementazione default.

**Soluzione**: Controllare se il metodo chiama `ObjectMethods.bootstrap` via invokedynamic. Se si, non emetterlo.

---

### 5.5 Soppressione cast ridondanti
**Stato**: Non implementato
**Impatto**: BASSO | **Effort**: BASSO

L'output mostra cast non necessari come `(List) this.data.get(key)` quando il tipo e gia noto dal contesto.

**Soluzione**: Nel writer, prima di emettere un cast, verificare se il tipo target e lo stesso del tipo dell'espressione. Se si, omettere il cast.

---

### 5.6 Ricostruzione string concatenation completa
**Stato**: Parziale (makeConcatWithConstants gestito)
**Impatto**: BASSO | **Effort**: MEDIO

La string concatenation con `makeConcatWithConstants` produce `"" + a + b` quando il template non e disponibile. Il template (es. `"Name: \1, Age: \1"`) e nei BootstrapMethods arguments ma non viene letto.

**Soluzione**: Leggere il template dal BootstrapMethods attribute e ricostruire `"Name: " + name + ", Age: " + age` correttamente.

---

### 5.7 Ricostruzione lambda body
**Stato**: Non implementato (placeholder `() -> { }`)
**Impatto**: MEDIO | **Effort**: ALTO

Le lambda attualmente mostrano solo la firma senza body. Il body e compilato come metodo sintetico privato nella stessa classe (es. `lambda$run$0`).

**Soluzione**: Quando si incontra un `invokedynamic` con `LambdaMetafactory`, trovare il metodo sintetico target nel `BootstrapMethods` attribute, decompilarlo, e inserire il body nella `LambdaExpression`.

---

## 6. PRIORITA CONSIGLIATA

### Immediata (v1.1)
1. **3.1** Gestione class file malformati
2. **3.2** Limiti di sicurezza
3. **5.3** Ricostruzione for classico da while
4. **5.1** Ricostruzione || e &&
5. **3.5** Test di regressione estesi

### A breve termine (v1.2)
6. **2.1** Decomposizione god class Converter
7. **2.2** AST Transformer pipeline
8. **2.4** Eliminare duplicazione skipOperands
9. **5.5** Soppressione cast ridondanti
10. **5.4** Soppressione metodi record auto-generati

### A medio termine (v2.0)
11. **1.5** Parallelizzazione JAR
12. **2.3** Visitor pattern per AST
13. **4.1** Supporto completo Java 9+ Modules
14. **5.7** Ricostruzione lambda body
15. **5.6** String concatenation completa
16. **4.6** Compatibilita output multi-versione

### A lungo termine (v3.0)
17. **1.4** Lazy AST / rappresentazione compatta
18. **4.4** Pattern matching completo (Java 21+)
19. **1.6** Cache risultati IDE
20. **3.3** Logging strutturato
21. **2.5** Separazione modello da logica

---

## 7. METRICHE ATTUALI DI RIFERIMENTO

| Metrica | Valore |
|---|---|
| File sorgenti | ~115 |
| Righe di codice (LoC) | ~12,000 |
| Classi decompilate senza errori | 139/139 |
| Test automatici | 16/16 |
| Throughput | ~4,700 classi/sec |
| Avg per classe | 0.21 ms |
| Heap usage | 1.7 MB per 200 classi |
| Java source compat | 1.6 |
| Class file compat | Java 1.0 - Java 25 (v45-v69) |

---

*Ultimo aggiornamento: Marzo 2026*
