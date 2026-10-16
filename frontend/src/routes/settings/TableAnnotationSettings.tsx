import { useCallback, useEffect, useState } from "react";
import { ArrowPathIcon } from "@heroicons/react/20/solid";
import {
  AnnotationSource,
  AnnotationSyncStatus,
  TableAnnotation,
  TableAnnotationPayload,
  createTableAnnotation,
  deleteTableAnnotation,
  getTableAnnotations,
  isTableAnnotation,
  syncTableAnnotations,
  updateTableAnnotation,
} from "../../api/TableAnnotationApi";
import { isApiErrorResponse } from "../../api/Errors";
import Button from "../../components/Button";
import InputField from "../../components/InputField";
import Modal from "../../components/Modal";
import SettingsTable, { Column } from "../../components/SettingsTable";
import Spinner from "../../components/Spinner";
import ReadOnlyNotice from "../../components/ReadOnlyNotice";
import useNotification from "../../hooks/useNotification";
import { useHasPermission } from "../../hooks/permissions";
import { WarningBanner } from "../../components/Alert";

const emptyDraft: TableAnnotationPayload = {
  tablePattern: "",
  connectionSelector: "*",
  message: "",
  runbookUrl: "",
  requiresAcknowledgement: true,
};

export default function TableAnnotationSettings() {
  const canEdit = useHasPermission("configuration:edit");
  const { addNotification } = useNotification();

  const [annotations, setAnnotations] = useState<TableAnnotation[]>([]);
  const [syncStatus, setSyncStatus] = useState<AnnotationSyncStatus | null>(
    null,
  );
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState<TableAnnotation | null>(null);
  const [draft, setDraft] = useState<TableAnnotationPayload | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    const response = await getTableAnnotations();
    if (isApiErrorResponse(response)) {
      addNotification({
        title: "Failed to load table annotations",
        text: response.message,
        type: "error",
      });
    } else {
      setAnnotations(response.annotations);
      setSyncStatus(response.syncStatus);
    }
    setLoading(false);
  }, [addNotification]);

  useEffect(() => {
    void load();
  }, [load]);

  const save = async () => {
    if (!draft) return;
    const payload: TableAnnotationPayload = {
      ...draft,
      runbookUrl: draft.runbookUrl?.trim() ? draft.runbookUrl.trim() : null,
    };
    const response = editing
      ? await updateTableAnnotation(editing.id, payload)
      : await createTableAnnotation(payload);

    // Not isApiErrorResponse: an annotation has a `message` of its own, which that guard would
    // mistake for an error envelope. See isTableAnnotation.
    if (!isTableAnnotation(response)) {
      addNotification({
        title: "Failed to save the annotation",
        text: response.message,
        type: "error",
      });
      return;
    }
    setDraft(null);
    setEditing(null);
    await load();
  };

  const remove = async (annotation: TableAnnotation) => {
    const error = await deleteTableAnnotation(annotation.id);
    if (error) {
      addNotification({
        title: "Failed to delete the annotation",
        text: error.message,
        type: "error",
      });
      return;
    }
    await load();
  };

  const runSync = async () => {
    const response = await syncTableAnnotations();
    if (isApiErrorResponse(response)) {
      addNotification({
        title: "Sync failed",
        text: response.message,
        type: "error",
      });
    } else {
      addNotification({
        title: "Sync complete",
        text: `${response.syncedCount} annotation(s) are in force.`,
        type: "info",
      });
    }
    await load();
  };

  const columns: Column<TableAnnotation>[] = [
    {
      header: "Table",
      render: (annotation) => (
        <span className="font-mono">{annotation.tablePattern}</span>
      ),
    },
    {
      header: "Connections",
      render: (annotation) => (
        <span className="font-mono">{annotation.connectionSelector}</span>
      ),
    },
    { header: "Message", accessor: "message" },
    {
      header: "Acknowledgement",
      render: (annotation) =>
        annotation.requiresAcknowledgement ? "Required" : "Warn only",
    },
    {
      header: "Source",
      render: (annotation) =>
        annotation.source === AnnotationSource.SYNCED ? "Synced" : "Manual",
    },
  ];

  return (
    <div>
      <h1 className="mb-2 text-lg">Table Annotations</h1>
      <p className="mb-4 text-sm text-slate-600 dark:text-slate-400">
        Attach a warning to a table so that writing to it raises a flag for the
        author, the reviewer and whoever runs the statement. Useful when a table
        is mirrored into a cache or a secondary store that a direct write would
        leave stale. Reads never raise a warning.
      </p>

      {!canEdit && <ReadOnlyNotice resource="these settings" className="mb-2" />}

      {syncStatus?.neverSynced && (
        <WarningBanner className="mb-3">
          A sync source is configured but has never returned a list, so Kviklet
          does not yet know which tables are annotated. An empty warning does not
          mean a statement is safe until the first sync succeeds.
        </WarningBanner>
      )}

      {syncStatus?.lastSyncError && (
        <WarningBanner className="mb-3">
          The last sync failed: {syncStatus.lastSyncError}. The annotations below
          are the ones last synced successfully and are still enforced, but they
          may be out of date.
        </WarningBanner>
      )}

      {syncStatus?.configured && (
        <div className="mb-3 flex items-center text-sm text-slate-600 dark:text-slate-400">
          <span>
            {syncStatus.syncedCount} synced annotation(s)
            {syncStatus.lastSyncAt &&
              `, last synced ${new Date(syncStatus.lastSyncAt).toLocaleString()}`}
          </span>
          {canEdit && (
            <Button
              className="ml-3"
              size="sm"
              onClick={() => void runSync()}
              dataTestId="sync-annotations"
            >
              <ArrowPathIcon className="mr-1 inline h-4" />
              Sync now
            </Button>
          )}
        </div>
      )}

      {loading ? (
        <Spinner size="lg" page />
      ) : (
        <SettingsTable
          title="Annotations"
          data={annotations}
          columns={columns}
          keyExtractor={(annotation) => annotation.id}
          onRowClick={
            canEdit
              ? (annotation) => {
                  // Synced rows are owned by the sync source, so editing one here would be
                  // undone by the next sync. The server refuses it too.
                  if (annotation.source === AnnotationSource.SYNCED) return;
                  setEditing(annotation);
                  setDraft({
                    tablePattern: annotation.tablePattern,
                    connectionSelector: annotation.connectionSelector,
                    message: annotation.message,
                    runbookUrl: annotation.runbookUrl ?? "",
                    requiresAcknowledgement: annotation.requiresAcknowledgement,
                  });
                }
              : undefined
          }
          onDelete={canEdit ? remove : undefined}
          canDelete={(annotation) =>
            annotation.source === AnnotationSource.MANUAL
          }
          onCreate={
            canEdit
              ? () => {
                  setEditing(null);
                  setDraft(emptyDraft);
                }
              : undefined
          }
          createButtonLabel="Add annotation"
          emptyMessage="No tables are annotated yet."
          testId="table-annotations-table"
        />
      )}

      {draft && (
        <Modal setVisible={() => setDraft(null)}>
          <div className="rounded border border-slate-200 bg-slate-50 p-4 shadow dark:border-none dark:bg-slate-950">
            <h2 className="mb-3 text-lg">
              {editing ? "Edit annotation" : "Add annotation"}
            </h2>
            <div className="flex flex-col space-y-4">
              <InputField
                label="Table pattern"
                data-testid="annotation-table-pattern"
                value={draft.tablePattern}
                tooltip="The table name, or a wildcard like billing_*. Match either the bare name or schema.name."
                onChange={(event: React.ChangeEvent<HTMLInputElement>) =>
                  setDraft({ ...draft, tablePattern: event.target.value })
                }
                placeholder="subscriber"
              />
              <InputField
                label="Connection selector"
                data-testid="annotation-connection-selector"
                value={draft.connectionSelector}
                tooltip="Which connections this applies to. Use * for all, or a wildcard like prod-*."
                onChange={(event: React.ChangeEvent<HTMLInputElement>) =>
                  setDraft({ ...draft, connectionSelector: event.target.value })
                }
                placeholder="*"
              />
              <InputField
                label="Message"
                data-testid="annotation-message"
                value={draft.message}
                tooltip="Shown to the author, the reviewer and the executor. Say what to do, not just that it is cached."
                onChange={(event: React.ChangeEvent<HTMLInputElement>) =>
                  setDraft({ ...draft, message: event.target.value })
                }
                placeholder="Mirrored into the read cache. Purge it after this runs."
              />
              <InputField
                label="Runbook URL"
                data-testid="annotation-runbook-url"
                value={draft.runbookUrl ?? ""}
                tooltip="Optional link shown alongside the message."
                onChange={(event: React.ChangeEvent<HTMLInputElement>) =>
                  setDraft({ ...draft, runbookUrl: event.target.value })
                }
                placeholder="https://..."
              />
              <InputField
                label="Require acknowledgement before executing"
                data-testid="annotation-requires-ack"
                type="checkbox"
                checked={draft.requiresAcknowledgement}
                tooltip="When off the warning is shown but never blocks execution."
                onChange={(event: React.ChangeEvent<HTMLInputElement>) =>
                  setDraft({
                    ...draft,
                    requiresAcknowledgement: event.target.checked,
                  })
                }
              />
            </div>
            <div className="mt-4 flex flex-row">
              <Button className="ml-auto" onClick={() => setDraft(null)}>
                Cancel
              </Button>
              <Button
                className="ml-2"
                variant="primary"
                onClick={() => void save()}
                dataTestId="save-annotation"
              >
                Save
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  );
}
