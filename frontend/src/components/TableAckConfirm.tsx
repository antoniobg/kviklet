import { useState } from "react";
import Button from "./Button";
import { TableWarning } from "../api/TableAnnotationApi";

/**
 * Collects an explicit confirmation for each annotated table a statement writes to.
 *
 * A checkbox per table rather than one blanket confirmation: the point is that the executor reads
 * which tables are affected, and a single "I agree" invites exactly the reflexive click the warning
 * exists to prevent. The server re-checks the acknowledgement, so this is a prompt, not the gate.
 */
const TableAckConfirm = (props: {
  warnings: TableWarning[];
  onConfirm: (acknowledgedTables: string[]) => Promise<void>;
  onCancel: () => void;
}) => {
  const tables = Array.from(new Set(props.warnings.map((w) => w.table)));
  const [acknowledged, setAcknowledged] = useState<Record<string, boolean>>({});
  const [submitting, setSubmitting] = useState(false);

  const allAcknowledged = tables.every((table) => acknowledged[table]);

  const toggle = (table: string) =>
    setAcknowledged((current) => ({ ...current, [table]: !current[table] }));

  const confirm = async () => {
    setSubmitting(true);
    try {
      await props.onConfirm(tables);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="w-2xl rounded border border-slate-200 bg-slate-50 p-3 shadow dark:border-none dark:bg-slate-950">
      <div className="mb-3 flex flex-col">
        <h1 className="text-l">Confirm before executing</h1>
        <p className="mt-2 text-slate-700 dark:text-slate-200">
          This statement writes to{" "}
          {tables.length === 1 ? "a table that carries" : "tables that carry"} a
          warning. Confirm you have read{" "}
          {tables.length === 1 ? "it" : "each of them"}.
        </p>

        <ul className="mt-3 space-y-3">
          {tables.map((table) => {
            const tableWarnings = props.warnings.filter(
              (w) => w.table === table,
            );
            return (
              <li key={table}>
                <label className="flex cursor-pointer items-start">
                  <input
                    type="checkbox"
                    className="mt-1 h-4 w-4 cursor-pointer rounded border-slate-300 text-indigo-700 focus:ring-indigo-700 dark:border-slate-600 dark:bg-slate-800"
                    checked={acknowledged[table] ?? false}
                    onChange={() => toggle(table)}
                    data-testid={`ack-${table}`}
                  />
                  <span className="ml-2 text-sm text-slate-700 dark:text-slate-200">
                    <span className="font-mono font-medium">{table}</span>
                    {tableWarnings.map((warning) => (
                      <span
                        key={warning.message}
                        className="block text-slate-600 dark:text-slate-400"
                      >
                        {warning.message}
                        {warning.runbookUrl && (
                          <>
                            {" "}
                            <a
                              className="underline hover:no-underline"
                              href={warning.runbookUrl}
                              target="_blank"
                              rel="noreferrer"
                              onClick={(event) => event.stopPropagation()}
                            >
                              Runbook
                            </a>
                          </>
                        )}
                      </span>
                    ))}
                  </span>
                </label>
              </li>
            );
          })}
        </ul>

        <div className="mt-4 flex flex-row">
          <Button className="ml-auto" onClick={props.onCancel}>
            Cancel
          </Button>
          <Button
            className="ml-2"
            onClick={() => void confirm()}
            variant={allAcknowledged && !submitting ? "primary" : "disabled"}
            dataTestId="confirm-table-ack"
          >
            Execute
          </Button>
        </div>
      </div>
    </div>
  );
};

export default TableAckConfirm;
