import type { ReactNode } from "react";

export interface Column<Row> {
  key: keyof Row | string;
  label: string;
  render?: (row: Row) => ReactNode;
}

interface DataTableProps<Row> {
  columns: Column<Row>[];
  rows: Row[];
  emptyMessage?: string;
}

export function DataTable<Row>({ columns, rows, emptyMessage }: DataTableProps<Row>): JSX.Element {
  if (rows.length === 0) {
    return <div className="data-table__empty">{emptyMessage ?? "No data"}</div>;
  }
  return (
    <table className="data-table">
      <thead>
        <tr>
          {columns.map((column) => (
            <th key={column.key as string}>{column.label}</th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((row, index) => (
          <tr key={(row as unknown as { id?: string }).id ?? index}>
            {columns.map((column) => (
              <td key={column.key as string}>
                {column.render ? column.render(row) : String((row as Record<string, unknown>)[column.key as string] ?? "")}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}
