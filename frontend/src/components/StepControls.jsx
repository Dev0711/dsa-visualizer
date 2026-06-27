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
          className="step-controls__btn step-controls__btn--text"
          onClick={onStepFirst}
          disabled={currentStepIndex === 0}
          title="Restart from beginning"
          aria-label="Restart"
        >
          ⏮ Restart
        </button>
        <button
          className="step-controls__btn step-controls__btn--text"
          onClick={onStepPrev}
          disabled={currentStepIndex === 0}
          title="Manual Step Previous"
          aria-label="Previous step"
        >
          ◀ Prev
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
          className="step-controls__btn step-controls__btn--text"
          onClick={onStepNext}
          disabled={currentStepIndex >= totalSteps - 1}
          title="Manual Step Next"
          aria-label="Next step"
        >
          Next ▶
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
          <option value={3000}>Very Slow (3s)</option>
          <option value={2000}>Slow (2s)</option>
          <option value={1500}>Normal (1.5s)</option>
          <option value={1000}>Fast (1s)</option>
          <option value={500}>Very Fast (0.5s)</option>
        </select>
      </div>
    </div>
  );
}
