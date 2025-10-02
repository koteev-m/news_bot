import { Fragment, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { FileDrop } from "../components/FileDrop";
import { ColumnMapper, type ColumnMapping, type ColumnKey } from "../components/ColumnMapper";
import { CsvPreview } from "../components/CsvPreview";
import { Loading } from "../components/Loading";
import { useToaster } from "../components/Toaster";
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
  const { t } = useTranslation();
  const toaster = useToaster();
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
      return t("import.error.portfolioRequired");
    }
    return UUID_REGEX.test(portfolioId.trim()) ? null : t("import.error.portfolioUuid");
  }, [portfolioId, t]);

  const showError = (message: string, options: { toast?: boolean } = {}) => {
    setError(message);
    if (options.toast !== false) {
      toaster.notifyError(message);
    }
  };

  const handleFileSelected = async (nextFile: File, _preview?: string[]) => {
    try {
      const text = await nextFile.text();
      const detected = detectDelimiter(text);
      const parsed = parseCsv(text, detected);
      if (parsed.headers.length === 0) {
        showError(t("import.error.missingHeader"));
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
      showError((cause as Error).message || t("import.error.parsing"));
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
        showError(t("import.error.missingHeader"));
        return;
      }
      setDelimiter(nextDelimiter);
      setHeaders(parsed.headers);
      setRows(parsed.rows);
      setMapping(null);
      setValidationIssues([]);
      setError(null);
    } catch (cause) {
      showError((cause as Error).message || t("import.error.delimiter"));
    }
  };

  const handleMappingSave = (nextMapping: ColumnMapping) => {
    setMapping(nextMapping);
    setValidationIssues([]);
    setError(null);
  };

  const buildCsvPayload = (): { file: File; validationErrors: ValidationIssue[] } | null => {
    if (!file || !mapping) {
      showError(t("import.error.missingFile"));
      return null;
    }
    const headerIndex = new Map<string, number>();
    headers.forEach((header, index) => {
      headerIndex.set(header, index);
    });
    const validationErrors: ValidationIssue[] = [];
    const sanitizedRows = rows.map((row, rowIndex) => {
      const target: Record<string, string> = {};
      OUTPUT_FIELDS.forEach((field) => {
        const source = mapping[field];
        if (!source) {
          return;
        }
        const index = headerIndex.get(source);
        target[field] = index !== undefined ? sanitizeCsvCell(row[index] ?? "") : "";
      });
      const validation = validateMappedRow(target);
      if (!validation.success) {
        validationErrors.push({
          line: rowIndex + 1,
          message: validation.error.issues[0]?.message ?? t("import.validation.none"),
        });
      }
      return target;
    });
    if (validationErrors.length > 0) {
      setValidationIssues(validationErrors);
      return null;
    }
    const csvContent = [
      OUTPUT_FIELDS.join(delimiter),
      ...sanitizedRows.map((row) => OUTPUT_FIELDS.map((field) => escapeCsvValue(row[field] ?? "")).join(delimiter)),
    ].join("\n");
    const blob = new Blob([csvContent], { type: "text/csv" });
    return { file: new File([blob], file.name, { type: "text/csv" }), validationErrors: [] };
  };

  const handleUpload = async () => {
    if (portfolioIdError) {
      showError(portfolioIdError);
      return;
    }
    const payload = buildCsvPayload();
    if (!payload) {
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const result = await postImportCsv(portfolioId.trim(), payload.file);
      setReport(result);
      toaster.notify(t("import.success.file"));
    } catch (cause) {
      showError((cause as Error).message || t("import.error.api"));
    } finally {
      setLoading(false);
    }
  };

  const handleImportByUrl = async () => {
    if (portfolioIdError) {
      showError(portfolioIdError);
      return;
    }
    const trimmedUrl = importUrl.trim();
    if (!trimmedUrl) {
      showError(t("import.error.api"));
      return;
    }
    if (!/^https:\/\//i.test(trimmedUrl)) {
      showError(t("import.error.invalidUrl"), { toast: false });
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const result = await postImportByUrl(portfolioId.trim(), trimmedUrl);
      setReport(result);
      toaster.notify(t("import.success.url"));
    } catch (cause) {
      showError((cause as Error).message || t("import.error.api"));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="page">
      <section className="card import-page">
        <h2>{t("import.title")}</h2>
        <div className="import-page__portfolio">
          <label htmlFor="import-portfolio-id">{t("import.portfolioId")}</label>
          <input
            id="import-portfolio-id"
            type="text"
            value={portfolioId}
            onChange={(event) => setPortfolioId(event.target.value)}
            placeholder={t("import.portfolioPlaceholder")}
            aria-describedby="portfolio-help"
            aria-invalid={Boolean(portfolioIdError)}
          />
          <p id="portfolio-help" className="import-page__hint">
            {t("import.portfolioHelp")}
          </p>
          {portfolioIdError ? (
            <span className="import-page__error" role="alert">
              {portfolioIdError}
            </span>
          ) : null}
        </div>
        <div className="import-page__tabs" role="tablist" aria-label={t("import.title")}>
          <button
            type="button"
            role="tab"
            aria-selected={activeTab === "file"}
            className={activeTab === "file" ? "active" : ""}
            onClick={() => {
              setActiveTab("file");
              setError(null);
            }}
          >
            {t("import.tabs.file")}
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={activeTab === "url"}
            className={activeTab === "url" ? "active" : ""}
            onClick={() => {
              setActiveTab("url");
              setError(null);
            }}
          >
            {t("import.tabs.url")}
          </button>
        </div>
        {activeTab === "file" ? (
          <div className="import-page__panel" role="tabpanel" aria-label={t("import.tabs.file")}>
            <FileDrop onFileSelected={handleFileSelected} maxPreviewLines={3} />
            {hasCsvData ? (
              <Fragment>
                <CsvPreview headers={headers} rows={rows} delimiter={delimiter} onChangeDelimiter={handleDelimiterChange} />
                <h3>{t("import.mapping.title")}</h3>
                <ColumnMapper headers={headers} onSave={handleMappingSave} />
                {validationIssues.length > 0 ? (
                  <div className="import-page__validation" role="alert" aria-live="assertive">
                    <h4>{t("import.validation.title")}</h4>
                    <ul>
                      {validationIssues.map((issue) => (
                        <li key={`${issue.line}-${issue.message}`}>
                          {t("import.validation.item", { line: issue.line, message: issue.message })}
                        </li>
                      ))}
                    </ul>
                  </div>
                ) : null}
                <button
                  type="button"
                  onClick={handleUpload}
                  disabled={loading}
                  className="import-page__submit"
                  aria-busy={loading}
                >
                  {loading ? t("import.file.uploading") : t("import.file.upload")}
                </button>
              </Fragment>
            ) : (
              <p className="import-page__hint">{t("import.file.hint")}</p>
            )}
          </div>
        ) : (
          <div className="import-page__panel" role="tabpanel" aria-label={t("import.tabs.url")}>
            <label htmlFor="import-url">{t("import.url.label")}</label>
            <input
              id="import-url"
              type="url"
              value={importUrl}
              onChange={(event) => setImportUrl(event.target.value)}
              placeholder={t("import.url.placeholder")}
              aria-describedby="import-url-hint"
            />
            <p id="import-url-hint" className="import-page__hint">
              {t("import.url.hint")}
            </p>
            <button
              type="button"
              onClick={handleImportByUrl}
              disabled={loading}
              className="import-page__submit"
              aria-busy={loading}
            >
              {loading ? t("import.url.importing") : t("import.url.import")}
            </button>
          </div>
        )}
        {loading && !hasCsvData ? <Loading variant="spinner" labelKey="loading" /> : null}
        {error ? (
          <div className="import-page__error import-page__error--global" role="alert" aria-live="assertive">
            {error}
          </div>
        ) : null}
        {report ? <ImportReportView report={report} /> : null}
      </section>
    </div>
  );
}

function ImportReportView({ report }: { report: ImportReport }): JSX.Element {
  const { t } = useTranslation();
  return (
    <div className="import-report">
      <h3>{t("import.report.title")}</h3>
      <ul>
        <li>
          {t("import.report.inserted")}: {report.inserted}
        </li>
        <li>
          {t("import.report.skipped")}: {report.skippedDuplicates}
        </li>
        <li>
          {t("import.report.failed")}: {report.failed.length === 0 ? (
            <span> {t("import.report.none")}</span>
          ) : (
            <ul>
              {report.failed.map((item) => (
                <li key={`${item.line}-${item.error}`}>
                  {t("import.validation.item", { line: item.line, message: item.error })}
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
