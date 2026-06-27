import { useMemo } from 'react';
import { motion } from 'framer-motion';
import './CodeViewer.css';

/**
 * Read-only code viewer that highlights the currently-executing line.
 *
 * Shows the user's original source with line numbers. The active line
 * (from the current trace step) gets an animated highlight bar.
 *
 * @param {string[]} sourceLines     - the original code split by line
 * @param {number|null} activeLine   - 1-indexed line number to highlight
 */
export default function CodeViewer({ sourceLines, activeLine }) {
  const lines = useMemo(() => {
    if (!sourceLines || sourceLines.length === 0) return [];
    return sourceLines.map((content, idx) => ({
      number: idx + 1,
      content,
    }));
  }, [sourceLines]);

  if (lines.length === 0) {
    return (
      <div className="code-viewer glass-panel" id="code-viewer">
        <div className="code-viewer__empty">
          Run a trace to see your code execute step by step.
        </div>
      </div>
    );
  }

  return (
    <div className="code-viewer glass-panel" id="code-viewer">
      <pre className="code-viewer__pre">
        {lines.map((line) => {
          const isActive = line.number === activeLine;
          return (
            <div
              key={line.number}
              className={`code-viewer__line ${isActive ? 'code-viewer__line--active' : ''}`}
            >
              {isActive && (
                <motion.div
                  className="code-viewer__line-highlight"
                  layoutId="line-highlight"
                  initial={{ opacity: 0 }}
                  animate={{ opacity: 1 }}
                  transition={{ type: 'spring', stiffness: 400, damping: 30 }}
                />
              )}
              <span className="code-viewer__line-number">{String(line.number).padStart(3, ' ')}</span>
              <span className="code-viewer__line-content">{line.content || ' '}</span>
            </div>
          );
        })}
      </pre>
    </div>
  );
}
