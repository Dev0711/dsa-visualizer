import { useMemo, useState } from 'react';
import { motion } from 'framer-motion';
import './AiInsightPanel.css';

const MODES = {
  debugger: {
    label: 'Debugger',
    subtitle: 'Short, technical, line-focused',
  },
  teacher: {
    label: 'Teacher',
    subtitle: 'Clear step-by-step explanation',
  },
  coach: {
    label: 'Coach',
    subtitle: 'Hint-based and interactive',
  },
};

/**
 * Rule-based trace narrator.
 *
 * Uses the current step, previous step, and source lines to generate a
 * deterministic explanation that can later be swapped for an AI-backed
 * narrator without changing the UI.
 */
export default function AiInsightPanel({ currentStep, previousStep, sourceLines }) {
  const [mode, setMode] = useState('debugger');

  const narration = useMemo(() => {
    if (!currentStep) {
      return {
        title: 'Trace narration',
        summary: 'Run a trace to see step-by-step explanations here.',
        details: [],
        hint: 'The narrator stays in sync with playback and manual stepping.',
        lineText: null,
      };
    }

    const currentVars = currentStep.variables || {};
    const prevVars = previousStep?.variables || {};
    const lineText = getSourceLine(sourceLines, currentStep.lineNumber);
    const trimmedLine = lineText ? lineText.trim() : '';
    const currentLine = trimmedLine || `line ${currentStep.lineNumber}`;
    const changes = getVariableChanges(currentVars, prevVars);
    const hasChanges = hasAnyChanges(changes);
    const lineKind = classifyLine(trimmedLine);

    const modeData = buildModeNarration(mode, {
      currentStep,
      currentLine,
      lineKind,
      changes,
      hasChanges,
    });

    return {
      ...modeData,
      lineText: trimmedLine || null,
      details: buildDetailPoints({ currentStep, changes, lineKind }),
    };
  }, [currentStep, previousStep, sourceLines, mode]);

  return (
    <div className="ai-insight glass-panel" id="ai-insight-panel" aria-live="polite">
      <div className="ai-insight__header">
        <div className="ai-insight__title-wrap">
          <span className="ai-insight__title">
            <span className="ai-insight__icon">✨</span> AI Trace Narrator
          </span>
          <span className="ai-insight__subtitle">
            Rule-based now, AI-ready later
          </span>
        </div>
        <span className="ai-insight__badge">Phase 4 preview</span>
      </div>

      <div className="ai-insight__mode-switch" role="tablist" aria-label="Narration mode">
        {Object.entries(MODES).map(([key, value]) => (
          <button
            key={key}
            type="button"
            className={`ai-insight__mode ${mode === key ? 'ai-insight__mode--active' : ''}`}
            onClick={() => setMode(key)}
            aria-pressed={mode === key}
            title={value.subtitle}
          >
            {value.label}
          </button>
        ))}
      </div>

      <div className="ai-insight__content">
        {currentStep ? (
          <motion.div
            key={`${currentStep.lineNumber}-${mode}`}
            initial={{ opacity: 0, y: 5 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.25 }}
            className="ai-insight__card"
          >
            <div className="ai-insight__card-header">
              <span className="ai-insight__line-label">
                Line {currentStep.lineNumber}
              </span>
              <span className="ai-insight__method">
                {currentStep.methodName}()
              </span>
            </div>

            <p className="ai-insight__summary">{narration.summary}</p>

            {narration.lineText && (
              <div className="ai-insight__line">
                <span className="ai-insight__line-label">Current code</span>
                <code className="ai-insight__line-code">{narration.lineText}</code>
              </div>
            )}

            <div className="ai-insight__details">
              {narration.details.map((detail) => (
                <div key={detail} className="ai-insight__detail">
                  {detail}
                </div>
              ))}
            </div>

            <div className="ai-insight__hint">
              <span className="ai-insight__hint-label">Next thought</span>
              <span className="ai-insight__hint-text">{narration.hint}</span>
            </div>
          </motion.div>
        ) : (
          <div className="ai-insight__empty">
            Run a trace to see step-by-step narration.
          </div>
        )}
      </div>
    </div>
  );
}

function buildModeNarration(mode, { currentStep, currentLine, lineKind, changes, hasChanges }) {
  const changeText = formatChanges(changes);

  if (mode === 'teacher') {
    return {
      summary: `The debugger is now on ${currentLine}. This step helps explain how ${lineKind.teacherDescription} while the method stays in sync with the live variables.`,
      hint: hasChanges
        ? `Watch how ${changeText} changes the state before the next line runs.`
        : 'Focus on the control flow here — the important change may be the branch, not a variable update.',
    };
  }

  if (mode === 'coach') {
    return {
      summary: `You are at ${currentLine}. Try to predict what this line will do before moving on.`,
      hint: hasChanges
        ? `Why do you think ${changeText} changed right here?`
        : 'What does this line check or prepare before the next step?',
    };
  }

  return {
    summary: `Step ${currentStep.stepIndex + 1}: executing ${currentLine}.`,
    hint: hasChanges
      ? `Changed: ${changeText}.`
      : `No visible local variable changed on this step.`,
  };
}

function buildDetailPoints({ currentStep, changes, lineKind }) {
  const details = [`Modeled as a ${lineKind.label} step.`];

  if (changes.newVars.length > 0) {
    details.push(`New locals: ${changes.newVars.join(', ')}.`);
  }

  if (changes.changedVars.length > 0) {
    details.push(`Updated locals: ${changes.changedVars.join(', ')}.`);
  }

  if (changes.removedVars.length > 0) {
    details.push(`Out of scope: ${changes.removedVars.join(', ')}.`);
  }

  if (details.length === 1) {
    details.push('No visible local-variable change detected at this step.');
  }

  if (currentStep.lineNumber > 1 && currentStep.stepIndex === 0) {
    details.push('This is the first captured step for the current method frame.');
  }

  return details;
}

function classifyLine(line) {
  if (!line) {
    return {
      label: 'control-flow',
      teacherDescription: 'control flow advances without a visible source line',
    };
  }

  if (/^return\b/.test(line)) {
    return {
      label: 'return',
      teacherDescription: 'the method is finishing and producing its result',
    };
  }

  if (/^(if|else if)\s*\(/.test(line)) {
    return {
      label: 'branch',
      teacherDescription: 'a branch condition is being evaluated',
    };
  }

  if (/^(for|while)\s*\(/.test(line)) {
    return {
      label: 'loop',
      teacherDescription: 'a loop condition is being checked before the next iteration',
    };
  }

  if (/\.(put|add|set|remove)\s*\(/.test(line)) {
    return {
      label: 'collection update',
      teacherDescription: 'a collection mutation is happening',
    };
  }

  if (/=/.test(line) && !/(==|!=|<=|>=)/.test(line)) {
    return {
      label: 'assignment',
      teacherDescription: 'a new value is being assigned to a local variable',
    };
  }

  return {
    label: 'statement',
    teacherDescription: 'a normal statement is executing',
  };
}

function getSourceLine(sourceLines, lineNumber) {
  if (!Array.isArray(sourceLines) || lineNumber <= 0) return '';
  return sourceLines[lineNumber - 1] || '';
}

function getVariableChanges(currentVars, prevVars) {
  const currentEntries = Object.entries(currentVars);
  const prevEntries = Object.entries(prevVars);

  const newVars = [];
  const changedVars = [];
  const removedVars = [];

  for (const [name, value] of currentEntries) {
    if (!(name in prevVars)) {
      newVars.push(name);
      continue;
    }

    if (!valuesEqual(value, prevVars[name])) {
      changedVars.push(name);
    }
  }

  for (const [name] of prevEntries) {
    if (!(name in currentVars)) {
      removedVars.push(name);
    }
  }

  return {
    newVars,
    changedVars,
    removedVars,
  };
}

function valuesEqual(left, right) {
  return serializeValue(left) === serializeValue(right);
}

function hasAnyChanges(changes) {
  return changes.newVars.length > 0 || changes.changedVars.length > 0 || changes.removedVars.length > 0;
}

function serializeValue(value) {
  if (value === undefined) return 'undefined';
  if (value === null) return 'null';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean' || typeof value === 'bigint') {
    return String(value);
  }

  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

function formatChanges(changes) {
  const pieces = [];

  if (changes.newVars.length > 0) {
    pieces.push(`new ${changes.newVars.join(', ')}`);
  }

  if (changes.changedVars.length > 0) {
    pieces.push(`updated ${changes.changedVars.join(', ')}`);
  }

  if (changes.removedVars.length > 0) {
    pieces.push(`removed ${changes.removedVars.join(', ')}`);
  }

  if (pieces.length === 0) {
    return 'no visible locals';
  }

  return pieces.join('; ');
}
