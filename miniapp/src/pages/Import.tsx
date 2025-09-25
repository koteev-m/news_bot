import { useState } from "react";
import type { FormEvent } from "react";
import { FileDrop } from "../components/FileDrop";
import { postImportCsv, postRevalue } from "../lib/api";
import type { ImportResult } from "../lib/api";

export function Import(): JSX.Element {
  const [file, setFile] = useState<File | null>(null);
  const [result, setResult] = useState<ImportResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [revalueMessage, setRevalueMessage] = useState<string | null>(null);

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!file) {
      setError("Please select a CSV file first.");
      return;
    }
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      const response = await postImportCsv(file);
      setResult(response);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setLoading(false);
    }
  };

  const triggerRevalue = async () => {
    setRevalueMessage(null);
    try {
      const response = await postRevalue();
      setRevalueMessage(response.status);
    } catch (err) {
      setRevalueMessage((err as Error).message);
    }
  };

  return (
    <div className="page">
      <section className="card">
        <h2>Import transactions</h2>
        <form onSubmit={handleSubmit} className="import-form">
          <FileDrop
            onFileSelected={(nextFile) => {
              setFile(nextFile);
              setResult(null);
              setError(null);
            }}
          />
          {error && <div className="error">{error}</div>}
          <button type="submit" disabled={loading}>
            {loading ? "Uploading..." : "Upload"}
          </button>
        </form>
        {result && (
          <div className="import-result">
            <h3>Import result</h3>
            <ul>
              <li>Inserted: {result.inserted}</li>
              <li>Skipped: {result.skipped}</li>
              <li>
                Failed rows:
                {result.failed.length === 0 ? (
                  <span> none</span>
                ) : (
                  <ul>
                    {result.failed.map((item) => (
                      <li key={item.row}>
                        Row {item.row}: {item.reason}
                      </li>
                    ))}
                  </ul>
                )}
              </li>
            </ul>
          </div>
        )}
        <div className="import-revalue">
          <button type="button" onClick={triggerRevalue}>
            Trigger portfolio revaluation
          </button>
          {revalueMessage && <p>{revalueMessage}</p>}
        </div>
      </section>
    </div>
  );
}
