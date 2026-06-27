import { useState, useCallback, useRef } from 'react';
import { visualize, parseSignature } from '../api/visualize';

/**
 * Core state machine for the visualizer lifecycle.
 *
 * States: idle → loading → success | error
 *
 * This hook manages:
 *   - The trace data (steps, sourceLines, returnValue)
 *   - The current step index + navigation (prev/next)
 *   - Loading and error states
 *   - Signature parsing for dynamic input fields
 *
 * Components consume this hook and render based on the state — they
 * never call the API directly or manage step indices themselves.
 *
 * EXTENSIBILITY NOTE: when new visualization types are added (trees,
 * graphs, etc.), the trace data structure returned by the backend will
 * grow, but this hook's interface stays the same — components just
 * read from `traceData` and render whatever's in the current step.
 */
export function useVisualizer() {
  // --- Trace state ---
  const [traceData, setTraceData] = useState(null);
  const [currentStepIndex, setCurrentStepIndex] = useState(0);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState(null);

  // --- Signature state ---
  const [signature, setSignature] = useState(null);
  const [signatureError, setSignatureError] = useState(null);
  const parseTimerRef = useRef(null);

  /**
   * Run the full compile + trace pipeline.
   */
  const runTrace = useCallback(async (solutionCode, methodName, args) => {
    setIsLoading(true);
    setError(null);
    setTraceData(null);
    setCurrentStepIndex(0);

    try {
      const data = await visualize(solutionCode, methodName, args);

      if (!data.success) {
        setError({
          phase: data.errorPhase,
          message: data.errorMessage,
          generatedSource: data.generatedSource || null,
        });
        return;
      }

      const steps = data.steps || [];
      setTraceData({
        steps: steps,
        sourceLines: data.sourceLines || [],
        returnValue: data.returnValue,
        truncationWarning: data.errorPhase === 'trace-truncated' ? data.errorMessage : null,
      });
      setCurrentStepIndex(Math.max(0, steps.length - 1));
    } catch (err) {
      setError({
        phase: 'network',
        message: `Failed to reach the backend: ${err.message}. Is the Spring Boot service running on :8080?`,
      });
    } finally {
      setIsLoading(false);
    }
  }, []);

  /**
   * Parse the method signature (debounced). Called on code/methodName
   * change to dynamically update the argument input fields.
   */
  const debouncedParseSignature = useCallback((solutionCode, methodName) => {
    if (parseTimerRef.current) {
      clearTimeout(parseTimerRef.current);
    }

    if (!solutionCode?.trim() || !methodName?.trim()) {
      setSignature(null);
      setSignatureError(null);
      return;
    }

    parseTimerRef.current = setTimeout(async () => {
      try {
        const data = await parseSignature(solutionCode, methodName);
        if (data.success) {
          setSignature(data);
          setSignatureError(null);
        } else {
          setSignature(null);
          setSignatureError(data.errorMessage);
        }
      } catch {
        // Silently ignore network errors for signature parsing —
        // it's a convenience feature, not critical path
        setSignature(null);
      }
    }, 600);
  }, []);

  // --- Step navigation ---
  const currentStep = traceData?.steps?.[currentStepIndex] || null;
  const totalSteps = traceData?.steps?.length || 0;

  const stepNext = useCallback(() => {
    setCurrentStepIndex(prev => Math.min(prev + 1, totalSteps - 1));
  }, [totalSteps]);

  const stepPrev = useCallback(() => {
    setCurrentStepIndex(prev => Math.max(prev - 1, 0));
  }, []);

  const stepTo = useCallback((index) => {
    setCurrentStepIndex(Math.max(0, Math.min(index, totalSteps - 1)));
  }, [totalSteps]);

  const stepFirst = useCallback(() => setCurrentStepIndex(0), []);
  const stepLast = useCallback(() => setCurrentStepIndex(Math.max(0, totalSteps - 1)), [totalSteps]);

  return {
    // Trace state
    traceData,
    currentStep,
    currentStepIndex,
    totalSteps,
    isLoading,
    error,

    // Signature
    signature,
    signatureError,

    // Actions
    runTrace,
    debouncedParseSignature,
    stepNext,
    stepPrev,
    stepTo,
    stepFirst,
    stepLast,
  };
}
