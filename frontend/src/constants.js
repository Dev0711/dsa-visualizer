/**
 * Default code sample pre-filled in the editor.
 * Two Sum is the canonical LeetCode #1 — familiar to everyone.
 */
export const DEFAULT_CODE = `class Solution {
    public int[] twoSum(int[] nums, int target) {
        java.util.Map<Integer, Integer> seen = new java.util.HashMap<>();
        for (int i = 0; i < nums.length; i++) {
            int complement = target - nums[i];
            if (seen.containsKey(complement)) {
                return new int[]{seen.get(complement), i};
            }
            seen.put(nums[i], i);
        }
        return new int[]{};
    }
}`;

export const DEFAULT_METHOD = 'twoSum';

export const DEFAULT_ARGS = ['2,7,11,15', '9'];

/**
 * Placeholder hints for common parameter types.
 * Used by InputPanel to show meaningful placeholder text based on
 * the parsed parameter type.
 *
 * EXTENSIBILITY: add new types here as they're supported in ArgLiteralBuilder.
 */
export const TYPE_PLACEHOLDERS = {
  'int':       'e.g. 5',
  'long':      'e.g. 1000000007',
  'double':    'e.g. 3.14',
  'boolean':   'true or false',
  'char':      'e.g. a',
  'String':    'e.g. hello',
  'int[]':     'e.g. 2,7,11,15',
  'long[]':    'e.g. 1,2,3',
  'double[]':  'e.g. 1.5,2.5,3.5',
  'String[]':  'e.g. foo,bar,baz',
};

/**
 * Returns a human-friendly placeholder for a given type.
 */
export function getPlaceholder(type) {
  return TYPE_PLACEHOLDERS[type] || `value for ${type}`;
}
