package com.dsaviz.engine;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Transforms a Solution class source by injecting {@code __DSAVizTrace.step(...)}
 * calls before every executable statement in every method.
 *
 * <h3>What it does</h3>
 * Given user code like:
 * <pre>
 *   int level = 0;
 *   for (char ch : s.toCharArray()) {
 *       if (ch == '(') level++;
 *   }
 * </pre>
 * It produces:
 * <pre>
 *   __DSAVizTrace.step(3,"removeOuterParentheses","s",(Object)(s));
 *   int level = 0;
 *   __DSAVizTrace.step(4,"removeOuterParentheses","s",(Object)(s),"level",(Object)(level));
 *   for (char ch : s.toCharArray()) {
 *       __DSAVizTrace.step(5,"removeOuterParentheses","s",(Object)(s),"level",(Object)(level),"ch",(Object)(ch));
 *       if (ch == '(') {
 *           __DSAVizTrace.step(5,"removeOuterParentheses",...);
 *           level++;
 *       }
 *   }
 * </pre>
 *
 * <h3>Scope tracking</h3>
 * Each trace call only includes variables that are actually in scope at that point:
 * <ul>
 *   <li>Method parameters — always in scope</li>
 *   <li>Variables declared earlier in the same block — added after their declaration</li>
 *   <li>For-each loop variable (e.g. {@code char ch}) — only inside the loop body</li>
 *   <li>For-loop init variables (e.g. {@code int i}) — only inside the loop body</li>
 *   <li>Variables declared in an inner block go out of scope when that block exits</li>
 * </ul>
 *
 * <h3>No manual method naming needed</h3>
 * The entire Solution class is instrumented — all methods, including private helpers.
 * The caller only specifies the entry-point method name for the Main class invocation;
 * all other methods are traced automatically when called.
 */
public class CodeInstrumentor {

    /**
     * Parses {@code source}, walks every method in every top-level class,
     * and injects trace calls. Returns the instrumented source as a string.
     *
     * <p>Works with any class name — not just {@code Solution} — and handles
     * multi-class files (e.g. a {@code ListNode} helper class alongside the
     * main algorithm class): all classes are instrumented so helper method
     * calls are visible in the trace.
     *
     * @param source  the user's raw Java source (one or more classes)
     * @return instrumented source, ready to be embedded by {@link CodeWrapper}
     */
    public String instrument(String source) {
        CompilationUnit cu = StaticJavaParser.parse(source);

        if (cu.getTypes().isEmpty()) {
            throw new IllegalStateException(
                    "No class found in submitted code. " +
                    "Please paste a complete Java class.");
        }

        // Instrument every class in the file (handles multi-class submissions
        // such as a ListNode definition alongside the main algorithm class).
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
            for (MethodDeclaration method : cls.getMethods()) {
                method.getBody().ifPresent(body -> {
                    List<String> paramScope = method.getParameters().stream()
                            .map(p -> p.getNameAsString())
                            .collect(Collectors.toCollection(ArrayList::new));
                    instrumentBlock(body, paramScope, method.getNameAsString());
                });
            }
        });

        return cu.toString();
    }

    // -------------------------------------------------------------------------
    // Core instrumentation
    // -------------------------------------------------------------------------

    /**
     * Rewrites all statements in {@code block} in place, inserting a trace call
     * before each statement and recursing into nested blocks.
     *
     * @param block          the block to instrument
     * @param inheritedScope variables visible from the enclosing scope
     * @param methodName     name of the enclosing method (for the trace payload)
     */
    private void instrumentBlock(BlockStmt block, List<String> inheritedScope, String methodName) {
        // Take a snapshot of the current statements, clear the block,
        // then re-add them one by one interspersed with trace calls.
        List<Statement> original = new ArrayList<>(block.getStatements());
        block.getStatements().clear();

        // Mutable running scope: grows as variables are declared inside the block.
        List<String> scope = new ArrayList<>(inheritedScope);

        for (Statement stmt : original) {
            int line = stmt.getBegin().map(pos -> pos.line).orElse(0);

            // ① Inject trace call BEFORE this statement (current scope snapshot)
            block.addStatement(buildTraceCall(line, methodName, scope));

            // ② Add the (possibly rewritten) statement itself
            block.addStatement(processStatement(stmt, scope, methodName));

            // ③ If the statement declared new variables, add them to scope so
            //    subsequent statements see them.
            addDeclaredVars(stmt, scope);
        }
    }

    /**
     * Handles a single statement. For simple statements this is a no-op (the
     * statement is returned unchanged). For statements with inner blocks (if,
     * for, while, etc.) we recurse so the inner blocks are also instrumented.
     */
    private Statement processStatement(Statement stmt, List<String> scope, String methodName) {

        if (stmt instanceof IfStmt ifStmt) {
            // Ensure then-branch is a block, then instrument it
            BlockStmt thenBlock = ensureBlock(ifStmt.getThenStmt());
            ifStmt.setThenStmt(thenBlock);
            instrumentBlock(thenBlock, scope, methodName);

            // Else-branch: may be absent, another IfStmt (else-if), or a block
            ifStmt.getElseStmt().ifPresent(elseBranch -> {
                if (elseBranch instanceof IfStmt) {
                    // else-if: recurse directly (it has its own then/else handling)
                    processStatement(elseBranch, scope, methodName);
                } else {
                    BlockStmt elseBlock = ensureBlock(elseBranch);
                    ifStmt.setElseStmt(elseBlock);
                    instrumentBlock(elseBlock, scope, methodName);
                }
            });

        } else if (stmt instanceof ForEachStmt forEachStmt) {
            // The loop variable (e.g. `char ch`) is only in scope inside the body.
            List<String> innerScope = new ArrayList<>(scope);
            forEachStmt.getVariable().getVariables()
                    .forEach(vd -> innerScope.add(vd.getNameAsString()));

            BlockStmt bodyBlock = ensureBlock(forEachStmt.getBody());
            forEachStmt.setBody(bodyBlock);
            instrumentBlock(bodyBlock, innerScope, methodName);

        } else if (stmt instanceof ForStmt forStmt) {
            // Variables declared in the for-init (e.g. `int i = 0`) are scoped
            // to the loop — visible in the condition, body, and update.
            List<String> innerScope = new ArrayList<>(scope);
            forStmt.getInitialization().forEach(init -> {
                if (init instanceof VariableDeclarationExpr vde) {
                    vde.getVariables().forEach(vd -> innerScope.add(vd.getNameAsString()));
                }
            });

            BlockStmt bodyBlock = ensureBlock(forStmt.getBody());
            forStmt.setBody(bodyBlock);
            instrumentBlock(bodyBlock, innerScope, methodName);

        } else if (stmt instanceof WhileStmt whileStmt) {
            BlockStmt bodyBlock = ensureBlock(whileStmt.getBody());
            whileStmt.setBody(bodyBlock);
            instrumentBlock(bodyBlock, scope, methodName);

        } else if (stmt instanceof DoStmt doStmt) {
            BlockStmt bodyBlock = ensureBlock(doStmt.getBody());
            doStmt.setBody(bodyBlock);
            instrumentBlock(bodyBlock, scope, methodName);

        } else if (stmt instanceof TryStmt tryStmt) {
            instrumentBlock(tryStmt.getTryBlock(), scope, methodName);
            tryStmt.getFinallyBlock().ifPresent(fb -> instrumentBlock(fb, scope, methodName));
            tryStmt.getCatchClauses().forEach(cc -> {
                // Catch parameter (e.g. Exception e) is in scope in its body
                List<String> catchScope = new ArrayList<>(scope);
                catchScope.add(cc.getParameter().getNameAsString());
                instrumentBlock(cc.getBody(), catchScope, methodName);
            });

        } else if (stmt instanceof BlockStmt innerBlock) {
            // Explicit inner block — create a fresh inner scope
            instrumentBlock(innerBlock, scope, methodName);
        }

        return stmt;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a statement like:
     * {@code __DSAVizTrace.step(5,"solve","a",(Object)(a),"b",(Object)(b));}
     *
     * <p>The {@code (Object)(...)} cast ensures primitives are autoboxed so the
     * varargs parameter accepts them — otherwise the compiler would complain
     * about incompatible types with {@code Object...}.
     */
    private Statement buildTraceCall(int line, String methodName, List<String> vars) {
        StringBuilder sb = new StringBuilder("__DSAVizTrace.step(");
        sb.append(line).append(", \"").append(methodName).append("\"");
        for (String var : vars) {
            sb.append(", \"").append(var).append("\", (Object)(").append(var).append(")");
        }
        sb.append(");");
        return StaticJavaParser.parseStatement(sb.toString());
    }

    /**
     * If the statement is already a {@link BlockStmt}, returns it as-is.
     * Otherwise wraps it in a new block — needed for single-statement if/for/while
     * branches so we can instrument the block's content.
     */
    private BlockStmt ensureBlock(Statement stmt) {
        if (stmt instanceof BlockStmt bs) {
            return bs;
        }
        BlockStmt block = new BlockStmt();
        block.addStatement(stmt);
        return block;
    }

    /**
     * If {@code stmt} is a variable declaration (e.g. {@code int x = 0;}),
     * appends the declared variable names to {@code scope} so subsequent
     * statements in the same block can see them in their trace calls.
     */
    private void addDeclaredVars(Statement stmt, List<String> scope) {
        if (stmt instanceof ExpressionStmt exprStmt) {
            Expression expr = exprStmt.getExpression();
            if (expr instanceof VariableDeclarationExpr vde) {
                for (VariableDeclarator vd : vde.getVariables()) {
                    scope.add(vd.getNameAsString());
                }
            }
        }
        // Note: for-each and for-loop init variables are handled in processStatement,
        // not here, because they're only in scope within the loop — not in the
        // block that contains the loop statement.
    }
}
