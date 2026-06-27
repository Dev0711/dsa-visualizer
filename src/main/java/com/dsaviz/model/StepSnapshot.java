package com.dsaviz.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One execution step: the line about to run, and the live local variable
 * values at that point in the frame, captured via JDI.
 *
 * variables is a LinkedHashMap (not HashMap) so insertion order is preserved
 * and the frontend renders variables in a stable, declaration-ish order
 * instead of jumping around between steps.
 */
public class StepSnapshot {

    private int stepIndex;
    private int lineNumber;
    private String methodName;
    private Map<String, Object> variables = new LinkedHashMap<>();

    public StepSnapshot() {
    }

    public StepSnapshot(int stepIndex, int lineNumber, String methodName) {
        this.stepIndex = stepIndex;
        this.lineNumber = lineNumber;
        this.methodName = methodName;
    }

    public int getStepIndex() {
        return stepIndex;
    }

    public void setStepIndex(int stepIndex) {
        this.stepIndex = stepIndex;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(int lineNumber) {
        this.lineNumber = lineNumber;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public Map<String, Object> getVariables() {
        return variables;
    }

    public void setVariables(Map<String, Object> variables) {
        this.variables = variables;
    }
}
