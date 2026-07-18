/**
 * API client for the DSA Visualizer backend.
 *
 * All backend communication goes through this module — components never
 * call fetch() directly. This makes it easy to:
 *   - Add auth headers later
 *   - Swap the base URL for deployment
 *   - Add request/response interceptors
 *   - Mock the API for testing
 *
 * URL resolution strategy:
 *   - Dev:  VITE_API_BASE_URL is not set → falls back to '/api'
 *           Vite's dev server proxy forwards /api → http://localhost:8080
 *   - Prod: VITE_API_BASE_URL=https://your-backend.onrender.com (set in
 *           Vercel dashboard as an env var) → calls backend directly
 *
 * To set for production:
 *   In Vercel dashboard → Project → Settings → Environment Variables:
 *     VITE_API_BASE_URL = https://your-dsa-visualizer-backend.onrender.com
 */

const API_BASE = import.meta.env.VITE_API_BASE_URL
  ? `${import.meta.env.VITE_API_BASE_URL}/api`
  : '/api';


/**
 * Generic fetch wrapper with consistent error handling.
 * @param {string} endpoint - e.g. '/visualize'
 * @param {object} body - request payload (will be JSON-serialized)
 * @returns {Promise<object>} parsed JSON response
 * @throws {Error} on network or HTTP errors
 */
async function apiPost(endpoint, body) {
  const url = `${API_BASE}${endpoint}`;

  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });

  if (!response.ok) {
    throw new Error(`HTTP ${response.status}: ${response.statusText}`);
  }

  return response.json();
}

/**
 * Full compile + trace pipeline.
 *
 * @param {string} solutionCode - the pasted Solution class
 * @param {string} methodName   - method to trace (e.g. "twoSum")
 * @param {string[]} args       - ordered arg values as strings
 * @returns {Promise<object>}   - VisualizeResponse from the backend
 */
export async function visualize(solutionCode, methodName, args) {
  return apiPost('/visualize', { solutionCode, methodName, args });
}

/**
 * Lightweight signature parser — returns param types and names so the
 * frontend can dynamically generate argument input fields.
 *
 * @param {string} solutionCode - the pasted Solution class
 * @param {string} methodName   - method to parse
 * @returns {Promise<object>}   - ParseSignatureResponse from the backend
 */
export async function parseSignature(solutionCode, methodName) {
  return apiPost('/parse-signature', { solutionCode, methodName });
}
