import { motion } from 'framer-motion';
import './ReturnValue.css';

/**
 * Displays the method's return value after a successful trace.
 *
 * @param {*}      value   - the return value from the trace
 * @param {string} warning - optional truncation warning
 */
export default function ReturnValue({ value, warning }) {
  if (value === undefined || value === null) return null;

  const formatted = typeof value === 'object'
    ? JSON.stringify(value)
    : String(value);

  return (
    <motion.div
      className="return-value glass-panel"
      id="return-value"
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.3, delay: 0.1 }}
    >
      <span className="return-value__label">Return value</span>
      <span className="return-value__value">{formatted}</span>

      {warning && (
        <div className="return-value__warning">
          ⚠ {warning}
        </div>
      )}
    </motion.div>
  );
}
