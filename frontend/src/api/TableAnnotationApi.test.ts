import { describe, it, expect } from "vitest";
import { isApiErrorResponse } from "./Errors";
import { AnnotationSource, isTableAnnotation } from "./TableAnnotationApi";
import type { TableAnnotation } from "./TableAnnotationApi";

const annotation: TableAnnotation = {
  id: "abc123",
  tablePattern: "subscriber",
  connectionSelector: "*",
  message: "Mirrored into the read cache.",
  runbookUrl: null,
  requiresAcknowledgement: true,
  source: AnnotationSource.MANUAL,
  updatedAt: "2026-10-16T07:47:14Z",
};

describe("isTableAnnotation", () => {
  it("accepts a real annotation that the generic error guard misreads", () => {
    // An annotation carries its own `message`, so the non-strict ApiErrorResponse schema matches
    // it. This is exactly the confusion that made every successful save look like a failure.
    expect(isApiErrorResponse(annotation)).toBe(true);
    expect(isTableAnnotation(annotation)).toBe(true);
  });

  it("rejects an actual error envelope", () => {
    expect(isTableAnnotation({ message: "Access denied" })).toBe(false);
  });
});
