import { getPlaceholder } from '../constants';
import './InputPanel.css';

/**
 * Dynamic argument input panel.
 *
 * When signature is available (from /api/parse-signature), renders one
 * input per parameter with type-aware placeholders and labels.
 * Falls back to a simple freeform textarea when signature isn't available.
 *
 * @param {string}   methodName       - current method name
 * @param {function} onMethodChange   - setter for method name
 * @param {object}   signature        - parsed signature from backend (or null)
 * @param {string[]} argValues        - current arg values array
 * @param {function} onArgChange      - called with (index, newValue) on edit
 * @param {function} onArgsCountChange - called with new args array when param count changes
 */
export default function InputPanel({
  methodName,
  onMethodChange,
  signature,
  argValues,
  onArgChange,
  onArgsCountChange,
}) {
  const params = signature?.params || [];

  return (
    <div className="input-panel" id="input-panel">
      {/* Method name */}
      <div className="input-panel__field">
        <label className="input-panel__label" htmlFor="method-name">
          Method name to trace
        </label>
        <input
          id="method-name"
          className="input-panel__input"
          type="text"
          value={methodName}
          onChange={(e) => onMethodChange(e.target.value)}
          placeholder="e.g. twoSum"
          spellCheck={false}
        />
      </div>

      {/* Arguments */}
      <div className="input-panel__field">
        <label className="input-panel__label">
          Arguments
          {params.length > 0 && (
            <span className="input-panel__param-count">
              {params.length} parameter{params.length !== 1 ? 's' : ''} detected
            </span>
          )}
        </label>

        {params.length > 0 ? (
          /* Dynamic inputs from parsed signature */
          <div className="input-panel__args">
            {params.map((param, idx) => (
              <div key={`${param.name}-${idx}`} className="input-panel__arg">
                <div className="input-panel__arg-meta">
                  <span className="input-panel__arg-type">{param.type}</span>
                  <span className="input-panel__arg-name">{param.name}</span>
                </div>
                <input
                  id={`arg-${idx}`}
                  className="input-panel__input"
                  type="text"
                  value={argValues[idx] || ''}
                  onChange={(e) => onArgChange(idx, e.target.value)}
                  placeholder={getPlaceholder(param.type)}
                  spellCheck={false}
                />
              </div>
            ))}
          </div>
        ) : (
          /* Fallback: manual inputs */
          <div className="input-panel__args">
            {argValues.map((val, idx) => (
              <div key={idx} className="input-panel__arg">
                <div className="input-panel__arg-meta">
                  <span className="input-panel__arg-name">arg{idx}</span>
                </div>
                <input
                  id={`arg-${idx}`}
                  className="input-panel__input"
                  type="text"
                  value={val}
                  onChange={(e) => onArgChange(idx, e.target.value)}
                  placeholder={`Argument ${idx + 1}`}
                  spellCheck={false}
                />
              </div>
            ))}
            <button
              className="input-panel__add-arg"
              onClick={() => onArgsCountChange([...argValues, ''])}
              type="button"
            >
              + Add argument
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
