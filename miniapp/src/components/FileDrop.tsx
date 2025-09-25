import { useCallback, useState } from "react";
import type { ChangeEvent, DragEvent } from "react";

const MAX_SIZE_BYTES = 1024 * 1024; // 1 MiB

interface FileDropProps {
  onFileSelected: (file: File, preview: string[]) => void;
  maxPreviewLines?: number;
}

export function FileDrop({ onFileSelected, maxPreviewLines = 5 }: FileDropProps): JSX.Element {
  const [error, setError] = useState<string | null>(null);
  const [filename, setFilename] = useState<string | null>(null);
  const [previewLines, setPreviewLines] = useState<string[]>([]);

  const processFile = useCallback(
    (file: File) => {
      if (file.type && file.type !== "text/csv") {
        setError("Only CSV files are supported.");
        return;
      }
      if (file.size > MAX_SIZE_BYTES) {
        setError("File is too large. Limit is 1 MiB.");
        return;
      }
      const reader = new FileReader();
      reader.onload = () => {
        const text = typeof reader.result === "string" ? reader.result : "";
        const lines = text.split(/\r?\n/).slice(0, maxPreviewLines);
        setPreviewLines(lines);
        setError(null);
        setFilename(file.name);
        onFileSelected(file, lines);
      };
      reader.onerror = () => {
        setError("Failed to read file.");
      };
      reader.readAsText(file);
    },
    [onFileSelected, maxPreviewLines]
  );

  const handleInputChange = useCallback(
    (event: ChangeEvent<HTMLInputElement>) => {
      const file = event.target.files?.[0];
      if (file) {
        processFile(file);
      }
    },
    [processFile]
  );

  const handleDrop = useCallback(
    (event: DragEvent<HTMLDivElement>) => {
      event.preventDefault();
      const file = event.dataTransfer.files?.[0];
      if (file) {
        processFile(file);
      }
    },
    [processFile]
  );

  const handleDragOver = useCallback((event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
  }, []);

  return (
    <div className="file-drop" onDrop={handleDrop} onDragOver={handleDragOver}>
      <label className="file-drop__label">
        <input type="file" accept=".csv,text/csv" onChange={handleInputChange} />
        <span>{filename ? `Selected: ${filename}` : "Drop CSV here or click to upload"}</span>
      </label>
      {error && <div className="file-drop__error">{error}</div>}
      {previewLines.length > 0 && (
        <div className="file-drop__preview">
          <p>Preview:</p>
          <pre>
            {previewLines.join("\n")}
          </pre>
        </div>
      )}
    </div>
  );
}
