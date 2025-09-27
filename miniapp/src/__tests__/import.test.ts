import { describe, expect, it, beforeEach, vi } from "vitest";
import type { Mock } from "vitest";
import "@testing-library/jest-dom/vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { createElement } from "react";
import { detectDelimiter, parseCsv, sanitizeCsvCell } from "../lib/csv";
import { ColumnMapper } from "../components/ColumnMapper";
import { Import } from "../pages/Import";
import * as api from "../lib/api";

vi.mock("../lib/api", async () => {
  const actual = await vi.importActual<typeof import("../lib/api")>("../lib/api");
  return {
    ...actual,
    postImportCsv: vi.fn(),
    postImportByUrl: vi.fn(),
  };
});

let mockCsvContent = "";

vi.mock("../components/FileDrop", () => {
  return {
    FileDrop: ({ onFileSelected }: { onFileSelected: (file: File, preview: string[]) => void }) =>
      createElement(
        "button",
        {
          type: "button",
          onClick: () => {
            const preview = mockCsvContent.split(/\r?\n/).slice(0, 3);
            const file = {
              name: "trades.csv",
              type: "text/csv",
              size: mockCsvContent.length,
              text: () => Promise.resolve(mockCsvContent),
            } as unknown as File;
            onFileSelected(file, preview);
          },
        },
        "Select mock CSV",
      ),
  };
});

describe("csv utilities", () => {
  it("detects delimiter and parses comma separated CSV", () => {
    const sample = "a,b,c\n1,2,3";
    const delimiter = detectDelimiter(sample);
    expect(delimiter).toBe(",");
    const parsed = parseCsv(sample, delimiter);
    expect(parsed.headers).toEqual(["a", "b", "c"]);
    expect(parsed.rows).toEqual([["1", "2", "3"]]);
  });

  it("detects delimiter and parses semicolon separated CSV", () => {
    const sample = "a;b;c\n1;2;3";
    const delimiter = detectDelimiter(sample);
    expect(delimiter).toBe(";");
    const parsed = parseCsv(sample, delimiter);
    expect(parsed.headers).toEqual(["a", "b", "c"]);
    expect(parsed.rows).toEqual([["1", "2", "3"]]);
  });

  it("detects delimiter and parses tab separated CSV", () => {
    const sample = "a\tb\tc\n1\t2\t3";
    const delimiter = detectDelimiter(sample);
    expect(delimiter).toBe("\t");
    const parsed = parseCsv(sample, delimiter);
    expect(parsed.headers).toEqual(["a", "b", "c"]);
    expect(parsed.rows).toEqual([["1", "2", "3"]]);
  });

  it("sanitizes CSV injection patterns", () => {
    expect(sanitizeCsvCell("=SUM(A1)")).toBe("'=SUM(A1)");
    expect(sanitizeCsvCell("@cmd")).toBe("'@cmd");
    expect(sanitizeCsvCell(" normal")).toBe(" normal");
  });
});

describe("ColumnMapper", () => {
  it("requires mapping for required fields", () => {
    render(createElement(ColumnMapper, { headers: ["col1", "col2"], onSave: vi.fn() }));
    fireEvent.click(screen.getByText("Save mapping"));
    expect(screen.getByText("Please map all required fields before continuing.")).toBeInTheDocument();
  });
});

describe("Import page", () => {
  const postImportCsvMock = api.postImportCsv as unknown as Mock;
  const postImportByUrlMock = api.postImportByUrl as unknown as Mock;

  beforeEach(() => {
    postImportCsvMock.mockReset();
    postImportByUrlMock.mockReset();
    mockCsvContent = "";
  });

  it("validates https URL before importing", async () => {
    render(createElement(Import));
    const portfolioInput = screen.getByLabelText("Portfolio ID");
    fireEvent.change(portfolioInput, { target: { value: "00000000-0000-0000-0000-000000000001" } });
    fireEvent.click(screen.getByText("By URL"));
    const urlInput = screen.getByLabelText("CSV export URL");
    fireEvent.change(urlInput, { target: { value: "http://example.com/data.csv" } });
    fireEvent.click(screen.getByText("Import from URL"));
    expect(await screen.findByText("URL must start with https://")).toBeInTheDocument();
    expect(postImportByUrlMock).not.toHaveBeenCalled();
  });

  it("uploads CSV after successful mapping", async () => {
    mockCsvContent =
      "ext_id,datetime,ticker,exchange,board,alias_source,side,quantity,price,currency,fee,fee_currency,tax,tax_currency,broker,note\n" +
      "trade-1,2024-03-16 10:00:00,SBER,MOEX,TQBR,,BUY,10,150,RUB,0,RUB,,,BrokerCo,First trade";
    postImportCsvMock.mockResolvedValue({ inserted: 1, skippedDuplicates: 0, failed: [] });

    render(createElement(Import));

    const portfolioInput = screen.getByLabelText("Portfolio ID");
    fireEvent.change(portfolioInput, { target: { value: "00000000-0000-0000-0000-000000000001" } });

    fireEvent.click(screen.getByText("Select mock CSV"));
    await screen.findByText("Map CSV columns");
    fireEvent.click(screen.getByText("Save mapping"));
    fireEvent.click(screen.getByText("Upload CSV"));

    await waitFor(() => expect(postImportCsvMock).toHaveBeenCalledTimes(1));
    expect(await screen.findByText("Import report")).toBeInTheDocument();
    expect(screen.getByText("Inserted: 1")).toBeInTheDocument();
  });
});

