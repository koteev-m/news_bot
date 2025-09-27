import type { CsvDelimiter } from "../lib/csv";

interface CsvPreviewProps {
  headers: string[];
  rows: string[][];
  delimiter: CsvDelimiter;
  onChangeDelimiter: (delimiter: CsvDelimiter) => void;
  limit?: number;
}

const DEFAULT_LIMIT = 10;
const OPTIONS: Array<{ value: CsvDelimiter; label: string }> = [
  { value: ",", label: "Comma" },
  { value: ";", label: "Semicolon" },
  { value: "\t", label: "Tab" },
];

export function CsvPreview({ headers, rows, delimiter, onChangeDelimiter, limit = DEFAULT_LIMIT }: CsvPreviewProps): JSX.Element {
  const previewRows = rows.slice(0, limit);

  return (
    <div className="csv-preview">
      <div className="csv-preview__toolbar">
        <span>Delimiter:</span>
        <select
          value={delimiter}
          onChange={(event) => onChangeDelimiter(event.target.value as CsvDelimiter)}
          className="csv-preview__delimiter"
        >
          {OPTIONS.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
        <span className="csv-preview__meta">
          Showing {previewRows.length} of {rows.length} rows
        </span>
      </div>
      <div className="csv-preview__table-wrapper">
        <table className="csv-preview__table">
          <thead>
            <tr>
              {headers.map((header) => (
                <th key={header}>{header}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {previewRows.length === 0 ? (
              <tr>
                <td colSpan={headers.length || 1} className="csv-preview__empty">
                  No rows detected. Check delimiter or file encoding.
                </td>
              </tr>
            ) : (
              previewRows.map((row, index) => (
                <tr key={`preview-row-${index}`}>
                  {headers.map((_, columnIndex) => (
                    <td key={`cell-${index}-${columnIndex}`}>{row[columnIndex] ?? ""}</td>
                  ))}
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
