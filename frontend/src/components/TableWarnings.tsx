import {
  ExclamationTriangleIcon,
  QuestionMarkCircleIcon,
} from "@heroicons/react/20/solid";
import { TableWarning } from "../api/TableAnnotationApi";

/**
 * Lists the annotations matching the tables a statement writes to.
 *
 * Shown to the author while they write and to the reviewer before they approve, so the warning
 * reaches the person best placed to act on it rather than only the person who runs the statement.
 */
export function TableWarningList({
  warnings,
  className,
}: {
  warnings: TableWarning[];
  className?: string;
}) {
  if (warnings.length === 0) return null;

  return (
    <div
      className={`border-l-4 border-yellow-400 bg-yellow-50 p-4 dark:border-yellow-500 dark:bg-yellow-400/10${
        className ? ` ${className}` : ""
      }`}
      data-testid="table-warnings"
    >
      <div className="flex">
        <div className="flex-shrink-0">
          <ExclamationTriangleIcon
            className="h-5 w-5 text-yellow-400 dark:text-yellow-500"
            aria-hidden="true"
          />
        </div>
        <div className="ml-3 text-sm text-yellow-700 dark:text-yellow-400">
          <p className="font-medium">
            This statement writes to{" "}
            {warnings.length === 1 ? "a table" : "tables"} with a warning
            attached.
          </p>
          <ul className="mt-2 list-inside list-disc space-y-1">
            {warnings.map((warning) => (
              <li key={`${warning.table}-${warning.message}`}>
                <span className="font-mono">{warning.table}</span>:{" "}
                {warning.message}
                {warning.runbookUrl && (
                  <>
                    {" "}
                    <a
                      className="underline hover:no-underline"
                      href={warning.runbookUrl}
                      target="_blank"
                      rel="noreferrer"
                    >
                      Runbook
                    </a>
                  </>
                )}
              </li>
            ))}
          </ul>
        </div>
      </div>
    </div>
  );
}

/**
 * Shown when the statement could not be parsed.
 *
 * Deliberately distinct from showing nothing: Kviklet does not know which tables this touches, and
 * saying so is honest where silence would read as "no annotated tables are affected".
 */
export function TableWarningUnknown({ className }: { className?: string }) {
  return (
    <div
      className={`border-l-4 border-slate-300 bg-slate-50 p-4 dark:border-slate-600 dark:bg-slate-800/50${
        className ? ` ${className}` : ""
      }`}
      data-testid="table-warnings-unknown"
    >
      <div className="flex">
        <div className="flex-shrink-0">
          <QuestionMarkCircleIcon
            className="h-5 w-5 text-slate-400 dark:text-slate-500"
            aria-hidden="true"
          />
        </div>
        <div className="ml-3 text-sm text-slate-600 dark:text-slate-300">
          <p>
            Kviklet could not determine which tables this statement touches, so
            it cannot tell you whether any of them carry a warning. Check
            manually before executing.
          </p>
        </div>
      </div>
    </div>
  );
}
