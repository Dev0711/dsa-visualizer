package com.dsaviz.engine;

/**
 * Produces the Java source expression used to stringify the method's return
 * value in the generated main(), so println gives us something parseable
 * (e.g. arrays print as "[1, 2]" via Arrays.toString rather than a useless
 * memory-address-style toString()).
 */
public class ResultPrinter {

    public static String printExpression(String varName, String returnType) {
        switch (returnType) {
            case "int[]":
            case "long[]":
            case "double[]":
            case "boolean[]":
            case "char[]":
                return "java.util.Arrays.toString(" + varName + ")";
            case "String[]":
            case "Object[]":
                return "java.util.Arrays.toString(" + varName + ")";
            case "int[][]":
            case "String[][]":
                return "java.util.Arrays.deepToString(" + varName + ")";
            default:
                // primitives, String, boxed types, and anything with a
                // sane toString() (we'll special-case ListNode/TreeNode
                // when those param/return types are added in a later phase)
                return varName;
        }
    }
}
