import { useState, useEffect, useCallback, useRef } from 'react';

/**
 * Auto-play hook for stepping through the trace automatically.
 *
 * Provides play/pause toggle and adjustable speed. Uses requestAnimationFrame
 * with a timestamp check instead of setInterval for smoother, more
 * battery-friendly playback.
 *
 * @param {number}   totalSteps  - total number of trace steps
 * @param {function} stepTo      - function to jump to a specific step index
 * @param {number}   currentStep - current step index
 */
export function useStepPlayer(totalSteps, stepTo, currentStep) {
  const [isPlaying, setIsPlaying] = useState(false);
  const [speed, setSpeed] = useState(500); // ms between steps
  const lastStepTimeRef = useRef(0);
  const animFrameRef = useRef(null);
  const currentStepRef = useRef(currentStep);

  // Keep the ref in sync so the animation callback reads fresh values
  useEffect(() => {
    currentStepRef.current = currentStep;
  }, [currentStep]);

  // Auto-pause when reaching the last step
  useEffect(() => {
    if (currentStep >= totalSteps - 1 && isPlaying) {
      setIsPlaying(false);
    }
  }, [currentStep, totalSteps, isPlaying]);

  // Animation loop
  useEffect(() => {
    if (!isPlaying || totalSteps === 0) return;

    const animate = (timestamp) => {
      if (timestamp - lastStepTimeRef.current >= speed) {
        lastStepTimeRef.current = timestamp;
        const nextStep = currentStepRef.current + 1;
        if (nextStep < totalSteps) {
          stepTo(nextStep);
        }
      }
      animFrameRef.current = requestAnimationFrame(animate);
    };

    lastStepTimeRef.current = performance.now();
    animFrameRef.current = requestAnimationFrame(animate);

    return () => {
      if (animFrameRef.current) {
        cancelAnimationFrame(animFrameRef.current);
      }
    };
  }, [isPlaying, speed, totalSteps, stepTo]);

  const togglePlay = useCallback(() => {
    setIsPlaying(prev => {
      // If we're at the end, restart from the beginning when playing
      if (!prev && currentStepRef.current >= totalSteps - 1) {
        stepTo(0);
      }
      return !prev;
    });
  }, [totalSteps, stepTo]);

  const pause = useCallback(() => setIsPlaying(false), []);

  return {
    isPlaying,
    speed,
    setSpeed,
    togglePlay,
    pause,
  };
}
