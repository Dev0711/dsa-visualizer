package com.dsaviz.engine;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.ByteValue;
import com.sun.jdi.CharValue;
import com.sun.jdi.DoubleValue;
import com.sun.jdi.FloatValue;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.LongValue;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ShortValue;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * JDI gives back com.sun.jdi.Value instances - these are remote-debugger
 * proxies representing values living in the DEBUGGEE process's memory, not
 * actual Java objects in OUR process. We have to explicitly unwrap each kind
 * into a plain value (Integer, String, List, etc.) before Jackson can
 * serialize it to JSON for the frontend.
 *
 * Phase 1 scope: primitives, String, single-dimension primitive/String
 * arrays, and now (as of this fix) readable rendering of standard
 * java.util collections (HashSet, HashMap, ArrayList, etc.) via toString().
 *
 * WHY toString() INSTEAD OF MANUALLY WALKING HashMap$Node INTERNALS:
 * Hand-walking HashSet/HashMap's internal bucket array (HashMap.table ->
 * Node.key/value/next) is real pointer-chasing work - the same complexity
 * tier as the LinkedList/Tree support already scoped for a later phase.
 * Rather than half-implement that now, we use JDI's ability to actually
 * INVOKE a method on the live object in the debuggee (ObjectReference.
 * invokeMethod) and call its own toString(). For java.util collections this
 * gives a correct, readable result ("[1, 2]", "{1=10, 2=20}") for free,
 * since that's literally what toString() already produces.
 *
 * SAFETY BOUNDARY - READ BEFORE EXTENDING:
 * We ONLY do this for types whose fully-qualified name starts with
 * "java.util." or "java.lang." (see isSafeToInvokeToString). We deliberately
 * do NOT do this for arbitrary user-defined types like a custom TreeNode or
 * ListNode class pasted by the user, because:
 *   (a) invokeMethod temporarily RESUMES the debuggee thread to actually run
 *       the method, then re-suspends it - running arbitrary user code as a
 *       side effect of "just inspecting a variable" is unsafe in general
 *       (e.g. a toString() with a bug could infinite-loop or throw)
 *   (b) custom data structures (TreeNode, ListNode, graph adjacency types)
 *       are exactly what we want to render as actual VISUAL diagrams later,
 *       not as a flattened toString() - so they're intentionally left as
 *       the "<ClassName#id>" placeholder for now, pending the pointer-
 *       chasing work already scoped for Phase 3/4.
 */
public class JdiValueConverter {

    public static Object toJavaValue(Value value) {
        return toJavaValue(value, null);
    }

    /**
     * @param thread the suspended thread we're currently stopped on, needed
     *                only if we end up invoking toString() on a collection.
     *                Pass null to skip toString() invocation entirely and
     *                always fall back to the placeholder (useful for tests
     *                or contexts with no live thread available).
     */
    public static Object toJavaValue(Value value, ThreadReference thread) {
        if (value == null) {
            return null;
        }

        if (value instanceof IntegerValue iv) return iv.value();
        if (value instanceof LongValue lv) return lv.value();
        if (value instanceof DoubleValue dv) return dv.value();
        if (value instanceof FloatValue fv) return fv.value();
        if (value instanceof BooleanValue bv) return bv.value();
        if (value instanceof ByteValue by) return by.value();
        if (value instanceof ShortValue sv) return sv.value();
        if (value instanceof CharValue cv) return cv.value();
        if (value instanceof StringReference str) return str.value();

        if (value instanceof ArrayReference arr) {
            return arrayToList(arr, thread);
        }

        if (value instanceof ObjectReference obj) {
            String className = obj.referenceType().name();

            if (isBoxedPrimitive(className)) {
                try {
                    Value primitiveVal = obj.getValue(obj.referenceType().fieldByName("value"));
                    return toJavaValue(primitiveVal, thread);
                } catch (Exception e) {
                    // fall through
                }
            }

            if (thread != null && isSafeToExtract(className)) {
                String rendered = extractCollectionString(obj, thread);
                if (rendered != null) {
                    return rendered;
                }
                // fall through to placeholder if extraction failed
            }

            // Placeholder for custom user types (e.g. ListNode/TreeNode).
            // Phase 3/4 will walk .next / .left / .right fields here instead
            // to render these as real diagrams rather than a flat string.
            return "<" + className + "#" + obj.uniqueID() + ">";
        }

        return value.toString();
    }

    private static boolean isBoxedPrimitive(String className) {
        return className.equals("java.lang.Integer")
                || className.equals("java.lang.Long")
                || className.equals("java.lang.Double")
                || className.equals("java.lang.Float")
                || className.equals("java.lang.Boolean")
                || className.equals("java.lang.Byte")
                || className.equals("java.lang.Short")
                || className.equals("java.lang.Character");
    }

    private static boolean isSafeToExtract(String className) {
        return className.equals("java.util.ArrayList")
                || className.equals("java.util.HashMap")
                || className.equals("java.util.HashSet");
    }

    private static String extractCollectionString(ObjectReference obj, ThreadReference thread) {
        String className = obj.referenceType().name();
        try {
            if (className.equals("java.util.ArrayList")) {
                return extractArrayList(obj, thread);
            } else if (className.equals("java.util.HashMap")) {
                return extractHashMap(obj, thread);
            } else if (className.equals("java.util.HashSet")) {
                // HashSet just wraps a HashMap internally
                ObjectReference map = (ObjectReference) obj.getValue(obj.referenceType().fieldByName("map"));
                if (map != null) {
                    return extractHashSet(map, thread);
                }
            }
            // For other collections not explicitly supported yet, return placeholder
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractArrayList(ObjectReference obj, ThreadReference thread) {
        IntegerValue sizeVal = (IntegerValue) obj.getValue(obj.referenceType().fieldByName("size"));
        int size = sizeVal.value();
        ArrayReference elementData = (ArrayReference) obj.getValue(obj.referenceType().fieldByName("elementData"));
        
        List<String> elements = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            Value val = elementData.getValue(i);
            elements.add(String.valueOf(toJavaValue(val, thread)));
        }
        return "[" + String.join(", ", elements) + "]";
    }

    private static String extractHashMap(ObjectReference obj, ThreadReference thread) {
        ArrayReference table = (ArrayReference) obj.getValue(obj.referenceType().fieldByName("table"));
        if (table == null) return "{}";

        List<String> entries = new ArrayList<>();
        for (Value nodeVal : table.getValues()) {
            ObjectReference node = (ObjectReference) nodeVal;
            while (node != null) {
                Value key = node.getValue(node.referenceType().fieldByName("key"));
                Value value = node.getValue(node.referenceType().fieldByName("value"));
                entries.add(toJavaValue(key, thread) + "=" + toJavaValue(value, thread));
                node = (ObjectReference) node.getValue(node.referenceType().fieldByName("next"));
            }
        }
        return "{" + String.join(", ", entries) + "}";
    }

    private static String extractHashSet(ObjectReference mapObj, ThreadReference thread) {
        ArrayReference table = (ArrayReference) mapObj.getValue(mapObj.referenceType().fieldByName("table"));
        if (table == null) return "[]";

        List<String> entries = new ArrayList<>();
        for (Value nodeVal : table.getValues()) {
            ObjectReference node = (ObjectReference) nodeVal;
            while (node != null) {
                Value key = node.getValue(node.referenceType().fieldByName("key"));
                entries.add(String.valueOf(toJavaValue(key, thread)));
                node = (ObjectReference) node.getValue(node.referenceType().fieldByName("next"));
            }
        }
        return "[" + String.join(", ", entries) + "]";
    }

    private static List<Object> arrayToList(ArrayReference arr, ThreadReference thread) {
        List<Object> result = new ArrayList<>();
        for (Value element : arr.getValues()) {
            result.add(toJavaValue(element, thread));
        }
        return result;
    }
}
