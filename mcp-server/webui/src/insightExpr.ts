import type { InsightDataset } from "./api";

/**
 * The small expression language insight components use in their props, ported from the report
 * automation engine's summary expressions:
 *
 *   count(orders)                     dataset row count
 *   orders                            dataset row count
 *   sum(orders.total)                 aggregate over a column (sum, avg, min, max)
 *   orders.total                      that column from the first row
 *   "Total: " + count(orders)         concatenation
 *   if count(open) > 0 then "yes" else "no"    conditional, nestable, with and/or/parens
 *
 * Comparison is numeric first, then case-insensitive text — the same rule the query engine uses.
 */

export type Datasets = Record<string, InsightDataset>;

type Value = string | number | boolean | null;

/** Splits on a separator that sits outside quotes, parentheses, and brackets. */
function splitTop(text: string, separator: string, wordBoundary: boolean): string[] {
  const parts: string[] = [];
  let depth = 0;
  let quote: string | null = null;
  let start = 0;
  for (let i = 0; i <= text.length - separator.length; i++) {
    const c = text[i];
    if (quote) {
      if (c === quote) quote = null;
      continue;
    }
    if (c === '"' || c === "'") {
      quote = c;
      continue;
    }
    if (c === "(" || c === "[") depth++;
    if (c === ")" || c === "]") depth--;
    if (depth !== 0) continue;
    const candidate = text.slice(i, i + separator.length);
    if (candidate.toLowerCase() !== separator.toLowerCase()) continue;
    if (wordBoundary) {
      const before = i === 0 ? " " : text[i - 1];
      const after = text[i + separator.length] ?? " ";
      if (!/\s/.test(before) || !/\s/.test(after)) continue;
    }
    parts.push(text.slice(start, i));
    start = i + separator.length;
    i = start - 1;
  }
  if (parts.length === 0) return [text];
  parts.push(text.slice(start));
  return parts;
}

/**
 * Splits a conditional's tail into its then-branch and else-branch. A nested `if` claims the next
 * `else`, so `if a then if b then x else y else z` reads y as b's alternative and z as a's.
 */
function splitAtMatchingElse(text: string): [string, string | null] {
  let depth = 0;
  let quote: string | null = null;
  let nestedIf = 0;
  const isWord = (index: number, word: string) => {
    if (text.slice(index, index + word.length).toLowerCase() !== word) return false;
    const before = index === 0 ? " " : text[index - 1];
    const after = text[index + word.length] ?? " ";
    return /\s/.test(before) && /\s/.test(after);
  };
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quote) {
      if (c === quote) quote = null;
      continue;
    }
    if (c === '"' || c === "'") {
      quote = c;
      continue;
    }
    if (c === "(" || c === "[") depth++;
    if (c === ")" || c === "]") depth--;
    if (depth !== 0) continue;
    if (isWord(i, "if")) {
      nestedIf++;
      i += 1;
      continue;
    }
    if (isWord(i, "else")) {
      if (nestedIf > 0) {
        nestedIf--;
        i += 3;
        continue;
      }
      return [text.slice(0, i), text.slice(i + 4)];
    }
  }
  return [text, null];
}

function unwrapParens(text: string): string {
  let result = text.trim();
  while (result.startsWith("(") && result.endsWith(")")) {
    let depth = 0;
    let wraps = true;
    for (let i = 0; i < result.length; i++) {
      if (result[i] === "(") depth++;
      if (result[i] === ")") {
        depth--;
        if (depth === 0 && i < result.length - 1) wraps = false;
      }
    }
    if (!wraps) break;
    result = result.slice(1, -1).trim();
  }
  return result;
}

function asNumber(value: unknown): number | null {
  if (typeof value === "number") return Number.isFinite(value) ? value : null;
  if (typeof value === "boolean") return null;
  if (typeof value === "string" && value.trim() !== "") {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }
  return null;
}

function asText(value: unknown): string {
  if (value === null || value === undefined) return "";
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

function formatNumber(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(1);
}

/** Numeric when both sides are numeric, then case-insensitive text — as in the query engine. */
function compare(left: Value, right: Value): number {
  const leftNumber = asNumber(left);
  const rightNumber = asNumber(right);
  if (leftNumber !== null && rightNumber !== null) return leftNumber - rightNumber;
  return asText(left).toLowerCase().localeCompare(asText(right).toLowerCase());
}

function aggregate(operation: string, values: number[]): number | null {
  if (values.length === 0) return null;
  if (operation === "sum") return values.reduce((total, item) => total + item, 0);
  if (operation === "avg") return values.reduce((total, item) => total + item, 0) / values.length;
  if (operation === "min") return Math.min(...values);
  return Math.max(...values);
}

/** A dataset reference: count(x), sum(x.field), x.field, or x. Returns undefined when not one. */
function datasetValue(expression: string, datasets: Datasets): Value | undefined {
  const trimmed = expression.trim();
  const counted = /^count\(\s*([^)]+?)\s*\)$/i.exec(trimmed);
  if (counted) return datasets[counted[1].trim()]?.rows.length ?? 0;

  const agg = /^(sum|avg|min|max)\(\s*([A-Za-z_][A-Za-z0-9_]*)(?:\.([^)]+))?\s*\)$/i.exec(trimmed);
  if (agg) {
    const dataset = datasets[agg[2]];
    if (!dataset) return null;
    const column = agg[3] ?? "value";
    const values = dataset.rows
      .map((row) => asNumber(row[column]))
      .filter((value): value is number => value !== null);
    return aggregate(agg[1].toLowerCase(), values);
  }

  const path = /^([A-Za-z_][A-Za-z0-9_]*)(?:\.(.+))?$/.exec(trimmed);
  if (path) {
    const dataset = datasets[path[1]];
    if (!dataset) return undefined;
    if (!path[2]) return dataset.rows.length;
    const cell = dataset.rows[0]?.[path[2]];
    return cell === undefined || cell === null ? null : (cell as Value);
  }
  return undefined;
}

function literal(expression: string): Value | undefined {
  const trimmed = expression.trim();
  if (
    (trimmed.startsWith('"') && trimmed.endsWith('"') && trimmed.length >= 2) ||
    (trimmed.startsWith("'") && trimmed.endsWith("'") && trimmed.length >= 2)
  ) {
    return trimmed.slice(1, -1);
  }
  if (/^-?\d+(?:\.\d+)?$/.test(trimmed)) return Number(trimmed);
  if (/^(true|false)$/i.test(trimmed)) return trimmed.toLowerCase() === "true";
  if (/^null$/i.test(trimmed)) return null;
  return undefined;
}

function condition(expression: string, datasets: Datasets): boolean {
  const normalized = unwrapParens(expression);
  const orParts = splitTop(normalized, "or", true);
  if (orParts.length > 1) return orParts.some((part) => condition(part, datasets));
  const andParts = splitTop(normalized, "and", true);
  if (andParts.length > 1) return andParts.every((part) => condition(part, datasets));
  if (/^not\s+/i.test(normalized)) return !condition(normalized.replace(/^not\s+/i, ""), datasets);

  const comparison = /^(.+?)\s*(>=|<=|!=|<>|==|=|>|<)\s*(.+)$/.exec(normalized);
  if (comparison) {
    const left = evaluate(comparison[1], datasets);
    const right = evaluate(comparison[3], datasets);
    const result = compare(left, right);
    switch (comparison[2]) {
      case "=":
      case "==":
        return result === 0;
      case "!=":
      case "<>":
        return result !== 0;
      case ">":
        return result > 0;
      case ">=":
        return result >= 0;
      case "<":
        return result < 0;
      default:
        return result <= 0;
    }
  }
  const value = evaluate(normalized, datasets);
  if (typeof value === "boolean") return value;
  const numeric = asNumber(value);
  return numeric !== null ? numeric !== 0 : asText(value).length > 0;
}

/** Evaluates one expression to a raw value. */
export function evaluate(expression: string, datasets: Datasets): Value {
  const trimmed = unwrapParens(expression ?? "");
  if (trimmed === "") return "";

  if (/^if\s+/i.test(trimmed)) {
    const body = trimmed.replace(/^if\s+/i, "");
    const thenParts = splitTop(body, "then", true);
    if (thenParts.length > 1) {
      const test = thenParts[0];
      const [thenBranch, elseBranch] = splitAtMatchingElse(thenParts.slice(1).join(" then "));
      const branch = condition(test, datasets) ? thenBranch : (elseBranch ?? "");
      return evaluate(branch, datasets);
    }
  }

  const concatParts = splitTop(trimmed, "+", false);
  if (concatParts.length > 1) {
    return concatParts
      .map((part) => {
        const value = evaluate(part, datasets);
        return typeof value === "number" ? formatNumber(value) : asText(value);
      })
      .join("");
  }

  const asLiteral = literal(trimmed);
  if (asLiteral !== undefined) return asLiteral;

  const fromDataset = datasetValue(trimmed, datasets);
  if (fromDataset !== undefined) return fromDataset;

  return trimmed;
}

/** Evaluates and formats for display; an unresolvable expression shows an em dash. */
export function evaluateText(expression: string, datasets: Datasets): string {
  const value = evaluate(expression, datasets);
  if (value === null) return "—";
  if (typeof value === "number") return formatNumber(value);
  if (typeof value === "boolean") return String(value);
  return value === "" ? "—" : value;
}

/** Parses a rows prop: {@code [["Total", count(orders)], ["Open", count(open)]]}. */
export function parseRows(raw: string | undefined): string[][] {
  if (!raw) return [];
  const inner = raw.trim().replace(/^\[/, "").replace(/]$/, "");
  return splitTop(inner, ",", false)
    .map((row) => row.trim())
    .filter((row) => row.startsWith("["))
    .map((row) =>
      splitTop(row.trim().replace(/^\[/, "").replace(/]$/, ""), ",", false).map((cell) => cell.trim()),
    );
}

/** Parses a string-array prop: {@code ["Metric", "Value"]}. */
export function parseList(raw: string | undefined): string[] {
  if (!raw) return [];
  const inner = raw.trim().replace(/^\[/, "").replace(/]$/, "");
  return splitTop(inner, ",", false)
    .map((item) => item.trim().replace(/^["']|["']$/g, ""))
    .filter((item) => item.length > 0);
}
