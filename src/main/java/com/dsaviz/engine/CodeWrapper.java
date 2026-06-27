package com.dsaviz.engine;

import com.dsaviz.model.VisualizeRequest;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 1 wrapper generator.
 *
 * Takes the user's pasted "Solution" class plus a method name and raw string
 * args, and produces a complete, compilable Java source file containing:
 *   - the user's Solution class, EXACTLY as pasted (we don't touch it,
 *     so line numbers in the original code match line numbers JDI reports)
 *   - a Main class with a main() method that:
 *       1. parses the args strings into real Java values
 *       2. constructs a Solution
 *       3. calls the target method
 *       4. prints the result (so we have a sanity-checked return value
 *          even outside of the JDI trace)
 *
 * IMPORTANT DESIGN CHOICE: we paste the user's class UNMODIFIED, and append
 * our generated Main class as a SEPARATE top-level class in the SAME FILE
 * (Java allows multiple top-level classes per file as long as only one is
 * public, and our Main is the one we make public — we strip "public" off
 * the user's class if present, since both can't be public in the same file).
 *
 * Pitfall we are explicitly avoiding: if we generated brand-new line numbers
 * for the user's code (e.g. by re-indenting or wrapping it inside another
 * class body), JDI's reported line numbers would no longer line up with
 * what the user sees in the editor. Keeping their code byte-for-byte (just
 * with "public" stripped from the class declaration) keeps line numbers
 * trustworthy.
 */
public class CodeWrapper {

    private static final Pattern PUBLIC_CLASS_DECL =
            Pattern.compile("public\\s+class\\s+Solution");

    public static class WrapResult {
        public final String fullSource;   // complete .java file content
        public final String fileName;     // "Main.java" - must match the public class name
        public final int userCodeLineOffset; // 0, since user code starts at line 1 (see below)

        public WrapResult(String fullSource, String fileName, int userCodeLineOffset) {
            this.fullSource = fullSource;
            this.fileName = fileName;
            this.userCodeLineOffset = userCodeLineOffset;
        }
    }

    public WrapResult wrap(VisualizeRequest request) {
        String userCode = normalizeUserCode(request.getSolutionCode());
        String methodName = request.getMethodName();
        List<String> rawArgs = request.getArgs();

        ParsedSignature signature = SignatureParser.extractMethodSignature(userCode, methodName);

        StringBuilder argDecls = new StringBuilder();
        StringBuilder callArgs = new StringBuilder();

        for (int i = 0; i < signature.paramTypes.size(); i++) {
            String type = signature.paramTypes.get(i);
            String rawValue = rawArgs.get(i);
            String varName = "arg" + i;

            argDecls.append("        ")
                    .append(ArgLiteralBuilder.buildDeclaration(type, varName, rawValue))
                    .append("\n");

            if (i > 0) callArgs.append(", ");
            callArgs.append(varName);
        }

        String returnType = signature.returnType;
        boolean isVoid = "void".equals(returnType);

        String callAndPrint;
        if (isVoid) {
            callAndPrint =
                    "        solution." + methodName + "(" + callArgs + ");\n" +
                    "        System.out.println(\"__RESULT__:void\");\n";
        } else {
            callAndPrint =
                    "        " + returnType + " result = solution." + methodName + "(" + callArgs + ");\n" +
                    "        System.out.println(\"__RESULT__:\" + " +
                    ResultPrinter.printExpression("result", returnType) + ");\n";
        }

        // userCode keeps its own line numbers starting at line 1 of the FULL FILE,
        // because we put it FIRST, before the Main class. That is the key trick
        // that keeps JDI line numbers == editor line numbers.
        String fullSource =
                userCode + "\n\n" +
                "class Main {\n" +
                "    public static void main(String[] args) throws Exception {\n" +
                "        Solution solution = new Solution();\n" +
                argDecls +
                callAndPrint +
                "    }\n" +
                "}\n";

        return new WrapResult(fullSource, "GeneratedSource.java", 0);
    }

    /**
     * Strips "public" from "public class Solution" (if present). A Java file
     * can only have one public top-level class, and our generated Main class
     * is also kept non-public, so neither class is public — meaning the file
     * is free to be named anything (we use "GeneratedSource.java").
     */
    private String normalizeUserCode(String code) {
        Matcher m = PUBLIC_CLASS_DECL.matcher(code);
        if (m.find()) {
            return m.replaceFirst("class Solution");
        }
        return code;
    }
}
