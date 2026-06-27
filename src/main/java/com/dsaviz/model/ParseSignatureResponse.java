package com.dsaviz.model;

import java.util.List;

/**
 * Response from /api/parse-signature.
 *
 * On success: success=true, returnType populated, params list populated.
 * On failure: success=false, errorMessage explains what went wrong
 *             (method not found, unparseable signature, etc.).
 *
 * The frontend uses the params list to dynamically generate one input
 * field per parameter, labelled with the param name and typed with a
 * placeholder hint matching the type (e.g. "e.g. 2,7,11,15" for int[]).
 */
public class ParseSignatureResponse {

    private boolean success;
    private String errorMessage;
    private String returnType;
    private String methodName;
    private List<ParamInfo> params;

    /**
     * Describes one method parameter — its declared type and name from the
     * source code. Immutable value object used only in API responses.
     */
    public static class ParamInfo {
        private String type;
        private String name;

        public ParamInfo() {
        }

        public ParamInfo(String type, String name) {
            this.type = type;
            this.name = name;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    public static ParseSignatureResponse success(String methodName, String returnType, List<ParamInfo> params) {
        ParseSignatureResponse r = new ParseSignatureResponse();
        r.success = true;
        r.methodName = methodName;
        r.returnType = returnType;
        r.params = params;
        return r;
    }

    public static ParseSignatureResponse failure(String errorMessage) {
        ParseSignatureResponse r = new ParseSignatureResponse();
        r.success = false;
        r.errorMessage = errorMessage;
        return r;
    }

    // --- Getters & Setters ---

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

    public String getReturnType() {
        return returnType;
    }

    public void setReturnType(String returnType) {
        this.returnType = returnType;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public List<ParamInfo> getParams() {
        return params;
    }

    public void setParams(List<ParamInfo> params) {
        this.params = params;
    }
}
