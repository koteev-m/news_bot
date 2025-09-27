import { useEffect, useMemo, useState } from "react";

export type ColumnKey =
  | "datetime"
  | "ticker"
  | "exchange"
  | "board"
  | "alias_source"
  | "side"
  | "quantity"
  | "price"
  | "currency"
  | "fee"
  | "fee_currency"
  | "tax"
  | "tax_currency"
  | "broker"
  | "note"
  | "ext_id";

interface FieldDefinition {
  key: ColumnKey;
  label: string;
  required: boolean;
  hint?: string;
}

export type ColumnMapping = Record<ColumnKey, string | null>;

interface ColumnMapperProps {
  headers: string[];
  initialMapping?: Partial<ColumnMapping>;
  onSave: (mapping: ColumnMapping) => void;
}

const FIELDS: FieldDefinition[] = [
  { key: "datetime", label: "Execution time", required: true, hint: "YYYY-MM-DD HH:mm:ss or ISO-8601" },
  { key: "ticker", label: "Ticker / Symbol", required: true },
  { key: "exchange", label: "Exchange", required: true },
  { key: "board", label: "Board", required: false },
  { key: "alias_source", label: "Account / Alias source", required: false, hint: "COINGECKO, ISS code, etc" },
  { key: "side", label: "Side (BUY/SELL)", required: true },
  { key: "quantity", label: "Quantity", required: true },
  { key: "price", label: "Price", required: true },
  { key: "currency", label: "Price currency", required: true },
  { key: "fee", label: "Fee", required: false },
  { key: "fee_currency", label: "Fee currency", required: false },
  { key: "tax", label: "Tax", required: false },
  { key: "tax_currency", label: "Tax currency", required: false },
  { key: "broker", label: "Broker", required: false },
  { key: "note", label: "Note", required: false },
  { key: "ext_id", label: "External ID", required: false },
];

function createInitialMapping(headers: string[], initial?: Partial<ColumnMapping>): ColumnMapping {
  const base: ColumnMapping = {
    datetime: null,
    ticker: null,
    exchange: null,
    board: null,
    alias_source: null,
    side: null,
    quantity: null,
    price: null,
    currency: null,
    fee: null,
    fee_currency: null,
    tax: null,
    tax_currency: null,
    broker: null,
    note: null,
    ext_id: null,
  };
  if (!initial) {
    return autoDetect(headers, base);
  }
  const withInitial: ColumnMapping = { ...base };
  (Object.keys(initial) as ColumnKey[]).forEach((key) => {
    const column = initial[key];
    if (typeof column === "string" && column.length > 0 && headers.includes(column)) {
      withInitial[key] = column;
    }
  });
  return autoDetect(headers, withInitial);
}

function autoDetect(headers: string[], mapping: ColumnMapping): ColumnMapping {
  const lowered = headers.map((header) => header.trim().toLowerCase());
  const guess: Partial<Record<ColumnKey, string>> = {};
  const pairs: Array<[ColumnKey, string[]]> = [
    ["datetime", ["datetime", "executed", "time", "timestamp"]],
    ["ticker", ["ticker", "symbol", "instrument", "asset"]],
    ["exchange", ["exchange", "market"]],
    ["board", ["board", "section"]],
    ["alias_source", ["alias", "account", "source", "crypto"]],
    ["side", ["side", "buy_sell", "action"]],
    ["quantity", ["quantity", "qty", "amount", "shares"]],
    ["price", ["price", "cost"]],
    ["currency", ["currency", "ccy", "price_currency"]],
    ["fee", ["fee", "commission"]],
    ["fee_currency", ["fee_currency", "commission_currency", "fee_ccy"]],
    ["tax", ["tax"]],
    ["tax_currency", ["tax_currency", "tax_ccy"]],
    ["broker", ["broker"]],
    ["note", ["note", "comment", "memo"]],
    ["ext_id", ["ext_id", "external_id", "trade_id"]],
  ];
  for (const [key, candidates] of pairs) {
    if (mapping[key]) {
      continue;
    }
    const foundIndex = lowered.findIndex((header) => candidates.some((candidate) => header === candidate));
    if (foundIndex >= 0) {
      guess[key] = headers[foundIndex];
    }
  }
  return { ...mapping, ...guess } as ColumnMapping;
}

export function ColumnMapper({ headers, initialMapping, onSave }: ColumnMapperProps): JSX.Element {
  const [mapping, setMapping] = useState<ColumnMapping>(() => createInitialMapping(headers, initialMapping));
  const [errors, setErrors] = useState<Set<ColumnKey>>(new Set());
  const [globalError, setGlobalError] = useState<string | null>(null);

  useEffect(() => {
    setMapping(createInitialMapping(headers, initialMapping));
    setErrors(new Set());
    setGlobalError(null);
  }, [headers, initialMapping]);

  const columnUsage = useMemo(() => {
    const usage = new Map<string, ColumnKey[]>();
    (Object.keys(mapping) as ColumnKey[]).forEach((key) => {
      const column = mapping[key];
      if (!column) {
        return;
      }
      if (!usage.has(column)) {
        usage.set(column, []);
      }
      usage.get(column)?.push(key);
    });
    return usage;
  }, [mapping]);

  const selectedColumns = useMemo(() => new Set<string>(Object.values(mapping).filter((value): value is string => Boolean(value))), [mapping]);

  const handleSelect = (key: ColumnKey, value: string) => {
    setMapping((prev) => ({ ...prev, [key]: value === "" ? null : value }));
    setErrors((prev) => {
      const next = new Set(prev);
      next.delete(key);
      return next;
    });
    setGlobalError(null);
  };

  const handleSave = () => {
    const missing = FIELDS.filter((field) => field.required && !mapping[field.key]);
    const duplicates = Array.from(columnUsage.entries()).filter(([, owners]) => owners.length > 1);
    if (missing.length > 0 || duplicates.length > 0) {
      const errorFields = new Set<ColumnKey>();
      missing.forEach((field) => errorFields.add(field.key));
      duplicates.forEach(([, owners]) => owners.forEach((owner) => errorFields.add(owner)));
      setErrors(errorFields);
      setGlobalError(
        missing.length > 0
          ? "Please map all required fields before continuing."
          : "Each column can be mapped to only one field.",
      );
      return;
    }
    onSave(mapping);
  };

  return (
    <div className="column-mapper">
      <h3>Map CSV columns</h3>
      <p className="column-mapper__hint">Match the imported columns with the required schema.</p>
      <div className="column-mapper__grid">
        {FIELDS.map((field) => {
          const assigned = mapping[field.key] ?? "";
          const fieldErrors: string[] = [];
          if (errors.has(field.key) && field.required && !assigned) {
            fieldErrors.push("Required");
          }
          const owners = assigned ? columnUsage.get(assigned) ?? [] : [];
          if (errors.has(field.key) && owners.length > 1) {
            const others = owners.filter((owner) => owner !== field.key);
            if (others.length > 0) {
              fieldErrors.push(`Also mapped to ${others.map((owner) => formatFieldLabel(owner)).join(", ")}`);
            }
          }
          return (
            <div key={field.key} className={`column-mapper__row${fieldErrors.length > 0 ? " column-mapper__row--error" : ""}`}>
              <label className="column-mapper__label" htmlFor={`column-map-${field.key}`}>
                {field.label}
                {field.required ? <span className="column-mapper__required">*</span> : null}
              </label>
              <div className="column-mapper__control">
                <select
                  id={`column-map-${field.key}`}
                  value={assigned}
                  onChange={(event) => handleSelect(field.key, event.target.value)}
                >
                  <option value="">Not mapped</option>
                  {headers.map((header) => {
                    const isSelectedElsewhere = selectedColumns.has(header) && mapping[field.key] !== header;
                    return (
                      <option key={header} value={header} disabled={isSelectedElsewhere}>
                        {header}
                      </option>
                    );
                  })}
                </select>
                {field.hint ? <span className="column-mapper__hint">{field.hint}</span> : null}
                {fieldErrors.length > 0 ? (
                  <div className="column-mapper__error">{fieldErrors.join(". ")}</div>
                ) : null}
              </div>
            </div>
          );
        })}
      </div>
      {globalError ? <div className="column-mapper__error column-mapper__error--global">{globalError}</div> : null}
      <div className="column-mapper__actions">
        <button type="button" onClick={handleSave} className="column-mapper__save">
          Save mapping
        </button>
      </div>
    </div>
  );
}

function formatFieldLabel(key: ColumnKey): string {
  const field = FIELDS.find((entry) => entry.key === key);
  return field ? field.label : key;
}
