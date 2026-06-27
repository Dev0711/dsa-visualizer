import { useEffect, useRef, useCallback } from 'react';
import { EditorView, keymap, lineNumbers, highlightActiveLine } from '@codemirror/view';
import { EditorState } from '@codemirror/state';
import { java } from '@codemirror/lang-java';
import { oneDark } from '@codemirror/theme-one-dark';
import './CodeEditor.css';

/**
 * CodeMirror 6 editor for pasting Java Solution code.
 *
 * This is the INPUT editor (left panel) — not to be confused with the
 * CodeViewer component which shows the read-only traced code with line
 * highlighting on the right.
 *
 * @param {string}   value    - current editor content
 * @param {function} onChange - called with the new string on every edit
 */
export default function CodeEditor({ value, onChange }) {
  const containerRef = useRef(null);
  const viewRef = useRef(null);
  const onChangeRef = useRef(onChange);

  // Keep callback ref fresh without recreating the editor
  useEffect(() => {
    onChangeRef.current = onChange;
  }, [onChange]);

  // Create the editor once on mount
  useEffect(() => {
    if (!containerRef.current) return;

    const updateListener = EditorView.updateListener.of((update) => {
      if (update.docChanged) {
        onChangeRef.current(update.state.doc.toString());
      }
    });

    const state = EditorState.create({
      doc: value,
      extensions: [
        java(),
        oneDark,
        lineNumbers(),
        highlightActiveLine(),
        updateListener,
        EditorView.theme({
          '&': {
            fontSize: '13.5px',
            height: '100%',
          },
          '.cm-scroller': {
            fontFamily: 'var(--font-mono)',
            overflow: 'auto',
          },
          '.cm-content': {
            padding: '8px 0',
          },
          '.cm-gutters': {
            background: 'transparent',
            borderRight: '1px solid var(--border-subtle)',
          },
          '.cm-lineNumbers .cm-gutterElement': {
            color: 'var(--line-number)',
            padding: '0 12px 0 8px',
            minWidth: '3em',
          },
        }),
      ],
    });

    const view = new EditorView({
      state,
      parent: containerRef.current,
    });

    viewRef.current = view;

    return () => {
      view.destroy();
      viewRef.current = null;
    };
    // Only create editor on mount — value is initial only
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div className="code-editor" ref={containerRef} id="code-editor" />
  );
}
