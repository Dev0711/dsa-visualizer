import './StepControls.css';

/**
 * Playback controls for stepping through the trace.
 *
 * Features: First / Prev / Play-Pause / Next / Last buttons,
 * a step slider for scrubbing, and a speed control.
 *
 * @param {number}   currentStepIndex - 0-indexed current step
 * @param {number}   totalSteps       - total number of steps
 * @param {boolean}  isPlaying        - whether auto-play is active
 * @param {number}   speed            - ms between auto-play steps
 * @param {function} onStepPrev       - go to previous step
 * @param {function} onStepNext       - go to next step
 * @param {function} onStepTo         - jump to a specific step index
 * @param {function} onStepFirst      - jump to first step
 * @param {function} onStepLast       - jump to last step
 * @param {function} onTogglePlay     - toggle auto-play
 * @param {function} onSpeedChange    - set auto-play speed
 */
export default function StepControls({
  currentStepIndex,
  totalSteps,
  isPlaying,
  speed,
  onStepPrev,
  onStepNext,
  onStepTo,
  onStepFirst,
  onStepLast,
  onTogglePlay,
  onSpeedChange,
}) {
  if (totalSteps === 0) return null;

  const progress = totalSteps > 1 ? (currentStepIndex / (totalSteps - 1)) * 100 : 0;

  return (
    <div className="step-controls glass-panel" id="step-controls">
      <div className="step-controls__buttons">
        <button
          className="step-controls__btn"
          onClick={onStepFirst}
          disabled={currentStepIndex === 0}
          title="First step"
          aria-label="First step"
        >
          ⏮
        </button>
        <button
          className="step-controls__btn"
          onClick={onStepPrev}
          disabled={currentStepIndex === 0}
          title="Previous step"
          aria-label="Previous step"
        >
          ◀
        </button>
        <button
          className="step-controls__btn step-controls__btn--play"
          onClick={onTogglePlay}
          title={isPlaying ? 'Pause' : 'Play'}
          aria-label={isPlaying ? 'Pause' : 'Play'}
        >
          {isPlaying ? '⏸' : '▶'}
        </button>
        <button
          className="step-controls__btn"
          onClick={onStepNext}
          disabled={currentStepIndex >= totalSteps - 1}
          title="Next step"
          aria-label="Next step"
        >
          ▶
        </button>
        <button
          className="step-controls__btn"
          onClick={onStepLast}
          disabled={currentStepIndex >= totalSteps - 1}
          title="Last step"
          aria-label="Last step"
        >
          ⏭
        </button>
      </div>

      {/* Step slider */}
      <div className="step-controls__slider-row">
        <input
          className="step-controls__slider"
          type="range"
          min={0}
          max={Math.max(totalSteps - 1, 0)}
          value={currentStepIndex}
          onChange={(e) => onStepTo(Number(e.target.value))}
          style={{ '--progress': `${progress}%` }}
          aria-label="Step slider"
        />
        <span className="step-controls__label">
          Step {currentStepIndex + 1} / {totalSteps}
        </span>
      </div>

      {/* Speed control */}
      <div className="step-controls__speed">
        <label className="step-controls__speed-label" htmlFor="speed-select">
          Speed
        </label>
        <select
          id="speed-select"
          className="step-controls__speed-select"
          value={speed}
          onChange={(e) => onSpeedChange(Number(e.target.value))}
        >
          <option value={1000}>0.5×</option>
          <option value={500}>1×</option>
          <option value={250}>2×</option>
          <option value={100}>5×</option>
          <option value={50}>10×</option>
        </select>
      </div>
    </div>
  );
}
