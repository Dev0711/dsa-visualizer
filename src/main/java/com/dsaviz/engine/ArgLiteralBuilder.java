package com.dsaviz.engine;

/**
 * Converts a raw, user-typed argument string (e.g. "1,2,3,4" or "hello")
 * into a literal Java variable declaration statement for the generated
 * Main.main(), based on the declared parameter type.
 *
 * Phase 1 supported types: int, long, double, boolean, char, String,
 * int[], long[], double[], String[].
 *
 * Input format conventions (what the user types in the form):
 *   int            -> "5"
 *   boolean        -> "true"
 *   String         -> "hello"            (no quotes needed from user)
 *   int[]          -> "1,2,3,4"          (comma separated, brackets optional)
 *   String[]       -> "foo,bar,baz"
 *
 * NOTE: this is intentionally simple string-splitting, not a JSON parser.
 * Good enough for Two Sum-style problems; revisit when TreeNode/ListNode
 * inputs are added in Phase 3/4 (those need their own builders since they
 * require constructing linked structures, not array literals).
 */
public class ArgLiteralBuilder {

    public static String buildDeclaration(String type, String varName, String rawValue) {
        String trimmed = rawValue == null ? "" : rawValue.trim();

        switch (type) {
            case "int":
                return "int " + varName + " = " + Integer.parseInt(trimmed) + ";";
            case "long":
                return "long " + varName + " = " + Long.parseLong(stripSuffix(trimmed)) + "L;";
            case "double":
                return "double " + varName + " = " + Double.parseDouble(trimmed) + ";";
            case "boolean":
                return "boolean " + varName + " = " + Boolean.parseBoolean(trimmed) + ";";
            case "char":
                return "char " + varName + " = '" + escapeChar(trimmed) + "';";
            case "String":
                return "String " + varName + " = \"" + escapeString(trimmed) + "\";";
            case "int[]":
                return "int[] " + varName + " = {" + cleanArrayLiteral(trimmed, false) + "};";
            case "long[]":
                return "long[] " + varName + " = {" + cleanArrayLiteral(trimmed, false) + "};";
            case "double[]":
                return "double[] " + varName + " = {" + cleanArrayLiteral(trimmed, false) + "};";
            case "String[]":
                return "String[] " + varName + " = {" + cleanArrayLiteral(trimmed, true) + "};";
            default:
                throw new UnsupportedOperationException(
                        "Type '" + type + "' is not supported in Phase 1. " +
                        "Supported: int, long, double, boolean, char, String, and single-dim arrays of these. " +
                        "TreeNode/ListNode/2D-array support arrives in a later phase.");
        }
    }

    private static String stripSuffix(String s) {
        return s.replaceAll("[lL]$", "");
    }

    private static String escapeChar(String s) {
        if (s.length() != 1) {
            throw new IllegalArgumentException("char value must be exactly one character, got: '" + s + "'");
        }
        return s.equals("'") ? "\\'" : s;
    }

    private static String escapeString(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * "1,2,3,4" -> "1,2,3,4"
     * "[1, 2, 3, 4]" -> "1,2,3,4"  (strips brackets/spaces if the user pasted
     *                               LeetCode-style array notation)
     * For String[] elements, each element gets wrapped in quotes.
     */
    private static String cleanArrayLiteral(String raw, boolean quoteElements) {
        String cleaned = raw.replaceAll("^\\[", "").replaceAll("\\]$", "").trim();
        if (cleaned.isEmpty()) {
            return "";
        }
        String[] parts = cleaned.split(",");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String element = parts[i].trim();
            // strip surrounding quotes if user already included them
            element = element.replaceAll("^\"|\"$", "");
            if (i > 0) sb.append(",");
            if (quoteElements) {
                sb.append("\"").append(escapeString(element)).append("\"");
            } else {
                sb.append(element);
            }
        }
        return sb.toString();
    }
}
