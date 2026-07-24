import { useState } from "react";
import { ToolPreview } from "../api";
import { CheckIcon, CodeIcon } from "../icons";

type Lang = "curl" | "javascript" | "python";

/** Generates a curl / fetch / requests snippet from a resolved (never-executed) request preview. */
export function CodeSnippetPanel({ preview, onClose }: { preview: ToolPreview; onClose: () => void }) {
  const [lang, setLang] = useState<Lang>("curl");
  const [copied, setCopied] = useState(false);
  const snippet = buildSnippet(lang, preview);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(snippet);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // clipboard API unavailable — nothing sensible to fall back to
    }
  };

  return (
    <div className="tool-panel rb-code-panel">
      <div className="tool-panel-header">
        <CodeIcon size={15} />
        <span className="tool-panel-name mono">Code snippet</span>
        <button type="button" className="btn btn-ghost" style={{ marginLeft: "auto" }} onClick={onClose}>
          Close
        </button>
      </div>
      <div className="rb-tabs rb-tabs-compact">
        {(["curl", "javascript", "python"] as Lang[]).map((l) => (
          <button
            key={l}
            type="button"
            className={`rb-tab ${lang === l ? "is-active" : ""}`}
            onClick={() => setLang(l)}
          >
            {l === "curl" ? "cURL" : l === "javascript" ? "JavaScript" : "Python"}
          </button>
        ))}
      </div>
      <pre className="tool-result-json rb-code-block">{snippet}</pre>
      <div className="form-actions">
        <button type="button" className="btn btn-ghost" onClick={copy}>
          {copied ? <CheckIcon size={13} /> : null}
          {copied ? "Copied" : "Copy"}
        </button>
      </div>
    </div>
  );
}

function buildSnippet(lang: Lang, preview: ToolPreview): string {
  const headers = preview.headers ?? {};
  switch (lang) {
    case "curl":
      return buildCurl(preview, headers);
    case "javascript":
      return buildFetch(preview, headers);
    case "python":
      return buildPython(preview, headers);
  }
}

function buildCurl(preview: ToolPreview, headers: Record<string, string>): string {
  const parts = [`curl -X ${preview.method} '${preview.url}'`];
  for (const [name, value] of Object.entries(headers)) {
    parts.push(`  -H '${name}: ${value}'`);
  }
  if (preview.body) {
    parts.push(`  -d '${preview.body.replace(/'/g, "'\\''")}'`);
  }
  return parts.join(" \\\n");
}

function buildFetch(preview: ToolPreview, headers: Record<string, string>): string {
  const headerLines = Object.entries(headers)
    .map(([name, value]) => `    "${name}": "${value}",`)
    .join("\n");
  const options = [`  method: "${preview.method}",`];
  if (headerLines) options.push(`  headers: {\n${headerLines}\n  },`);
  if (preview.body) options.push(`  body: ${JSON.stringify(preview.body)},`);
  return `fetch("${preview.url}", {\n${options.join("\n")}\n})\n  .then((res) => res.json())\n  .then(console.log);`;
}

function buildPython(preview: ToolPreview, headers: Record<string, string>): string {
  const lines = ["import requests", ""];
  lines.push(`headers = ${Object.keys(headers).length > 0 ? pyDict(headers) : "{}"}`);
  if (preview.body) {
    lines.push(`data = ${JSON.stringify(preview.body)}`);
    lines.push(
      `response = requests.request("${preview.method}", "${preview.url}", headers=headers, data=data)`,
    );
  } else {
    lines.push(`response = requests.request("${preview.method}", "${preview.url}", headers=headers)`);
  }
  lines.push("print(response.status_code, response.text)");
  return lines.join("\n");
}

function pyDict(obj: Record<string, string>): string {
  const entries = Object.entries(obj).map(([k, v]) => `    "${k}": "${v}",`);
  return `{\n${entries.join("\n")}\n}`;
}
