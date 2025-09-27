export type CsvDelimiter = "," | ";" | "\t";

export interface ParsedCsv {
  headers: string[];
  rows: string[][];
}

const DELIMITER_CANDIDATES: CsvDelimiter[] = [",", ";", "\t"];
const DANGEROUS_PREFIXES = new Set(["=", "+", "-", "@"]); // CSV injection guards

export function detectDelimiter(sample: string): CsvDelimiter {
  const lines = sample.split(/\r?\n/).slice(0, 10);
  let best: { delimiter: CsvDelimiter; score: number } = { delimiter: ",", score: -1 };
  for (const delimiter of DELIMITER_CANDIDATES) {
    let score = 0;
    for (const line of lines) {
      let insideQuotes = false;
      for (let index = 0; index < line.length; index += 1) {
        const char = line[index];
        if (char === '"') {
          const next = line[index + 1];
          if (next === '"') {
            index += 1;
            continue;
          }
          insideQuotes = !insideQuotes;
          continue;
        }
        if (!insideQuotes && char === delimiter) {
          score += 1;
        }
      }
    }
    if (score > best.score) {
      best = { delimiter, score };
    }
  }
  return best.score > 0 ? best.delimiter : ",";
}

export function parseCsv(text: string, delimiter: CsvDelimiter): ParsedCsv {
  const rows: string[][] = [];
  let currentRow: string[] = [];
  let currentField = "";
  let insideQuotes = false;
  let index = 0;
  while (index < text.length) {
    const char = text[index];
    if (insideQuotes) {
      if (char === '"') {
        const next = text[index + 1];
        if (next === '"') {
          currentField += '"';
          index += 1;
        } else {
          insideQuotes = false;
        }
      } else {
        currentField += char;
      }
    } else if (char === '"') {
      insideQuotes = true;
    } else if (char === delimiter) {
      currentRow.push(currentField);
      currentField = "";
    } else if (char === "\n" || char === "\r") {
      currentRow.push(currentField);
      rows.push(currentRow);
      currentRow = [];
      currentField = "";
      if (char === "\r" && text[index + 1] === "\n") {
        index += 1;
      }
    } else {
      currentField += char;
    }
    index += 1;
  }
  if (insideQuotes) {
    throw new Error("Unterminated quoted field in CSV");
  }
  currentRow.push(currentField);
  rows.push(currentRow);
  if (rows.length > 0) {
    const lastRow = rows[rows.length - 1];
    const trailingNewline = text.endsWith("\n") || text.endsWith("\r");
    const emptyLastRow = lastRow.every((value) => value === "");
    if (trailingNewline && emptyLastRow) {
      rows.pop();
    }
  }
  const headers = rows.shift() ?? [];
  if (headers.length > 0 && headers[0].length > 0 && headers[0].charCodeAt(0) === 0xfeff) {
    headers[0] = headers[0].slice(1);
  }
  return { headers, rows };
}

export function sanitizeCsvCell(value: string): string {
  if (value.length === 0) {
    return value;
  }
  const leadingTrimmed = value.replace(/^\uFEFF/, "");
  if (leadingTrimmed.startsWith("'")) {
    return value;
  }
  const firstSignificant = leadingTrimmed.trimStart().charAt(0);
  if (firstSignificant && DANGEROUS_PREFIXES.has(firstSignificant)) {
    return `'${value}`;
  }
  return value;
}
