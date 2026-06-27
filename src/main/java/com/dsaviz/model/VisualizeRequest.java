package com.dsaviz.model;

import java.util.List;

/**
 * What the frontend sends us.
 *
 * solutionCode  - the raw pasted LeetCode-style class, e.g.:
 *                  class Solution {
 *                      public int[] twoSum(int[] nums, int target) { ... }
 *                  }
 * methodName    - which method on Solution to invoke and trace, e.g. "twoSum"
 * args          - ordered list of argument values as STRINGS exactly as the user typed them,
 *                  e.g. ["1,2,3,4", "6"]  for (int[] nums, int target)
 *                  Phase 1 supports types: int, int[], String, String[]
 */
public class VisualizeRequest {

    private String solutionCode;
    private String methodName;
    private List<String> args;

    public VisualizeRequest() {
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

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args;
    }
}
