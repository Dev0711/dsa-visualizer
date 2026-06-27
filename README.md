# DSA Visualizer

A Spring Boot + React application that compiles a pasted LeetCode-style
`Solution` class in memory, launches it as a real child JVM, attaches a
debugger to it via JDI (Java Debug Interface — the same API IntelliJ/Eclipse
use internally), and steps through it line by line, capturing local variable
values at every step. The frontend renders this trace as a synced
code-highlight + animated data-structure visualization with step-through
controls, variable diff highlighting, and array box diagrams.

**Current status:** Phase 1 (backend pipeline) and Phase 2 (React frontend)
are complete. Phase 3/4 (LinkedList, Tree, Graph visualization) are next.

**Run environment confirmed by the project owner:** JDK 17+ and Maven 3.8+
already installed locally (Windows, paths like `D:\dsa-visualizer`).
Developed/iterated in IntelliJ using both `java -jar` and the IDE's
Run/Debug button.

---

## ⚠️ The one thing that will block you if you skip it

`com.sun.jdi.*` lives in the **`jdk.jdi` module**, which is NOT on the
default module path. Skipping this gives a `ClassNotFoundException` or
`module not found` error the moment `JdiStepEngine` is touched.

**You must explicitly add the module** when both *compiling* and *running*
this Spring Boot app itself (not the debuggee — the debuggee is just plain
user code with no JDI dependency of its own).

### Compiling (Maven)

```bash
export JDK_JAVA_OPTIONS="--add-modules jdk.jdi"
mvn clean package
```

### Running — three ways, pick whichever fits your workflow

**Terminal / jar:**
```bash
java --add-modules jdk.jdi -jar target/dsa-visualizer.jar
```

**IntelliJ Run/Debug button:** Run/Debug Configurations → your
`DsaVisualizerApplication` config → "Modify options" → enable **VM options**
field → enter:
```
--add-modules jdk.jdi
```
Apply → OK, then use the green ▶ Run or 🐞 Debug button as normal. Debug
mode is genuinely useful here since you're debugging a debugger — you can
breakpoint inside `JdiStepEngine.java` itself and inspect the live JDI
event loop (`eventSet`, `vm.allThreads()`, etc.).

If `JDK_JAVA_OPTIONS` is already exported in your shell from the build
step, plain `java -jar target/dsa-visualizer.jar` also picks it up
automatically.

---

## Prerequisites

- **A full JDK 17+** (not just a JRE). Verify with `javac -version` and
  `java -version` — both must report the same major version, and `javac`
  must exist. The app throws a clear error if
  `ToolProvider.getSystemJavaCompiler()` returns null (JRE-only install).
- **Maven 3.8+**
- Nothing else — no Lombok, no DB. Lean on purpose for Phase 1.

---

## Running it end to end

### Backend (Spring Boot)

```bash
cd dsa-visualizer
set JDK_JAVA_OPTIONS=--add-modules jdk.jdi
mvn spring-boot:run
```

### Frontend (React — Phase 2)

```bash
cd frontend
npm install        # first time only
npm run dev
```

Open **http://localhost:5173** in your browser. The Vite dev server
automatically proxies `/api/*` requests to the Spring Boot backend at
`:8080`. The app is pre-filled with a Two Sum example — hit **"Compile &
Trace"** and you should see:
- Code panel with animated line highlighting
- Live variables panel with diff highlighting (changed values pulse green)
- Array visualization with animated boxes
- Step controls with play/pause, speed, and scrub slider
- Return value display

**Note:** The Phase 1 test page (`src/main/resources/static/index.html`)
is still available as a fallback at `http://localhost:8080/index.html`
if you just want to test the backend directly.

### Frontend tech stack
- **Vite** — dev server + build tool
- **React 19** — UI framework
- **CodeMirror 6** — Java syntax highlighting in the code editor
- **Framer Motion** — smooth animations (line highlights, variable diffs,
  array box transitions)
- **Vanilla CSS** with CSS custom properties (design tokens) — no
  Tailwind, no CSS-in-JS; all theming is via `index.css` tokens

---

## How a request flows through the code

1. **`VisualizeController`** — two endpoints:
   - `POST /api/visualize` — full compile + trace pipeline
   - `POST /api/parse-signature` — lightweight signature extraction for
     dynamic frontend input generation
   Both endpoints validate inputs at the controller level (fail fast).
2. **`VisualizationService.visualize()`** — orchestrates everything below,
   wrapped in a try/finally that always cleans up the temp directory:
   - **`CodeWrapper.wrap()`** — keeps the pasted class **byte-for-byte**
     (so JDI's reported line numbers match what the user sees in the
     editor) and appends a generated `Main` class with parsed argument
     literals and a call to the target method. Both classes are kept
     **non-public** so the generated file can have any filename.
   - **`SignatureParser`** — regex-extracts the target method's return type
     and parameter types/names from the pasted code. Deliberately
     regex-based, not full AST parsing — sufficient for typical LeetCode
     signatures; see Known Limitations below for where this will need
     upgrading.
   - **`ArgLiteralBuilder`** — turns each raw string arg (e.g.
     `"2,7,11,15"`) into a valid Java literal declaration (e.g.
     `int[] arg0 = {2,7,11,15};`). Supports `int`, `long`, `double`,
     `boolean`, `char`, `String`, and 1D arrays of those.
   - **`CompilerService.compile()`** — `javax.tools.JavaCompiler` in
     memory, compiling to real `.class` files on disk (required since the
     debuggee must be a launchable child process, not just bytes in
     memory). Compiled with `-g` for full debug info — **non-negotiable**,
     since JDI needs this for variable names and accurate line numbers.
   - **`JdiStepEngine.trace()`** — launches the compiled `Main` as a real
     child JVM via JDI's `CommandLineLaunch` connector, installs a
     `ThreadStartRequest` first (to reliably catch the main thread even for
     fast-finishing code — see Bug #2 below for why this matters), then a
     line-step request filtered to skip `java.*`/`javax.*`/`sun.*`/`jdk.*`
     internals, and pumps the JDI event queue. Captures a `StepSnapshot`
     (line number + all visible local variables) every time execution
     lands on a line inside the user's `Solution` class specifically
     (Main's own setup lines are filtered out).
   - **`JdiValueConverter`** — unwraps JDI's remote-proxy `Value` objects
     into plain Java values Jackson can serialize. Primitives/String/arrays
     convert directly. As of the toString() fix (see Bug #3 below),
     `java.util.*`/`java.lang.*` objects (HashSet, HashMap, ArrayList, etc.)
     are rendered by actually invoking their `toString()` live in the
     debuggee. Custom user-defined types (future TreeNode/ListNode) still
     render as an opaque `<ClassName#id>` placeholder pending Phase 3/4's
     pointer-chasing work.
3. Response: `VisualizeResponse` — `success` with the full step array +
   return value + original source lines, or `failure` with a phase
   (`compile`/`input`/`internal`/`trace-truncated`) and message. **On
   compile failure specifically**, the response also includes
   `generatedSource` — the exact `.java` file text that was sent to
   `javac` — so the test page can show you precisely what failed instead
   of leaving you to guess.

---

## Bugs found and fixed so far (chronological, for context)

### Bug #1 — `IncompatibleThreadStateException` not caught (compile error)
`thread.frame(0)` and `frame.getValue()` both declare this checked
exception in the JDI API. Original code only caught
`AbsentInformationException`. **Fix:** both exceptions now caught together
in `JdiStepEngine.captureSnapshot()`.

### Bug #2 — Return type regex capturing the access modifier (compile error)
The regex's optional `(?:public|private|protected)?` group could match
zero characters while the non-greedy return-type capture group swallowed
`"public boolean"` (or `"public int[]"`, etc.) together as one unit —
because whitespace was part of that group's allowed character class. This
generated invalid Java like `public boolean result = solution.foo(...);`
as a **local variable declaration**, which doesn't compile (`public` isn't
a valid modifier there). This affected **every example tested**, including
the original Two Sum default.

**Fix:** added `SignatureParser.stripLeadingModifiers()`, which strips
`public`/`private`/`protected`/`static`/`final` off the front of whatever
the regex captured, regardless of which group accidentally consumed them.
Verified by hand-simulation against `boolean`, `int[]`, `void`, and
multiple full sample problems (Two Sum, Contains Duplicate, Reverse
String, Valid Anagram) before shipping.

### Bug #3 (not a bug, a gap) — Collections rendered as empty string / opaque placeholder
`HashSet`/`HashMap`/`ArrayList` etc. are regular Java objects, so they hit
the generic `ObjectReference` branch in `JdiValueConverter`, which only
ever produced a placeholder like `<java.util.HashSet#12345>` — not genuinely
broken, just not useful for a *visualizer* where the user needs to actually
see `seen = [1, 2]`.

**Fix:** rather than hand-walking `HashMap`'s internal `table`/`Node` array
(real pointer-chasing work, same complexity tier as the LinkedList/Tree
support already scoped for later phases), `JdiValueConverter` now calls
`ObjectReference.invokeMethod(...)` to invoke the **live object's own
`toString()`** inside the debuggee, for `java.util.*`/`java.lang.*` types
only. This reuses each collection's already-correct `toString()` instead of
reimplementing bucket-array traversal.

**Safety boundary, deliberately enforced:** `invokeMethod` actually
**resumes the debuggee thread** to run the method, then re-suspends it —
running arbitrary live code as a side effect of inspecting a variable is
only acceptable for trusted standard-library types with known-safe
`toString()` implementations. It is intentionally **never** invoked on
custom user-defined types (e.g. a future pasted `TreeNode`/`ListNode`
class), both for safety (an unknown `toString()` could misbehave) and
because those types are meant to be rendered as actual structural diagrams
later, not flattened strings.

**Verification status:** the JDI `invokeMethod` signature and exception
types were checked against Oracle's official JDI docs, and the event-loop
structure was traced to confirm `captureSnapshot()` (and the nested
`invokeMethod` call) always completes before `eventSet.resume()` is called
— meaning this doesn't fall into the deadlock scenario the JDI docs warn
about (calling `invokeMethod` from inside a still-blocked
`SUSPEND_ALL`-pending event handler). This was **not** executed against a
real JVM during development (the dev sandbox used has no JDK/JDI module
available) — it's spec-verified and logic-traced, not end-to-end tested.
If collection rendering still misbehaves, that's the next thing to debug.

---

## Known Phase 1 limitations (by design, not bugs)

- **No `TreeNode`/`ListNode`/2D-array argument support yet.**
  `ArgLiteralBuilder` throws a clear `UnsupportedOperationException` if you
  try passing one as an argument — Phase 3/4 scope (needs pointer-chasing
  construction logic, not array literals).
- **Custom object types still render as `<ClassName#id>` placeholders**
  in the variables panel, not their contents — see Bug #3 above. Only
  `java.util.*`/`java.lang.*` types get readable `toString()` rendering.
- **No recursion call-stack visualization yet.** The engine traces
  whichever frame is on top of the stack; recursive calls show repeated
  entries into the same lines, but call-stack depth itself isn't
  visualized separately yet.
- **`SignatureParser` is regex-based, not AST-based.** Handles standard
  single-line-ish signatures fine (including brace-on-next-line — verified
  this actually already works, contrary to an earlier assumption). May
  need upgrading for generics in return types, annotations on the method,
  or multi-line parameter lists with complex generic bounds.
- ~~**The HTML test page hardcodes exactly two argument input boxes**~~ —
  Fixed in Phase 2. The React frontend dynamically generates input fields
  from the parsed method signature via `/api/parse-signature`.
- **5000-step / 15-second hard caps** (`JdiStepEngine.MAX_STEPS` /
  `SESSION_TIMEOUT_MS`) guard against accidentally pasted infinite loops.
  Bump these constants if you need to trace something longer-running.
- **One request at a time, conceptually.** Each request gets its own fresh
  temp dir and child JVM (safe for concurrency), but nothing pools or
  rate-limits child JVM processes — fine for local dev, worth revisiting
  before any real deployment.

---

## Suggested next debugging step if something goes wrong

1. **`jdk.jdi` module not added** → see the warning at the top of this file.
2. **`javac` missing** → JRE not JDK on `JAVA_HOME`.
3. **Compile error from generated source** → the API response includes
   `generatedSource` on compile failure; the test page's code panel
   displays it directly so you can see exactly what was sent to `javac`
   instead of guessing.
4. **Method not found** → check the method name field matches exactly
   (case-sensitive), and that the signature has a normal modifier+type
   pattern (e.g. `public int[] foo(...)`) — see `SignatureParser`'s
   limitations above.
5. **A variable shows as `<ClassName#id>`** → that's an object type not yet
   covered (custom class, or a `java.util` type not yet tested) — not
   necessarily a bug, see Known Limitations.

---

## Roadmap (matches what's been discussed and agreed)

- **Phase 1** — Backend pipeline: compile → debug → trace via JDI, REST
  API, plain HTML test page. ✅ Complete.
- **Phase 2** — React frontend: CodeMirror 6 editor, animated array-box
  visualization, step controls with play/pause/speed, variable diff
  highlighting, dynamic arg inputs from parsed signature. ✅ Complete.
- **Phase 2.5** — Rule-based AI Trace Narrator: right-sidebar mode switch
  with Debugger / Teacher / Coach narration synced to the current step.
- **Phase 3** — LinkedList + Tree support: real pointer-chasing through
  `.next` / `.left` / `.right` fields via JDI (not toString()), plus
  recursive layout computation for tree rendering.
- **Phase 4** — Graph + HashMap-as-a-visual-structure (distinct from the
  toString()-based rendering already in place — this would be an actual
  bucket/node diagram), call-stack depth visualization for recursion.

---

## For continuity across chat sessions / new AI assistants

See **`AGENTS.md`** in this same directory — it's written specifically for
an AI coding assistant (Claude, Claude Code, or otherwise) picking up this
project fresh, including the working agreement on how fixes get delivered
and verified for this project.
