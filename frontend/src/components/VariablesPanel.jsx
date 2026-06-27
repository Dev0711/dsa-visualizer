import { useMemo } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import './VariablesPanel.css';

/**
 * Displays live local variable values at the current trace step.
 *
 * Features:
 *   - Variables that CHANGED since the previous step get a green pulse
 *   - New variables (first appearance) get a fade-in animation
 *   - Values are formatted based on their type (arrays, strings, etc.)
 *
 * EXTENSIBILITY: when new data structure types are added (TreeNode, etc.),
 * the rendering logic here can be extended to show inline previews or
 * links to the dedicated visualizer components. The variable data itself
 * comes straight from the backend's StepSnapshot — no frontend parsing needed.
 *
 * @param {object|null} currentStep   - current StepSnapshot
 * @param {object|null} previousStep  - previous StepSnapshot (for diff highlighting)
 */
export default function VariablesPanel({ currentStep, previousStep }) {
  const entries = useMemo(() => {
    if (!currentStep?.variables) return [];
    return Object.entries(currentStep.variables);
  }, [currentStep]);

  const prevVars = previousStep?.variables || {};

  if (!currentStep) {
    return (
      <div className="variables-panel glass-panel" id="variables-panel">
        <div className="variables-panel__header">Variables</div>
        <div className="variables-panel__empty">
          Run a trace to see variable states here.
        </div>
      </div>
    );
  }

  return (
    <div className="variables-panel glass-panel" id="variables-panel">
      <div className="variables-panel__header">
        Variables
        <span className="variables-panel__method">
          {currentStep.methodName}()
        </span>
      </div>

      {entries.length === 0 ? (
        <div className="variables-panel__empty">
          No local variables in scope at this line.
        </div>
      ) : (
        <div className="variables-panel__list">
          <AnimatePresence mode="popLayout">
            {entries.map(([name, value]) => {
              const prevValue = prevVars[name];
              const isNew = !(name in prevVars);
              const didChange = !isNew && JSON.stringify(prevValue) !== JSON.stringify(value);
              const formattedValue = formatValue(value);

              return (
                <motion.div
                  key={name}
                  className={`variables-panel__var ${didChange ? 'variables-panel__var--changed' : ''}`}
                  initial={isNew ? { opacity: 0, x: -8 } : false}
                  animate={{ opacity: 1, x: 0 }}
                  transition={{ duration: 0.2 }}
                  layout
                >
                  <span className="variables-panel__name">{name}</span>
                  <span className="variables-panel__eq">=</span>
                  <span
                    className={`variables-panel__value ${didChange ? 'variables-panel__value--changed' : ''}`}
                    title={typeof formattedValue === 'string' ? formattedValue : undefined}
                  >
                    {formattedValue}
                  </span>
                </motion.div>
              );
            })}
          </AnimatePresence>
        </div>
      )}
    </div>
  );
}

/**
 * Formats a variable value for display.
 * Arrays show as [1, 2, 3], strings get quotes, etc.
 */
function formatValue(value) {
  if (value === null || value === undefined) return 'null';
  if (typeof value === 'string') return value;
  if (Array.isArray(value)) return `[${value.join(', ')}]`;
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}
