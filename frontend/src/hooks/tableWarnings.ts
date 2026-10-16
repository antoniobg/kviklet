import { useEffect, useState } from "react";
import {
  AnalyzeStatementResponse,
  TableWarning,
  analyzeStatement,
} from "../api/TableAnnotationApi";
import { isApiErrorResponse } from "../api/Errors";

interface TableWarningsState {
  warnings: TableWarning[];
  /** False when the statement could not be parsed, so the empty warning list proves nothing. */
  parsed: boolean;
  loading: boolean;
}

const empty: TableWarningsState = {
  warnings: [],
  parsed: true,
  loading: false,
};

/**
 * Keeps the table warnings for a statement in step with what the user has typed.
 *
 * Debounced, because this runs on every keystroke in the editor and each call parses the statement
 * on the server. A failed request degrades to "no warnings" rather than blocking the page: the
 * execute endpoint re-checks regardless, so a missed warning here costs a prompt, not the gate.
 */
export function useTableWarnings(
  connectionId: string | undefined,
  statement: string | undefined,
  debounceMs = 400,
): TableWarningsState {
  const [state, setState] = useState<TableWarningsState>(empty);

  useEffect(() => {
    if (!connectionId || !statement || statement.trim() === "") {
      setState(empty);
      return;
    }

    let cancelled = false;
    setState((current) => ({ ...current, loading: true }));

    const timer = setTimeout(() => {
      void (async () => {
        const response: AnalyzeStatementResponse | { message: string } =
          await analyzeStatement(connectionId, statement);
        if (cancelled) return;

        if (isApiErrorResponse(response)) {
          setState(empty);
          return;
        }
        setState({
          warnings: response.warnings,
          parsed: response.parsed,
          loading: false,
        });
      })();
    }, debounceMs);

    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [connectionId, statement, debounceMs]);

  return state;
}

export type { TableWarningsState };
