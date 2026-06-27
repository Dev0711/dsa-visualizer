import { useState, useEffect, useCallback } from 'react';
import { useVisualizer } from './hooks/useVisualizer';
import { useStepPlayer } from './hooks/useStepPlayer';
import { DEFAULT_CODE, DEFAULT_METHOD, DEFAULT_ARGS } from './constants';
import CodeEditor from './components/CodeEditor';
import CodeViewer from './components/CodeViewer';
import InputPanel from './components/InputPanel';
import VariablesPanel from './components/VariablesPanel';
import StepControls from './components/StepControls';
import ArrayVisualizer from './components/ArrayVisualizer';
import ReturnValue from './components/ReturnValue';
import AiInsightPanel from './components/AiInsightPanel';
import './App.css';

/**
 * Root application component.
 *
 * Layout: two-panel design
 *   Left panel:  Code editor + method name + argument inputs + Run button
 *   Right panel: Code viewer (with line highlight) + variables + array viz + controls
 *
 * State flow:
 *   1. User edits code/method name → triggers debounced signature parse
 *   2. Signature parse response → dynamically updates argument input fields
 *   3. User clicks "Compile & Trace" → runs the full pipeline
 *   4. Trace data → drives the right panel (code viewer, variables, visualizations)
 *   5. Step controls → navigate through trace steps
 */
export default function App() {
  // --- Form state ---
  const [code, setCode] = useState(DEFAULT_CODE);
  const [methodName, setMethodName] = useState(DEFAULT_METHOD);
  const [argValues, setArgValues] = useState(DEFAULT_ARGS);

  // --- Visualizer state ---
  const {
    traceData,
    currentStep,
    currentStepIndex,
    totalSteps,
    isLoading,
    error,
    signature,
    runTrace,
    debouncedParseSignature,
    stepNext,
    stepPrev,
    stepTo,
    stepFirst,
    stepLast,
  } = useVisualizer();

  // --- Auto-play ---
  const { isPlaying, speed, setSpeed, togglePlay, pause } = useStepPlayer(
    totalSteps,
    stepTo,
    currentStepIndex
  );

  // --- Manual Navigation Wrappers (Pause auto-play when used) ---
  const handleStepPrev = useCallback(() => { pause(); stepPrev(); }, [pause, stepPrev]);
  const handleStepNext = useCallback(() => { pause(); stepNext(); }, [pause, stepNext]);
  const handleStepTo = useCallback((val) => { pause(); stepTo(val); }, [pause, stepTo]);
  const handleStepFirst = useCallback(() => { pause(); stepFirst(); }, [pause, stepFirst]);
  const handleStepLast = useCallback(() => { pause(); stepLast(); }, [pause, stepLast]);

  // --- Parse signature on code/method change ---
  useEffect(() => {
    debouncedParseSignature(code, methodName);
  }, [code, methodName, debouncedParseSignature]);

  // --- Sync arg count when signature changes ---
  useEffect(() => {
    if (signature?.params) {
      setArgValues((prev) => {
        const newArgs = signature.params.map((_, i) => prev[i] || '');
        return newArgs;
      });
    }
  }, [signature]);

  // --- Handlers ---
  const handleRun = useCallback(() => {
    const trimmedArgs = argValues.filter((a) => a !== undefined);
    runTrace(code, methodName.trim(), trimmedArgs);
  }, [code, methodName, argValues, runTrace]);

  const handleArgChange = useCallback((index, value) => {
    setArgValues((prev) => {
      const next = [...prev];
      next[index] = value;
      return next;
    });
  }, []);

  const handleKeyDown = useCallback((e) => {
    // Ctrl/Cmd + Enter to run
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
      e.preventDefault();
      handleRun();
    }
  }, [handleRun]);

  // Previous step for diff highlighting
  const previousStep = currentStepIndex > 0
    ? traceData?.steps?.[currentStepIndex - 1]
    : null;

  return (
    <div className="app" onKeyDown={handleKeyDown}>
      {/* Header */}
      <header className="app__header">
        <div className="app__logo">
          <span className="app__logo-icon">⚡</span>
          <h1 className="app__title">DSA Visualizer</h1>
        </div>
        <span className="app__badge">Phase 2</span>
      </header>

      {/* Main content */}
      <main className="app__main">
        {/* Left panel — Input */}
        <section className="app__panel app__panel--left" aria-label="Code input">
          <div className="app__section">
            <div className="app__section-header">
              <span className="app__section-label">Solution Code</span>
              <span className="app__section-hint">Paste your LeetCode-style class — no main() needed</span>
            </div>
            <div className="app__editor-container">
              <CodeEditor value={code} onChange={setCode} />
            </div>
          </div>

          <div className="app__section">
            <InputPanel
              methodName={methodName}
              onMethodChange={setMethodName}
              signature={signature}
              argValues={argValues}
              onArgChange={handleArgChange}
              onArgsCountChange={setArgValues}
            />
          </div>

          <button
            className="app__run-btn"
            onClick={handleRun}
            disabled={isLoading}
            id="run-button"
          >
            {isLoading ? (
              <>
                <span className="app__spinner" />
                Compiling & Tracing...
              </>
            ) : (
              <>▶ Compile & Trace</>
            )}
          </button>
          <span className="app__shortcut-hint">Ctrl + Enter</span>

          {/* Error display */}
          {error && (
            <div className="app__error" id="error-display">
              <div className="app__error-phase">{error.phase}</div>
              <div className="app__error-message">{error.message}</div>
              {error.generatedSource && (
                <details className="app__error-details">
                  <summary>Show generated source</summary>
                  <pre className="app__error-source">{error.generatedSource}</pre>
                </details>
              )}
            </div>
          )}
        </section>

        {/* Right panel — Visualization */}
        <section className="app__panel app__panel--right" aria-label="Visualization">
          <div className="app__section">
            <div className="app__section-header">
              <span className="app__section-label">Execution Trace</span>
              {currentStep && (
                <span className="app__section-hint">
                  Line {currentStep.lineNumber}
                </span>
              )}
            </div>
            <CodeViewer
              sourceLines={traceData?.sourceLines || []}
              activeLine={currentStep?.lineNumber}
              activeVariables={currentStep?.variables}
            />
          </div>

          <StepControls
            currentStepIndex={currentStepIndex}
            totalSteps={totalSteps}
            isPlaying={isPlaying}
            speed={speed}
            onStepPrev={handleStepPrev}
            onStepNext={handleStepNext}
            onStepTo={handleStepTo}
            onStepFirst={handleStepFirst}
            onStepLast={handleStepLast}
            onTogglePlay={togglePlay}
            onSpeedChange={setSpeed}
          />

          <div className="app__viz-row">
            <div className="app__viz-col">
              <VariablesPanel
                currentStep={currentStep}
                previousStep={previousStep}
              />
              <AiInsightPanel 
                currentStep={currentStep} 
                previousStep={previousStep}
                sourceLines={traceData?.sourceLines || []}
              />
            </div>
            <div className="app__viz-col">
              <ArrayVisualizer
                currentStep={currentStep}
                previousStep={previousStep}
              />
            </div>
          </div>

          <ReturnValue
            value={traceData?.returnValue}
            warning={traceData?.truncationWarning}
          />
        </section>
      </main>
    </div>
  );
}
