import React, { useMemo } from "react";

/**
 * Minimal, safe markdown renderer for chat answers — no dependency, no
 * dangerouslySetInnerHTML; everything is parsed into React elements.
 * Supports fenced code blocks, headings, bullet and numbered lists, quotes,
 * bold, italic, inline code, and [links](https://…).
 * Anything unrecognized renders as plain text.
 */

interface Block {
  kind: "p" | "ul" | "ol" | "pre" | "quote" | "h";
  level?: number; // headings
  lines: string[];
}

export function MarkdownText({ text }: { text: string }) {
  const blocks = useMemo(() => parseBlocks(text), [text]);
  return (
    <div className="md">
      {blocks.map((b, i) => {
        switch (b.kind) {
          case "pre":
            return (
              <pre key={i} className="md-pre">
                <code>{b.lines.join("\n")}</code>
              </pre>
            );
          case "h": {
            const Tag = (`h${Math.min((b.level ?? 1) + 2, 6)}`) as "h3" | "h4" | "h5" | "h6";
            return (
              <Tag key={i} className="md-h">
                {renderInline(b.lines.join(" "))}
              </Tag>
            );
          }
          case "ul":
            return (
              <ul key={i} className="md-list">
                {b.lines.map((l, j) => (
                  <li key={j}>{renderInline(l)}</li>
                ))}
              </ul>
            );
          case "ol":
            return (
              <ol key={i} className="md-list">
                {b.lines.map((l, j) => (
                  <li key={j}>{renderInline(l)}</li>
                ))}
              </ol>
            );
          case "quote":
            return (
              <blockquote key={i} className="md-quote">
                {renderInline(b.lines.join(" "))}
              </blockquote>
            );
          default:
            return (
              <p key={i} className="md-p">
                {renderInline(b.lines.join(" "))}
              </p>
            );
        }
      })}
    </div>
  );
}

/** Splits into fenced-code segments first, then groups lines into block-level elements. */
function parseBlocks(text: string): Block[] {
  const blocks: Block[] = [];
  const segments = text.split("```");
  segments.forEach((seg, segIdx) => {
    if (segIdx % 2 === 1) {
      // Inside a fence: a first line without spaces is a language tag, not content.
      const nl = seg.indexOf("\n");
      const body = nl > 0 && !seg.slice(0, nl).includes(" ") ? seg.slice(nl + 1) : seg;
      const code = body.replace(/\n$/, "");
      if (code.trim()) blocks.push({ kind: "pre", lines: [code] });
      return;
    }
    let current: Block | null = null;
    const flush = () => {
      if (current && current.lines.length > 0) blocks.push(current);
      current = null;
    };
    for (const rawLine of seg.split("\n")) {
      const line = rawLine;
      const trimmed = line.trim();
      if (!trimmed) {
        flush();
        continue;
      }
      const heading = /^(#{1,3})\s+(.*)$/.exec(trimmed);
      const unordered = /^\s*[-*]\s+(.*)$/.exec(line);
      const ordered = /^\s*\d+\.\s+(.*)$/.exec(line);
      const quote = /^>\s?(.*)$/.exec(trimmed);
      if (heading) {
        flush();
        blocks.push({ kind: "h", level: heading[1].length, lines: [heading[2]] });
      } else if (unordered) {
        if (current?.kind !== "ul") {
          flush();
          current = { kind: "ul", lines: [] };
        }
        current.lines.push(unordered[1]);
      } else if (ordered) {
        if (current?.kind !== "ol") {
          flush();
          current = { kind: "ol", lines: [] };
        }
        current.lines.push(ordered[1]);
      } else if (quote) {
        if (current?.kind !== "quote") {
          flush();
          current = { kind: "quote", lines: [] };
        }
        current.lines.push(quote[1]);
      } else {
        if (current?.kind !== "p") {
          flush();
          current = { kind: "p", lines: [] };
        }
        current.lines.push(trimmed);
      }
    }
    flush();
  });
  return blocks;
}

const INLINE_RE =
  /(\*\*[^*]+\*\*)|(\*[^*\n]+\*)|(_[^_\n]+_)|(`[^`\n]+`)|(\[[^\]]+\]\([^)\s]+\))/g;

function renderInline(text: string): React.ReactNode[] {
  const out: React.ReactNode[] = [];
  let last = 0;
  let key = 0;
  for (const m of text.matchAll(INLINE_RE)) {
    const idx = m.index ?? 0;
    if (idx > last) out.push(text.slice(last, idx));
    const [full, bold, italic, italicU, code, link] = m;
    if (bold) {
      out.push(<strong key={key++}>{bold.slice(2, -2)}</strong>);
    } else if (italic) {
      out.push(<em key={key++}>{italic.slice(1, -1)}</em>);
    } else if (italicU) {
      out.push(<em key={key++}>{italicU.slice(1, -1)}</em>);
    } else if (code) {
      out.push(
        <code key={key++} className="md-code">
          {code.slice(1, -1)}
        </code>,
      );
    } else if (link) {
      const lm = /^\[([^\]]+)\]\(([^)\s]+)\)$/.exec(link);
      const label = lm?.[1] ?? link;
      const url = lm?.[2] ?? "";
      if (/^https?:\/\//i.test(url)) {
        out.push(
          <a key={key++} href={url} target="_blank" rel="noopener noreferrer" className="md-link">
            {label}
          </a>,
        );
      } else {
        out.push(full); // non-http(s) link target — render literally
      }
    }
    last = idx + full.length;
  }
  if (last < text.length) out.push(text.slice(last));
  return out;
}
