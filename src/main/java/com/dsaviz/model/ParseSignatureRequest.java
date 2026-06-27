package com.dsaviz.model;

/**
 * Request payload for the /api/parse-signature endpoint.
 * The frontend sends the pasted code + method name, and we return the
 * parsed parameter types and names so the UI can dynamically generate
 * the correct number and type of argument input fields.
 */
public class ParseSignatureRequest {

    private String solutionCode;
    private String methodName;

    public ParseSignatureRequest() {
    }

    public String getSolutionCode() {
        return solutionCode;
    }

    public void setSolutionCode(String solutionCode) {
        this.solutionCode = solutionCode;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }
}
