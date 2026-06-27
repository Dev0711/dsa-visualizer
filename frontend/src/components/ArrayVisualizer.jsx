import { useMemo } from 'react';
import { motion } from 'framer-motion';
import './ArrayVisualizer.css';

/**
 * Animated array visualization.
 *
 * Renders array-type variables as a row of boxes with values inside.
 * Values that changed since the last step get a highlight animation.
 *
 * EXTENSIBILITY: This is the first of several planned data structure
 * visualizers. Future components (StackVisualizer, QueueVisualizer,
 * TreeVisualizer, GraphVisualizer) will follow the same pattern:
 *   - Accept current + previous step variables
 *   - Extract the relevant variables by type
 *   - Render an animated visualization
 *   - Highlight changes between steps
 *
 * @param {object|null} currentStep   - current StepSnapshot
 * @param {object|null} previousStep  - previous StepSnapshot (for diff)
 */
export default function ArrayVisualizer({ currentStep, previousStep }) {
  const arrayVars = useMemo(() => {
    if (!currentStep?.variables) return [];
    return Object.entries(currentStep.variables)
      .filter(([, value]) => Array.isArray(value))
      .map(([name, value]) => ({ name, value }));
  }, [currentStep]);

  const prevVars = previousStep?.variables || {};

  if (arrayVars.length === 0) return null;

  return (
    <div className="array-visualizer" id="array-visualizer">
      <div className="array-visualizer__header">Data Structures</div>

      {arrayVars.map(({ name, value }) => {
        const prevArray = prevVars[name];
        const prevIsArray = Array.isArray(prevArray);

        return (
          <div key={name} className="array-visualizer__array">
            <div className="array-visualizer__label">
              <span className="array-visualizer__name">{name}</span>
              <span className="array-visualizer__meta">[{value.length}]</span>
            </div>
            <div className="array-visualizer__boxes">
              {value.map((item, idx) => {
                const prevItem = prevIsArray ? prevArray[idx] : undefined;
                const didChange = prevIsArray && prevItem !== undefined && prevItem !== item;
                const isNew = !prevIsArray || idx >= prevArray.length;

                return (
                  <motion.div
                    key={idx}
                    className={`array-visualizer__box ${
                      didChange ? 'array-visualizer__box--changed' : ''
                    } ${isNew ? 'array-visualizer__box--new' : ''}`}
                    initial={isNew ? { scale: 0.8, opacity: 0 } : false}
                    animate={{ scale: 1, opacity: 1 }}
                    transition={{ type: 'spring', stiffness: 500, damping: 25 }}
                    layout
                  >
                    <span className="array-visualizer__index">{idx}</span>
                    <span className="array-visualizer__value">
                      {item === null ? 'null' : String(item)}
                    </span>
                    
                    {/* Index pointer indicators */}
                    {(() => {
                      const indexPointers = Object.entries(currentStep?.variables || {})
                        .filter(([varName, varVal]) => {
                          const nameLower = varName.toLowerCase();
                          const isIndexName = ['i', 'j', 'k', 'left', 'right', 'low', 'high', 'mid', 'idx', 'index', 'ptr', 'pointer', 'p'].includes(nameLower);
                          return isIndexName && typeof varVal === 'number' && varVal === idx;
                        })
                        .map(([varName]) => varName);

                      if (indexPointers.length === 0) return null;

                      return (
                        <div className="array-visualizer__pointers">
                          {indexPointers.map(ptr => (
                            <span key={ptr} className="array-visualizer__pointer-badge">
                              ↑ {ptr}
                            </span>
                          ))}
                        </div>
                      );
                    })()}
                  </motion.div>
                );
              })}
            </div>
          </div>
        );
      })}
    </div>
  );
}
