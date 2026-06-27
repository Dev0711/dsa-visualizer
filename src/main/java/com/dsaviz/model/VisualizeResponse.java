package com.dsaviz.model;

import java.util.List;

public class VisualizeResponse {

    private boolean success;
    private String errorMessage;
    private String errorPhase;          // "compile" | "launch" | "trace" | null
    private List<StepSnapshot> steps;
    private Object returnValue;
    private List<String> sourceLines;   // original code split by line, for frontend highlighting
    private String generatedSource;     // full wrapped source, included on compile failure for debugging

    public static VisualizeResponse failure(String phase, String message) {
        VisualizeResponse r = new VisualizeResponse();
        r.success = false;
        r.errorPhase = phase;
        r.errorMessage = message;
        return r;
    }

    public static VisualizeResponse failure(String phase, String message, String generatedSource) {
        VisualizeResponse r = failure(phase, message);
        r.generatedSource = generatedSource;
        return r;
    }

    public static VisualizeResponse success(List<StepSnapshot> steps, Object returnValue, List<String> sourceLines) {
        VisualizeResponse r = new VisualizeResponse();
        r.success = true;
        r.steps = steps;
        r.returnValue = returnValue;
        r.sourceLines = sourceLines;
        return r;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getErrorPhase() {
        return errorPhase;
    }

    public void setErrorPhase(String errorPhase) {
        this.errorPhase = errorPhase;
    }

    public List<StepSnapshot> getSteps() {
        return steps;
    }

    public void setSteps(List<StepSnapshot> steps) {
        this.steps = steps;
    }

    public Object getReturnValue() {
        return returnValue;
    }

    public void setReturnValue(Object returnValue) {
        this.returnValue = returnValue;
    }

    public List<String> getSourceLines() {
        return sourceLines;
    }

    public void setSourceLines(List<String> sourceLines) {
        this.sourceLines = sourceLines;
    }

    public String getGeneratedSource() {
        return generatedSource;
    }

    public void setGeneratedSource(String generatedSource) {
        this.generatedSource = generatedSource;
    }
}
