import { useMemo, useState } from "react";
import { FileDrop } from "../components/FileDrop";
import { ColumnMapper, type ColumnMapping, type ColumnKey } from "../components/ColumnMapper";
import { CsvPreview } from "../components/CsvPreview";
import { detectDelimiter, parseCsv, sanitizeCsvCell, type CsvDelimiter } from "../lib/csv";
import { validateMappedRow } from "../lib/csvSchema";
import { postImportByUrl, postImportCsv, type ImportReport } from "../lib/api";

const OUTPUT_FIELDS: ColumnKey[] = [
  "ext_id",
  "datetime",
  "ticker",
  "exchange",
  "board",
  "alias_source",
  "side",
  "quantity",
  "price",
  "currency",
  "fee",
  "fee_currency",
  "tax",
  "tax_currency",
  "broker",
  "note",
];

const UUID_REGEX = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

type ImportTab = "file" | "url";

interface ValidationIssue {
  line: number;
  message: string;
}

export function Import(): JSX.Element {
  const [activeTab, setActiveTab] = useState<ImportTab>("file");
  const [portfolioId, setPortfolioId] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [rawCsv, setRawCsv] = useState<string>("");
  const [delimiter, setDelimiter] = useState<CsvDelimiter>(",");
  const [headers, setHeaders] = useState<string[]>([]);
  const [rows, setRows] = useState<string[][]>([]);
  const [mapping, setMapping] = useState<ColumnMapping | null>(null);
  const [validationIssues, setValidationIssues] = useState<ValidationIssue[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [report, setReport] = useState<ImportReport | null>(null);
  const [importUrl, setImportUrl] = useState("");

  const hasCsvData = headers.length > 0 && rows.length > 0;

  const portfolioIdError = useMemo(() => {
    if (!portfolioId) {
      return "Portfolio ID is required";
    }
    return UUID_REGEX.test(portfolioId.trim()) ? null : "Expected UUID";
  }, [portfolioId]);

  const handleFileSelected = async (nextFile: File, _preview?: string[]) => {
    try {
      const text = await nextFile.text();
      const detected = detectDelimiter(text);
      const parsed = parseCsv(text, detected);
      if (parsed.headers.length === 0) {
        setError("CSV file is missing a header row");
        return;
      }
      setFile(nextFile);
      setRawCsv(text);
      setDelimiter(detected);
      setHeaders(parsed.headers);
      setRows(parsed.rows);
      setMapping(null);
      setValidationIssues([]);
      setError(null);
      setReport(null);
    } catch (cause) {
      setError((cause as Error).message || "Failed to parse CSV file");
      setFile(null);
      setHeaders([]);
      setRows([]);
      setMapping(null);
    }
  };

  const handleDelimiterChange = (nextDelimiter: CsvDelimiter) => {
    if (!rawCsv) {
      return;
    }
    try {
      const parsed = parseCsv(rawCsv, nextDelimiter);
      if (parsed.headers.length === 0) {
        setError("CSV header row is empty after re-parsing");
        return;
      }
      setDelimiter(nextDelimiter);
      setHeaders(parsed.headers);
      setRows(parsed.rows);
      setMapping(null);
      setValidationIssues([]);
      setError(null);
    } catch (cause) {
      setError((cause as Error).message || "Failed to parse CSV with selected delimiter");
    }
  };

  const handleMappingSave = (nextMapping: ColumnMapping) => {
    setMapping(nextMapping);
    setValidationIssues([]);
    setError(null);
  };

  const buildCsvPayload = (): { file: File; validationErrors: ValidationIssue[] } => {
    if (!file || !mapping) {
      return { file: file as File, validationErrors: [{ line: 0, message: "File and mapping required" }] };
    }
    const headerIndex = new Map<string, number>();
    headers.forEach((header, index) => {
      headerIndex.set(header, index);
    });

    const issues: ValidationIssue[] = [];
    const outputRows: string[][] = [];
    rows.forEach((row, rowIndex) => {
      const inputRecord: Record<string, string | undefined> = {};
      (Object.keys(mapping) as ColumnKey[]).forEach((key) => {
        const columnName = mapping[key];
        if (!columnName) {
          return;
        }
        const columnIndex = headerIndex.get(columnName);
        const value = columnIndex === undefined ? "" : row[columnIndex] ?? "";
        inputRecord[key] = value;
      });
      const validation = validateMappedRow(inputRecord);
      if (!validation.success) {
        validation.error.issues.forEach((issue) => {
          issues.push({
            line: rowIndex + 2,
            message: issue.path.length > 0 ? `${issue.path.join(".")}: ${issue.message}` : issue.message,
          });
        });
        return;
      }
      const normalized = validation.data;
      const ordered = OUTPUT_FIELDS.map((field) => sanitizeCsvCell(normalized[field] ?? ""));
      outputRows.push(ordered);
    });

    if (issues.length > 0) {
      return { file, validationErrors: issues };
    }

    const headerRow = OUTPUT_FIELDS.join(",");
    const body = outputRows.map((row) => row.map(escapeCsvValue).join(",")).join("\n");
    const csvContent = [headerRow, body].filter((segment) => segment.length > 0).join("\n");
    const blob = new Blob([csvContent], { type: "text/csv" });
    const exportName = file.name.replace(/\.csv$/i, "");
    const normalizedFile = new File([blob], `${exportName || "import"}-normalized.csv`, { type: "text/csv" });
    return { file: normalizedFile, validationErrors: [] };
  };

  const handleUpload = async () => {
    if (!portfolioId || portfolioIdError) {
      setError(portfolioIdError ?? "Portfolio ID is required");
      return;
    }
    if (!file) {
      setError("Select a CSV file first");
      return;
    }
    if (!mapping) {
      setError("Save the column mapping before uploading");
      return;
    }
    const { file: preparedFile, validationErrors } = buildCsvPayload();
    if (validationErrors.length > 0) {
      setValidationIssues(validationErrors);
      setError("Fix validation errors before uploading");
      return;
    }

    setLoading(true);
    setError(null);
    setReport(null);
    try {
      const response = await postImportCsv(portfolioId.trim(), preparedFile);
      setReport(response);
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setLoading(false);
    }
  };

  const handleImportByUrl = async () => {
    if (!portfolioId || portfolioIdError) {
      setError(portfolioIdError ?? "Portfolio ID is required");
      return;
    }
    if (!importUrl.trim()) {
      setError("Provide HTTPS URL to CSV export");
      return;
    }
    if (!importUrl.trim().toLowerCase().startsWith("https://")) {
      setError("URL must start with https://");
      return;
    }

    setLoading(true);
    setError(null);
    setReport(null);
    setValidationIssues([]);
    try {
      const response = await postImportByUrl(portfolioId.trim(), importUrl.trim());
      setReport(response);
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="page">
      <section className="card import-page">
        <h2>Import trades</h2>
        <div className="import-page__portfolio">
          <label htmlFor="import-portfolio-id">Portfolio ID</label>
          <input
            id="import-portfolio-id"
            type="text"
            value={portfolioId}
            onChange={(event) => setPortfolioId(event.target.value)}
            placeholder="00000000-0000-0000-0000-000000000000"
          />
          {portfolioIdError ? <span className="import-page__error">{portfolioIdError}</span> : null}
        </div>
        <div className="import-page__tabs">
          <button
            type="button"
            className={activeTab === "file" ? "active" : ""}
            onClick={() => {
              setActiveTab("file");
              setError(null);
            }}
          >
            File
          </button>
          <button
            type="button"
            className={activeTab === "url" ? "active" : ""}
            onClick={() => {
              setActiveTab("url");
              setError(null);
            }}
          >
            By URL
          </button>
        </div>
        {activeTab === "file" ? (
          <div className="import-page__panel">
            <FileDrop onFileSelected={handleFileSelected} maxPreviewLines={3} />
            {hasCsvData ? (
              <>
                <CsvPreview headers={headers} rows={rows} delimiter={delimiter} onChangeDelimiter={handleDelimiterChange} />
                <ColumnMapper headers={headers} onSave={handleMappingSave} />
                {validationIssues.length > 0 ? (
                  <div className="import-page__validation">
                    <h4>Validation issues</h4>
                    <ul>
                      {validationIssues.map((issue) => (
                        <li key={`${issue.line}-${issue.message}`}>
                          Line {issue.line}: {issue.message}
                        </li>
                      ))}
                    </ul>
                  </div>
                ) : null}
                <button type="button" onClick={handleUpload} disabled={loading} className="import-page__submit">
                  {loading ? "Uploading..." : "Upload CSV"}
                </button>
              </>
            ) : (
              <p className="import-page__hint">Select a CSV file to see preview and configure mapping.</p>
            )}
          </div>
        ) : (
          <div className="import-page__panel">
            <label htmlFor="import-url">CSV export URL</label>
            <input
              id="import-url"
              type="url"
              value={importUrl}
              onChange={(event) => setImportUrl(event.target.value)}
              placeholder="https://docs.google.com/spreadsheets/.../export?format=csv"
            />
            <button type="button" onClick={handleImportByUrl} disabled={loading} className="import-page__submit">
              {loading ? "Importing..." : "Import from URL"}
            </button>
          </div>
        )}
        {error ? <div className="import-page__error import-page__error--global">{error}</div> : null}
        {report ? <ImportReportView report={report} /> : null}
      </section>
    </div>
  );
}

function ImportReportView({ report }: { report: ImportReport }): JSX.Element {
  return (
    <div className="import-report">
      <h3>Import report</h3>
      <ul>
        <li>Inserted: {report.inserted}</li>
        <li>Skipped duplicates: {report.skippedDuplicates}</li>
        <li>
          Failed rows:
          {report.failed.length === 0 ? (
            <span> none</span>
          ) : (
            <ul>
              {report.failed.map((item) => (
                <li key={`${item.line}-${item.error}`}>
                  Line {item.line}: {item.error}
                </li>
              ))}
            </ul>
          )}
        </li>
      </ul>
    </div>
  );
}

function escapeCsvValue(value: string): string {
  if (value.includes("\"") || value.includes(",") || value.includes("\n") || value.includes("\r")) {
    return `"${value.replace(/"/g, '""')}"`;
  }
  return value;
}
