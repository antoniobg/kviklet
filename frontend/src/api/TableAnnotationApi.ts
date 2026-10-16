import { z } from "zod";
import baseUrl from "./base";
import {
  ApiResponse,
  fetchEmptyWithErrorHandling,
  fetchWithErrorHandling,
} from "./Errors";

const annotationsUrl = `${baseUrl}/table-annotations`;
const connectionsUrl = `${baseUrl}/connections`;

enum AnnotationSource {
  MANUAL = "MANUAL",
  SYNCED = "SYNCED",
}

const tableWarningSchema = z.object({
  table: z.string(),
  message: z.string(),
  runbookUrl: z.string().nullable(),
  requiresAcknowledgement: z.boolean(),
});

/**
 * `parsed: false` means the statement could not be parsed, so an empty `warnings` list says nothing
 * about whether the statement is safe. The UI has to distinguish the two.
 */
const analyzeStatementResponseSchema = z.object({
  parsed: z.boolean(),
  warnings: z.array(tableWarningSchema),
  parseError: z.string().nullable().optional(),
});

const tableAnnotationSchema = z.object({
  id: z.string(),
  tablePattern: z.string(),
  connectionSelector: z.string(),
  message: z.string(),
  runbookUrl: z.string().nullable(),
  requiresAcknowledgement: z.boolean(),
  source: z.nativeEnum(AnnotationSource),
  updatedAt: z.string(),
});

const annotationSyncStatusSchema = z.object({
  configured: z.boolean(),
  lastSyncAt: z.string().nullable(),
  lastSyncError: z.string().nullable(),
  syncedCount: z.number(),
  neverSynced: z.boolean(),
});

const tableAnnotationListResponseSchema = z.object({
  annotations: z.array(tableAnnotationSchema),
  syncStatus: annotationSyncStatusSchema,
});

type TableWarning = z.infer<typeof tableWarningSchema>;
type AnalyzeStatementResponse = z.infer<typeof analyzeStatementResponseSchema>;
type TableAnnotation = z.infer<typeof tableAnnotationSchema>;
type AnnotationSyncStatus = z.infer<typeof annotationSyncStatusSchema>;
type TableAnnotationListResponse = z.infer<
  typeof tableAnnotationListResponseSchema
>;

interface TableAnnotationPayload {
  tablePattern: string;
  connectionSelector: string;
  message: string;
  runbookUrl?: string | null;
  requiresAcknowledgement: boolean;
}

/**
 * An annotation carries a `message` field of its own, so the generic `ApiErrorResponse` shape
 * (`{ message: string }`) matches a *successful* annotation response too — Zod objects are
 * non-strict, so `isApiErrorResponse` cannot tell them apart. Discriminate on `id`, which only a
 * real annotation has.
 */
const isTableAnnotation = (
  response: ApiResponse<TableAnnotation>,
): response is TableAnnotation => "id" in response;

const analyzeStatement = async (
  connectionId: string,
  statement: string,
): Promise<ApiResponse<AnalyzeStatementResponse>> =>
  fetchWithErrorHandling(
    `${connectionsUrl}/${connectionId}/analyze-statement`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "include",
      body: JSON.stringify({ statement }),
    },
    analyzeStatementResponseSchema,
  );

const getTableAnnotations = async (): Promise<
  ApiResponse<TableAnnotationListResponse>
> =>
  fetchWithErrorHandling(
    `${annotationsUrl}/`,
    { method: "GET", credentials: "include" },
    tableAnnotationListResponseSchema,
  );

const createTableAnnotation = async (
  payload: TableAnnotationPayload,
): Promise<ApiResponse<TableAnnotation>> =>
  fetchWithErrorHandling(
    `${annotationsUrl}/`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "include",
      body: JSON.stringify(payload),
    },
    tableAnnotationSchema,
  );

const updateTableAnnotation = async (
  id: string,
  payload: TableAnnotationPayload,
): Promise<ApiResponse<TableAnnotation>> =>
  fetchWithErrorHandling(
    `${annotationsUrl}/${id}`,
    {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      credentials: "include",
      body: JSON.stringify(payload),
    },
    tableAnnotationSchema,
  );

const deleteTableAnnotation = async (id: string) =>
  fetchEmptyWithErrorHandling(`${annotationsUrl}/${id}`, {
    method: "DELETE",
    credentials: "include",
  });

const syncTableAnnotations = async (): Promise<
  ApiResponse<AnnotationSyncStatus>
> =>
  fetchWithErrorHandling(
    `${annotationsUrl}/sync`,
    { method: "POST", credentials: "include" },
    annotationSyncStatusSchema,
  );

export {
  AnnotationSource,
  analyzeStatement,
  isTableAnnotation,
  getTableAnnotations,
  createTableAnnotation,
  updateTableAnnotation,
  deleteTableAnnotation,
  syncTableAnnotations,
};

export type {
  AnalyzeStatementResponse,
  AnnotationSyncStatus,
  TableAnnotation,
  TableAnnotationListResponse,
  TableAnnotationPayload,
  TableWarning,
};
