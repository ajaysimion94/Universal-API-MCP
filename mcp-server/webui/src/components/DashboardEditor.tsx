import { EditorState } from "@codemirror/state";
import { autocompletion, type CompletionContext } from "@codemirror/autocomplete";
import { lintGutter, setDiagnostics, type Diagnostic as CodeMirrorDiagnostic } from "@codemirror/lint";
import { EditorView, highlightActiveLineGutter, lineNumbers } from "@codemirror/view";
import { useEffect, useRef } from "react";
import type { QueryCompletion, QueryDiagnostic } from "../api";

interface DashboardEditorProps {
  value: string;
  onChange: (value: string) => void;
  diagnostics: QueryDiagnostic[];
  completions: QueryCompletion[];
}

/** A small CodeMirror 6 shell; language intelligence comes from debounced server analysis. */
export function DashboardEditor({ value, onChange, diagnostics, completions }: DashboardEditorProps) {
  const host = useRef<HTMLDivElement>(null);
  const view = useRef<EditorView | null>(null);
  const onChangeRef = useRef(onChange);
  const completionsRef = useRef(completions);

  useEffect(() => {
    onChangeRef.current = onChange;
  }, [onChange]);

  useEffect(() => {
    completionsRef.current = completions;
  }, [completions]);

  useEffect(() => {
    if (!host.current) return;
    const completionSource = (context: CompletionContext) => {
      const word = context.matchBefore(/[A-Za-z_$][\w$-]*/);
      if (!context.explicit && !word) return null;
      return {
        from: word?.from ?? context.pos,
        options: completionsRef.current.map((item) => ({
          label: item.label,
          type: item.kind.toLowerCase(),
          detail: item.detail,
          apply: item.insertText,
        })),
      };
    };
    const state = EditorState.create({
      doc: value,
      extensions: [
        lineNumbers(),
        highlightActiveLineGutter(),
        lintGutter(),
        autocompletion({ override: [completionSource] }),
        EditorView.lineWrapping,
        EditorView.updateListener.of((update) => {
          if (update.docChanged) onChangeRef.current(update.state.doc.toString());
        }),
        EditorView.theme({
          "&": { height: "100%", backgroundColor: "var(--bg-input)", color: "var(--text)" },
          ".cm-scroller": { fontFamily: "var(--font-mono)", fontSize: "12px", lineHeight: "1.6" },
          ".cm-gutters": { backgroundColor: "var(--bg-panel)", color: "var(--text-faint)", borderRight: "1px solid var(--border)" },
          ".cm-activeLine": { backgroundColor: "var(--accent-tint)" },
          ".cm-activeLineGutter": { backgroundColor: "var(--bg-elevated)" },
          ".cm-tooltip": { backgroundColor: "var(--bg-elevated)", border: "1px solid var(--border-strong)", color: "var(--text)" },
          ".cm-tooltip-autocomplete ul li[aria-selected]": { backgroundColor: "var(--accent-tint-strong)", color: "var(--text)" },
        }),
      ],
    });
    const editor = new EditorView({ state, parent: host.current });
    view.current = editor;
    return () => {
      editor.destroy();
      view.current = null;
    };
  }, []); // The view is deliberately stable while users type.

  useEffect(() => {
    const editor = view.current;
    if (!editor) return;
    const current = editor.state.doc.toString();
    if (current !== value) editor.dispatch({ changes: { from: 0, to: current.length, insert: value } });
  }, [value]);

  useEffect(() => {
    const editor = view.current;
    if (!editor) return;
    const length = editor.state.doc.length;
    const lint: CodeMirrorDiagnostic[] = diagnostics
      .filter((item) => item.span.startOffset >= 0 && item.span.startOffset < length && item.span.endOffset <= length)
      .map((item) => ({
        from: item.span.startOffset,
        to: Math.min(length, Math.max(item.span.startOffset + 1, item.span.endOffset)),
        severity: item.severity.toLowerCase() as CodeMirrorDiagnostic["severity"],
        message: `${item.code} · ${item.message}`,
      }));
    editor.dispatch(setDiagnostics(editor.state, lint));
  }, [diagnostics]);

  return <div ref={host} className="dashboard-code-editor" aria-label="Dashboard document editor" />;
}
