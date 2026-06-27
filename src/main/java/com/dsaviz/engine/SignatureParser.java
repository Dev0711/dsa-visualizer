package com.dsaviz.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts a method's return type + parameter types/names from raw source
 * text via regex.
 *
 * Phase 1 deliberately does NOT use the full javac AST (javax.lang.model) for
 * this — that's overkill for "public int[] twoSum(int[] nums, int target)".
 * A real AST-based parser is the right call once we support arbitrary
 * generics / nested types in Phase 3; for now this covers:
 *   int, long, double, boolean, String, char
 *   and single-dimension arrays of those: int[], String[], etc.
 */
public class SignatureParser {

    // Matches: [modifiers] returnType methodName ( paramList ) {
    // Captures: group(1)=returnType  group(2)=methodName  group(3)=paramList
    private static final Pattern METHOD_PATTERN = Pattern.compile(
            "(?:public|private|protected)?\\s*(?:static\\s+)?" +
            "([A-Za-z_][A-Za-z0-9_<>\\[\\],\\s]*?)\\s+" +   // return type (non-greedy)
            "(\\w+)\\s*" +                                    // method name
            "\\(([^)]*)\\)\\s*\\{"                            // ( params ) {
    );

    public static ParsedSignature extractMethodSignature(String sourceCode, String methodName) {
        Matcher m = METHOD_PATTERN.matcher(sourceCode);

        while (m.find()) {
            String foundName = m.group(2);
            if (!foundName.equals(methodName)) {
                continue;
            }

            String returnType = stripLeadingModifiers(m.group(1).trim());
            String paramList = m.group(3).trim();

            ParsedSignature sig = new ParsedSignature();
            sig.methodName = methodName;
            sig.returnType = returnType;
            sig.paramTypes = new ArrayList<>();
            sig.paramNames = new ArrayList<>();

            if (!paramList.isEmpty()) {
                for (String rawParam : splitTopLevelCommas(paramList)) {
                    String param = rawParam.trim();
                    int lastSpace = param.lastIndexOf(' ');
                    if (lastSpace < 0) {
                        throw new IllegalArgumentException(
                                "Could not parse parameter: '" + param + "' in method " + methodName);
                    }
                    String type = param.substring(0, lastSpace).trim();
                    String name = param.substring(lastSpace + 1).trim();
                    sig.paramTypes.add(type);
                    sig.paramNames.add(name);
                }
            }

            return sig;
        }

        throw new IllegalArgumentException(
                "Method '" + methodName + "' not found in pasted Solution class. " +
                "Check spelling and make sure the method body uses braces on a normal signature line.");
    }

    /**
     * Splits "int[] nums, int target" into ["int[] nums", " int target"]
     * without breaking on commas that might appear inside generic type
     * params like Map<String, Integer> key (not needed for Phase 1's
     * supported types, but harmless to guard against for safety).
     */
    private static List<String> splitTopLevelCommas(String paramList) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < paramList.length(); i++) {
            char c = paramList.charAt(i);
            if (c == '<' || c == '[') depth++;
            else if (c == '>' || c == ']') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(paramList.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(paramList.substring(start));
        return parts;
    }

    /**
     * Defensive cleanup for a real bug: the optional modifier group in
     * METHOD_PATTERN can match zero characters (it's optional) while the
     * non-greedy return-type group ends up swallowing "public", "static",
     * etc. anyway, since whitespace is part of its allowed character class.
     * Concretely, "public boolean containsDuplicate(...)" was producing a
     * captured return type of "public boolean" instead of "boolean" —
     * which then generates invalid Java like "public boolean result = ...;"
     * as a local variable declaration. Stripping modifier keywords from the
     * front of whatever the regex captured fixes this regardless of which
     * group happened to consume them.
     */
    private static String stripLeadingModifiers(String rawReturnType) {
        String result = rawReturnType.trim();
        Pattern modifierPrefix = Pattern.compile(
                "^(public|private|protected|static|final)\\s+");
        Matcher modMatcher;
        while ((modMatcher = modifierPrefix.matcher(result)).find()) {
            result = result.substring(modMatcher.end()).trim();
        }
        return result;
    }
}
