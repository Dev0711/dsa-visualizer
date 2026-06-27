# AGENTS.md — Instructions for AI assistants working on this project

This file is for any AI coding assistant (Claude, Claude Code, or other)
picking up this project. If you're an AI reading this at the start of a
new session: read this whole file before touching any code.

---

## What this project is

A DSA (Data Structures & Algorithms) code visualizer for Java. The user
pastes a LeetCode-style `Solution` class (no `main()` method, as LeetCode
submissions never include one), and the tool:
1. Compiles it in memory
2. Launches it as a real child JVM
3. Attaches a real debugger via JDI (Java Debug Interface)
4. Steps through execution line by line
5. Captures local variable state at each step
6. Returns the trace as JSON for a frontend to render as
   code-highlight + animated data-structure visualization

This is a **multi-phase project**. Phase 1 (backend core) and Phase 2
(React frontend) are complete. The backend handles arrays, Strings,
primitives, and standard `java.util` collections (HashMap/HashSet/ArrayList)
via `toString()`. LinkedList/Tree/Graph need real pointer-chasing and are
deferred to Phase 3/4 — see README.md's Roadmap section.

**Read `README.md` in full before making changes** — it has the complete
architecture, the full list of bugs found and fixed (with root causes, not
just symptoms), and the current known limitations. Do not duplicate
information here that's already in the README; this file is about *how to
work on the project*, not *what the project is*.

---

## Architecture overview (two-process setup)

This project has **two separate processes** during development:

1. **Spring Boot backend** (port 8080) — Java/Maven, uses JDI to
   compile and trace user code. Start with:
   ```bash
   set JDK_JAVA_OPTIONS=--add-modules jdk.jdi
   mvn spring-boot:run
   ```

2. **Vite + React frontend** (port 5173) — the UI. Start with:
   ```bash
   cd frontend
   npm run dev
   ```

The Vite dev server proxies `/api/*` requests to `localhost:8080`, so
no CORS issues during development. In production, the frontend is built
to static files (`npm run build`) and served directly by Spring Boot
from `src/main/resources/static/`.

---

## Critical environment constraint: you likely cannot compile or run this yourself

If you're an AI assistant working in a sandboxed environment, check
whether you have:
- A full JDK (not just JRE) — `javac -version`
- The `jdk.jdi` module available — this is often NOT exposed by default
  even when a JDK is present
- Maven, and network access to Maven Central (many sandboxes restrict
  egress to npm/pip registries only and block Maven entirely)

If any of these are missing (this has been the case in the development
sandbox used so far), **you cannot compile-test or run-test this project
directly**. The actual build/run environment is the project owner's own
machine (confirmed: JDK 17+, Maven 3.8+, Node 24+, Windows, IntelliJ).

### What to do about it — do not skip this

When you cannot compile/run something yourself, **hand-simulate the logic
instead of guessing**. This project's bug history (see README "Bugs found
and fixed") happened specifically because a regex or string-building change
was reasoned about abstractly instead of traced against real input
character-by-character. The fix going forward, and the standard to hold
yourself to:

1. **Before shipping any change to code-generation logic** (anything in
   `CodeWrapper`, `SignatureParser`, `ArgLiteralBuilder`, `ResultPrinter`),
   write a small standalone Java snippet that runs the *exact* logic
   against the *exact* user-provided example, and actually execute it
   (e.g. via `java SomeTest.java` if you have any JDK at all, even without
   Spring/Maven) to see the literal generated output, not just reason
   about what you expect it to produce.
2. **For JDI-specific behavior** (anything in `JdiStepEngine`,
   `JdiValueConverter`) that you cannot execute: verify against official
   JDI documentation (search for `"com.sun.jdi.<ClassName>"` +
   `"Oracle docs"` or similar), check exact method signatures and declared
   checked exceptions, and explicitly tell the user which parts are
   "verified against the spec" versus "actually tested" — these are
   different confidence levels and the user has explicitly asked for this
   distinction to be made clear every time.
3. **Never present untested logic as working** without flagging the
   limitation. The user has been explicit: "do think and not repeat
   mistakes" — meaning trace it through fully before handing it over, and
   be upfront about what couldn't be verified.

---

## How fixes should be delivered (per explicit user preference)

The user has a Windows machine and edits files manually — there is no
shared filesystem access between an AI assistant's sandbox and the user's
actual project folder.

**Confirmed preference: deliver fixes as exact line-level patches/diffs**
the user can apply by hand — not a full zip re-download for small changes.
Concretely:
- State the exact file path
- Show the exact old code and exact new code (or a clear "add this method"
  instruction with full method text)
- Only provide a full zip when multiple files change together, or if
  explicitly asked

If you're starting a *new* chat session without access to this project's
current file state, ask the user to paste the current content of whatever
file you need to modify, rather than assuming you remember it correctly
from a prior summary — prior summaries can be stale or incomplete.

---

## Working style expectations (explicit user feedback, do not regress on these)

- **Ask clarifying questions when scope is ambiguous**, rather than
  guessing silently and shipping the wrong thing.
- **Check edge cases before declaring something done** — e.g. when the
  return-type modifier bug was found, the fix was verified against
  multiple different return types (boolean, int[], void) and multiple full
  example problems before being called complete, not just the one case
  that originally broke.
- **Don't repeat a previously-fixed mistake.** If you're about to touch
  `SignatureParser`, `CodeWrapper`, or `ArgLiteralBuilder` again, re-read
  the "Bugs found and fixed" section of README.md first — there's a real
  chance a new change could reintroduce or interact with one of those
  fixes (e.g. any future change to the return-type regex should be
  re-tested against the modifier-stripping fix, not assumed compatible).
- **Be explicit about confidence level.** Distinguish "I hand-traced this
  against your exact input and confirmed the output" from "this should
  work based on documented API behavior, but I couldn't execute it" — the
  user wants to know which one applies for every fix.
- **Follow SOLID principles and clean code patterns.** The user has
  explicitly asked for ACID-compliant, readable, design-pattern-following
  code. Every new component, hook, or module should have a single
  responsibility and be easy to extend without modifying existing code.

---

## Design patterns & conventions in this codebase

### Backend (Java / Spring Boot)
- **Service layer pattern**: `VisualizationService` orchestrates the
  pipeline; individual engines (`CodeWrapper`, `CompilerService`,
  `JdiStepEngine`) are focused, single-responsibility classes.
- **Static factory methods** on response DTOs (`VisualizeResponse.success()`,
  `VisualizeResponse.failure()`) rather than complex constructors.
- **Fail-fast validation**: input validation happens at the controller
  level before hitting the service layer.
- **Phase-specific error reporting**: errors include `errorPhase` so the
  frontend can show contextually appropriate messages.

### Frontend (React / Vite)
- **Custom hooks for state management**: `useVisualizer` and
  `useStepPlayer` encapsulate all business logic. Components are pure
  renderers.
- **Centralized API client**: all backend calls go through
  `src/api/visualize.js`. Components never call `fetch()` directly.
- **CSS custom properties (design tokens)**: all colors, spacing, fonts,
  and shadows are defined in `index.css` as CSS variables. Components
  reference tokens, never hardcode values.
- **BEM-style CSS naming**: `.component__element--modifier` pattern for
  all class names (e.g. `.code-viewer__line--active`).
- **Component structure**: each component has its own `.jsx` + `.css`
  files in `src/components/`. No style prop spaghetti.

### Adding new data structure visualizers
When you add a new visualizer (e.g. `TreeVisualizer`, `StackVisualizer`):
1. Create `src/components/YourVisualizer.jsx` + `.css`
2. Follow the same pattern as `ArrayVisualizer.jsx`:
   - Accept `currentStep` and `previousStep` props
   - Extract relevant variables from `currentStep.variables`
   - Render animated visualization
   - Highlight changes vs `previousStep`
3. Import and add to `App.jsx`'s right panel `app__viz-col` section
4. No backend changes needed — the JDI engine already captures all
   variable values; the frontend just decides how to render each type.

---

## Current file map (Phase 2)

```
dsa-visualizer/
├── pom.xml                                        — Maven build, Spring Boot 3.3.4
├── README.md                                      — full project context, architecture, bug history
├── AGENTS.md                                      — this file
├── .gitignore
├── src/main/
│   ├── java/com/dsaviz/
│   │   ├── DsaVisualizerApplication.java          — Spring Boot entry point
│   │   ├── controller/
│   │   │   └── VisualizeController.java           — REST: /api/visualize + /api/parse-signature
│   │   ├── model/
│   │   │   ├── VisualizeRequest.java              — {solutionCode, methodName, args}
│   │   │   ├── VisualizeResponse.java             — success/failure result incl. generatedSource
│   │   │   ├── StepSnapshot.java                  — one trace step: line number + variables map
│   │   │   ├── ParseSignatureRequest.java         — {solutionCode, methodName}
│   │   │   └── ParseSignatureResponse.java        — {returnType, params[{type, name}]}
│   │   └── engine/
│   │       ├── CodeWrapper.java                   — builds compilable source (user code + Main)
│   │       ├── SignatureParser.java                — regex-extracts method signature
│   │       ├── ParsedSignature.java                — simple data holder
│   │       ├── ArgLiteralBuilder.java              — raw string args → Java literal declarations
│   │       ├── ResultPrinter.java                  — builds println expression for return value
│   │       ├── CompilerService.java                — javax.tools.JavaCompiler wrapper
│   │       ├── JdiStepEngine.java                  — core JDI engine: launch, attach, step, snapshot
│   │       ├── JdiValueConverter.java              — JDI Value → plain Java value
│   │       └── VisualizationService.java           — orchestrates wrap → compile → trace
│   └── resources/
│       ├── application.properties
│       └── static/index.html                      — Phase 1 test page (kept as backup)
├── frontend/                                      — Vite + React frontend (Phase 2)
│   ├── package.json
│   ├── vite.config.js                             — dev proxy to Spring Boot :8080
│   ├── index.html
│   └── src/
│       ├── main.jsx                               — React entry point
│       ├── App.jsx                                — root component, two-panel layout
│       ├── App.css                                — layout styles
│       ├── index.css                              — design system (CSS custom properties)
│       ├── constants.js                           — defaults, type placeholders
│       ├── api/
│       │   └── visualize.js                       — centralized API client
│       ├── hooks/
│       │   ├── useVisualizer.js                   — core state machine (trace lifecycle)
│       │   └── useStepPlayer.js                   — auto-play with speed control
│       └── components/
│           ├── CodeEditor.jsx + .css              — CodeMirror 6 Java editor
│           ├── CodeViewer.jsx + .css              — read-only traced code with line highlight
│           ├── InputPanel.jsx + .css              — dynamic arg inputs from signature
│           ├── VariablesPanel.jsx + .css           — live variables with diff highlighting
│           ├── StepControls.jsx + .css             — play/prev/next/slider/speed controls
│           ├── ArrayVisualizer.jsx + .css          — animated array box visualization
│           └── ReturnValue.jsx + .css             — return value display
```

---

## If the user reports a bug

1. Ask for (if not already given): the exact pasted `Solution` code, exact
   method name field value, exact arg values, and the exact error message
   or unexpected output.
2. Reproduce the relevant logic in a standalone runnable snippet first (see
   "What to do about it" above) — don't patch blind.
3. Identify root cause, not just symptom — e.g. Bug #2 in the README
   wasn't "the regex is wrong for this one case," it was "the regex's
   optional group can match zero-width, so ANY case with a return type
   could be affected."
4. Fix, then re-verify against the *original* failing case AND at least 2-3
   other previously-working cases, to make sure the fix doesn't regress
   something else.
5. Update README.md's "Bugs found and fixed" section with the new entry,
   following the same format as existing entries (what broke, why, the
   fix, verification status).

---

## Future roadmap context

The project owner has stated the long-term vision is "very big." Known
future directions include:
- **Phase 3/4**: LinkedList, Tree, Graph visualization with real
  pointer-chasing through JDI (not toString())
- **Data structure visualizers**: Stack, Queue, HashMap-as-bucket-diagram,
  all as animated React components following the ArrayVisualizer pattern
- **AI integration**: planned but scope TBD — likely code explanation,
  complexity analysis, or optimization suggestions
- **Possible multi-language support** in the distant future

When making architectural decisions, prefer extensibility and clean
interfaces over premature optimization.
