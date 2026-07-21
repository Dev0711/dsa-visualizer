package com.dsaviz.engine;

import com.dsaviz.model.VisualizeRequest;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

import java.util.List;

/**
 * Produces a complete, compilable Java source file from the user's Solution
 * class plus a generated {@code Main} harness and the {@code __DSAVizTrace}
 * helper class.
 *
 * <h3>Source layout</h3>
 * <pre>
 * [instrumented Solution class]   ← from CodeInstrumentor; line numbers preserved
 *
 * class __DSAVizTrace { ... }     ← runtime helper: serialises step snapshots
 *                                     to stdout as "STEP:{json}" lines
 *
 * class Main {                    ← generated harness: sets up args and calls
 *     public static void main(...)    the target method
 * }
 * </pre>
 *
 * <h3>Line-number preservation</h3>
 * The user's Solution class is placed FIRST in the file so that the line
 * numbers produced by JavaParser-based instrumentation still match the line
 * numbers the user sees in the editor.  {@link CodeInstrumentor} injects trace
 * calls as NEW statements (they have their own lines in the output), so the
 * original statement lines shift slightly — but the {@code lineNumber} values
 * embedded in each {@code __DSAVizTrace.step()} call refer to the ORIGINAL
 * source lines, not the post-instrumentation lines.  This is correct: we pass
 * the original line number as a literal integer argument, not via reflection.
 *
 * <h3>Any class name — not just "Solution"</h3>
 * The class name is detected dynamically from the submitted source via
 * JavaParser.  For multi-class files (e.g. a {@code ListNode} helper class
 * plus the main algorithm class) the "primary" class is the one that
 * declares the target method; everything else is treated as a helper.
 *
 * <h3>Multiple top-level classes</h3>
 * Java allows multiple non-public top-level classes in one {@code .java} file.
 * {@link #normalizeUserCode} strips {@code public} from ALL top-level class
 * declarations so the generated file can be named freely ({@code GeneratedSource.java}).
 */
public class CodeWrapper {

    private final CodeInstrumentor instrumentor = new CodeInstrumentor();

    public static class WrapResult {
        public final String fullSource;
        public final String fileName;

        public WrapResult(String fullSource, String fileName) {
            this.fullSource = fullSource;
            this.fileName   = fileName;
        }
    }

    public WrapResult wrap(VisualizeRequest request) {
        String userCode   = request.getSolutionCode();
        String methodName = request.getMethodName();
        List<String> rawArgs = request.getArgs();

        // ── Step 1: Detect the primary class name ─────────────────────────────
        // The "primary" class is the one that declares the target method.
        // For single-class submissions this is just that one class.
        // For multi-class submissions (e.g. ListNode + Solution) it is the
        // class whose method the user asked to trace.
        String primaryClassName = detectPrimaryClassName(userCode, methodName);

        // ── Step 2: Normalise — strip 'public' from all top-level classes ──────
        // A .java file can only have one public top-level type.  Since our
        // generated file also contains __DSAVizTrace and Main (both non-public),
        // we strip 'public' from every user class to avoid the restriction.
        String normalizedCode = normalizeUserCode(userCode);

        // ── Step 3: Instrument the Solution source ────────────────────────────
        String instrumentedSolution = instrumentor.instrument(normalizedCode);

        // ── Step 4: Parse signature to build call-site code ──────────────────
        ParsedSignature signature = SignatureParser.extractMethodSignature(userCode, methodName);

        StringBuilder argDecls = new StringBuilder();
        StringBuilder callArgs = new StringBuilder();

        for (int i = 0; i < signature.paramTypes.size(); i++) {
            String type     = signature.paramTypes.get(i);
            String rawValue = rawArgs.get(i);
            String varName  = "arg" + i;

            argDecls.append("        ")
                    .append(ArgLiteralBuilder.buildDeclaration(type, varName, rawValue))
                    .append("\n");

            if (i > 0) callArgs.append(", ");
            callArgs.append(varName);
        }

        String returnType = signature.returnType;
        boolean isVoid    = "void".equals(returnType);

        String callAndPrint;
        if (isVoid) {
            callAndPrint =
                    "        solution." + methodName + "(" + callArgs + ");\n" +
                    "        System.out.println(\"__RESULT__:void\");\n";
        } else {
            callAndPrint =
                    "        " + returnType + " result = solution." + methodName
                            + "(" + callArgs + ");\n" +
                    "        System.out.println(\"__RESULT__:\" + "
                            + ResultPrinter.printExpression("result", returnType) + ");\n";
        }

        // ── Step 5: Assemble the full source ──────────────────────────────────
        String fullSource =
                instrumentedSolution + "\n\n" +
                traceHelperClass()   + "\n\n" +
                "class Main {\n" +
                "    public static void main(String[] args) throws Exception {\n" +
                "        " + primaryClassName + " solution = new " + primaryClassName + "();\n" +
                argDecls +
                callAndPrint +
                "    }\n" +
                "}\n";

        return new WrapResult(fullSource, "GeneratedSource.java");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Detects the name of the class that declares {@code methodName}.
     * Falls back to the first declared class if no match is found (e.g. the
     * method name hasn't been typed yet or there is only one class).
     */
    private String detectPrimaryClassName(String code, String methodName) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(code);
            return cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                    .filter(cls -> cls.getMethods().stream()
                            .anyMatch(m -> m.getNameAsString().equals(methodName)))
                    .map(ClassOrInterfaceDeclaration::getNameAsString)
                    .findFirst()
                    // Fallback: use the first declared class
                    .orElseGet(() -> cu.getTypes().isEmpty() ? "Solution"
                            : cu.getTypes().get(0).getNameAsString());
        } catch (Exception e) {
            return "Solution"; // safe fallback if parsing fails
        }
    }

    /**
     * Strips {@code public} from ALL top-level class declarations using
     * JavaParser, so the generated file (which contains multiple top-level
     * classes) is valid regardless of what class name(s) the user chose.
     */
    private String normalizeUserCode(String code) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(code);
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
                if (!cls.isNestedType()) {
                    cls.removeModifier(Modifier.Keyword.PUBLIC);
                }
            });
            return cu.toString();
        } catch (Exception e) {
            // If parsing fails, fall back to a simple regex strip for "public class"
            return code.replaceAll("public\\s+class\\s+", "class ");
        }
    }

    /**
     * Returns the source code for the {@code __DSAVizTrace} class that is
     * injected into every generated source file.
     *
     * <p>Responsibilities:
     * <ul>
     *   <li>Maintain a monotonically increasing step counter.</li>
     *   <li>Serialize a step snapshot to a JSON line and print it to stdout
     *       prefixed with {@code STEP:}.</li>
     *   <li>Convert any Java value (primitives via autoboxing, arrays, collections,
     *       strings, etc.) to a JSON-compatible representation.</li>
     * </ul>
     *
     * <p>{@link InstrumentedRunner} reads these {@code STEP:} lines and parses
     * them back into {@link com.dsaviz.model.StepSnapshot} objects.
     */
    private String traceHelperClass() {
        return
            "class __DSAVizTrace {\n" +
            "    private static int __step = 0;\n" +
            "\n" +
            "    /** Called by every injected trace point in the Solution class. */\n" +
            "    static void step(int line, String method, Object... pairs) {\n" +
            "        StringBuilder sb = new StringBuilder();\n" +
            "        sb.append(\"{\\\"lineNumber\\\":\").append(line);\n" +
            "        sb.append(\",\\\"methodName\\\":\\\"\").append(method).append(\"\\\"\");\n" +
            "        sb.append(\",\\\"variables\\\":{\");\n" +
            "        boolean first = true;\n" +
            "        for (int i = 0; i + 1 < pairs.length; i += 2) {\n" +
            "            if (!first) sb.append(',');\n" +
            "            String name = String.valueOf(pairs[i]);\n" +
            "            Object val  = pairs[i + 1];\n" +
            "            sb.append('\\\"').append(name).append('\\\"').append(':');\n" +
            "            sb.append(toJson(val));\n" +
            "            first = false;\n" +
            "        }\n" +
            "        sb.append(\"}}\");\n" +
            "        System.out.println(\"STEP:\" + sb);\n" +
            "    }\n" +
            "\n" +
            "    /** Converts a Java value to a JSON fragment. */\n" +
            "    static String toJson(Object val) {\n" +
            "        if (val == null) return \"null\";\n" +
            "        // ── primitive arrays ──────────────────────────────────────────\n" +
            "        if (val instanceof int[]    a) return intArrJson(a);\n" +
            "        if (val instanceof long[]   a) { StringBuilder s=new StringBuilder(\"[\"); for(int i=0;i<a.length;i++){if(i>0)s.append(',');s.append(a[i]);}return s.append(\"]\").toString(); }\n" +
            "        if (val instanceof double[] a) { StringBuilder s=new StringBuilder(\"[\"); for(int i=0;i<a.length;i++){if(i>0)s.append(',');s.append(a[i]);}return s.append(\"]\").toString(); }\n" +
            "        if (val instanceof boolean[]a) { StringBuilder s=new StringBuilder(\"[\"); for(int i=0;i<a.length;i++){if(i>0)s.append(',');s.append(a[i]);}return s.append(\"]\").toString(); }\n" +
            "        if (val instanceof char[]   a) { StringBuilder s=new StringBuilder(\"[\"); for(int i=0;i<a.length;i++){if(i>0)s.append(',');s.append('\\\"');s.append(esc(String.valueOf(a[i])));s.append('\\\"');}return s.append(\"]\").toString(); }\n" +
            "        // ── object arrays ─────────────────────────────────────────────\n" +
            "        if (val.getClass().isArray()) {\n" +
            "            Object[] arr = (Object[]) val;\n" +
            "            StringBuilder s = new StringBuilder(\"[\");\n" +
            "            for (int i = 0; i < arr.length; i++) { if(i>0)s.append(','); s.append(toJson(arr[i])); }\n" +
            "            return s.append(\"]\").toString();\n" +
            "        }\n" +
            "        // ── numbers and booleans ──────────────────────────────────────\n" +
            "        if (val instanceof Number || val instanceof Boolean) return val.toString();\n" +
            "        // ── characters ────────────────────────────────────────────────\n" +
            "        if (val instanceof Character) return \"\\\"\" + esc(val.toString()) + \"\\\"\";\n" +
            "        // ── strings and everything else (StringBuilder, List, etc.) ───\n" +
            "        return \"\\\"\" + esc(val.toString()) + \"\\\"\";\n" +
            "    }\n" +
            "\n" +
            "    private static String intArrJson(int[] a) {\n" +
            "        StringBuilder s = new StringBuilder(\"[\");\n" +
            "        for (int i = 0; i < a.length; i++) { if(i>0)s.append(','); s.append(a[i]); }\n" +
            "        return s.append(\"]\").toString();\n" +
            "    }\n" +
            "\n" +
            "    /** Escapes special characters for embedding in a JSON string. */\n" +
            "    private static String esc(String s) {\n" +
            "        return s.replace(\"\\\\\", \"\\\\\\\\\").replace(\"\\\"\", \"\\\\\\\"\").replace(\"\\n\", \"\\\\n\").replace(\"\\r\", \"\\\\r\").replace(\"\\t\", \"\\\\t\");\n" +
            "    }\n" +
            "}\n";
    }
}
