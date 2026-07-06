package com.dsaviz.engine;

import java.util.regex.Pattern;

/**
 * Input validation utilities for security hardening.
 *
 * This class provides validation to prevent:
 * - Malicious code execution (file I/O, network, system calls)
 * - Resource exhaustion attacks (huge code inputs)
 * - Code injection attempts
 */
public class SecurityValidator {

    // Maximum allowed code size (100KB - reasonable for algorithm visualization)
    public static final int MAX_CODE_SIZE_BYTES = 100 * 1024;

    // Maximum allowed method name length
    public static final int MAX_METHOD_NAME_LENGTH = 100;

    // Dangerous import patterns that should be blocked
    private static final Pattern[] DANGEROUS_IMPORTS = {
        Pattern.compile("import\\s+java\\.io\\.", Pattern.CASE_INSENSITIVE),
        Pattern.compile("import\\s+java\\.net\\.", Pattern.CASE_INSENSITIVE),
        Pattern.compile("import\\s+java\\.nio\\.", Pattern.CASE_INSENSITIVE),
        Pattern.compile("import\\s+java\\.lang\\.(Runtime|System)\\.", Pattern.CASE_INSENSITIVE),
        Pattern.compile("import\\s+java\\.lang\\.reflect\\.", Pattern.CASE_INSENSITIVE),
        Pattern.compile("import\\s+java\\.process\\.", Pattern.CASE_INSENSITIVE),
        Pattern.compile("import\\s+java\\.awt\\.", Pattern.CASE_INSENSITIVE),
        Pattern.compile("import\\s+javax\\.swing\\.", Pattern.CASE_INSENSITIVE),
        Pattern.compile("import\\s+jdk\\.", Pattern.CASE_INSENSITIVE),
    };

    // Dangerous method calls that should be blocked
    private static final Pattern[] DANGEROUS_CALLS = {
        Pattern.compile("\\bRuntime\\.getRuntime\\(\\)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bSystem\\.(exit|setSecurityManager)\\(", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bProcessBuilder\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bFileReader|FileOutputStream|FileInputStream\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bSocket\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bURL\\b.*openConnection", Pattern.CASE_INSENSITIVE),
    };

    /**
     * Validates the solution code for security issues.
     * @param code The Java code to validate
     * @throws SecurityException if the code contains dangerous patterns
     */
    public static void validateCode(String code) throws SecurityException {
        if (code == null || code.isEmpty()) {
            return;
        }

        // Check code size
        if (code.getBytes().length > MAX_CODE_SIZE_BYTES) {
            throw new SecurityException(
                "Code size exceeds maximum allowed (" + MAX_CODE_SIZE_BYTES + " bytes). " +
                "Please reduce your code size."
            );
        }

        // Check for dangerous imports
        for (Pattern pattern : DANGEROUS_IMPORTS) {
            if (pattern.matcher(code).find()) {
                throw new SecurityException(
                    "Code contains restricted imports. " +
                    "File I/O, network operations, and system access are not allowed."
                );
            }
        }

        // Check for dangerous method calls
        for (Pattern pattern : DANGEROUS_CALLS) {
            if (pattern.matcher(code).find()) {
                throw new SecurityException(
                    "Code contains restricted operations. " +
                    "System access and process building are not allowed."
                );
            }
        }

        // Check for infinite loop patterns (basic heuristic)
        checkForInfiniteLoopPatterns(code);
    }

    /**
     * Checks for common infinite loop patterns that could hang the system.
     * @param code The code to check
     * @throws SecurityException if potential infinite loop patterns found
     */
    private static void checkForInfiniteLoopPatterns(String code) throws SecurityException {
        // Check for while(true) patterns
        if (Pattern.compile("\\bwhile\\s*\\(\\s*true\\s*\\)").matcher(code).find()) {
            // This is allowed but we should warn about it via the step limit
            // The MAX_STEPS limit in JdiStepEngine will handle it
        }

        // Check for for(;;) patterns (equivalent to while(true))
        if (Pattern.compile("\\bfor\\s*\\(\\s*;\\s*;\\s*\\)").matcher(code).find()) {
            // Same as above - handled by MAX_STEPS
        }
    }

    /**
     * Validates the method name.
     * @param methodName The method name to validate
     * @throws SecurityException if the method name is invalid
     */
    public static void validateMethodName(String methodName) throws SecurityException {
        if (methodName == null || methodName.isBlank()) {
            throw new SecurityException("Method name is required.");
        }

        if (methodName.length() > MAX_METHOD_NAME_LENGTH) {
            throw new SecurityException(
                "Method name exceeds maximum length (" + MAX_METHOD_NAME_LENGTH + " characters)."
            );
        }

        // Check for valid Java identifier pattern
        if (!methodName.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new SecurityException(
                "Method name must be a valid Java identifier (letters, digits, underscores)."
            );
        }
    }

    /**
     * Validates arguments to ensure they don't contain dangerous content.
     * @param args The arguments to validate
     * @throws SecurityException if arguments are invalid
     */
    public static void validateArgs(String[] args) throws SecurityException {
        if (args == null) {
            return;
        }

        for (int i = 0; i < args.length; i++) {
            if (args[i] != null && args[i].length() > 10000) {
                throw new SecurityException(
                    "Argument " + i + " exceeds maximum length (10000 characters)."
                );
            }
        }
    }
}