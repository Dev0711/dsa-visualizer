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

This is **Phase 1** of a multi-phase project. Phase 1 scope is intentionally
narrow: arrays, Strings, primitives, and standard `java.util` collections
(HashMap/HashSet/ArrayList) via `toString()`. LinkedList/Tree/Graph need
real pointer-chasing and are explicitly deferred to Phase 3/4 — see
README.md's Roadmap section.

**Read `README.md` in full before making changes** — it has the complete
architecture, the full list of bugs found and fixed (with root causes, not
just symptoms), and the current known limitations. Do not duplicate
information here that's already in the README; this file is about *how to
work on the project*, not *what the project is*.

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
machine (confirmed: JDK 17+, Maven 3.8+, Windows, IntelliJ).

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

---

## Current file map (Phase 1)

```
dsa-visualizer/
├── pom.xml
├── README.md                                    — full project context, architecture, bug history
├── AGENTS.md                                     — this file
└── src/main/
    ├── java/com/dsaviz/
    │   ├── DsaVisualizerApplication.java          — Spring Boot entry point
    │   ├── controller/
    │   │   └── VisualizeController.java           — REST endpoint /api/visualize
    │   ├── model/
    │   │   ├── VisualizeRequest.java              — {solutionCode, methodName, args}
    │   │   ├── VisualizeResponse.java             — success/failure result incl. generatedSource on compile failure
    │   │   └── StepSnapshot.java                  — one trace step: line number + variables map
    │   └── engine/
    │       ├── CodeWrapper.java                   — builds the full compilable source (user code + generated Main)
    │       ├── SignatureParser.java                — regex-extracts method signature; has stripLeadingModifiers fix
    │       ├── ParsedSignature.java                — simple data holder
    │       ├── ArgLiteralBuilder.java              — raw string args -> Java literal declarations
    │       ├── ResultPrinter.java                  — builds the println expression for the return value
    │       ├── CompilerService.java                — javax.tools.JavaCompiler wrapper, compiles with -g
    │       ├── JdiStepEngine.java                  — the core JDI engine: launch, attach, step, snapshot
    │       ├── JdiValueConverter.java              — JDI Value -> plain Java value, incl. toString() for collections
    │       └── VisualizationService.java           — orchestrates wrap -> compile -> trace, owns temp dir lifecycle
    └── resources/
        ├── application.properties
        └── static/index.html                      — Phase 1 test page, no React yet (2 hardcoded arg inputs)
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
