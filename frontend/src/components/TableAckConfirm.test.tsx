import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect, vi } from "vitest";
import TableAckConfirm from "./TableAckConfirm";
import { TableWarning } from "../api/TableAnnotationApi";

const warning = (
  table: string,
  message = "Cached, purge after.",
): TableWarning => ({
  table,
  message,
  runbookUrl: null,
  requiresAcknowledgement: true,
});

describe("TableAckConfirm", () => {
  it("keeps execution disabled until every table is acknowledged", async () => {
    const onConfirm = vi.fn().mockResolvedValue(undefined);
    render(
      <TableAckConfirm
        warnings={[warning("public.subscriber"), warning("public.plan")]}
        onConfirm={onConfirm}
        onCancel={() => {}}
      />,
    );

    const execute = screen.getByTestId("confirm-table-ack");
    expect(execute).toBeDisabled();

    userEvent.click(screen.getByTestId("ack-public.subscriber"));
    // One of two acknowledged is still not enough: the point is that each table is read.
    await waitFor(() => expect(execute).toBeDisabled());

    userEvent.click(screen.getByTestId("ack-public.plan"));
    await waitFor(() => expect(execute).toBeEnabled());

    userEvent.click(execute);
    await waitFor(() => expect(onConfirm).toHaveBeenCalledTimes(1));
    expect(onConfirm).toHaveBeenCalledWith([
      "public.subscriber",
      "public.plan",
    ]);
  });

  it("lists each distinct table once even when it carries several annotations", () => {
    render(
      <TableAckConfirm
        warnings={[
          warning("public.subscriber", "Cached in Redis."),
          warning("public.subscriber", "Also mirrored to the search index."),
        ]}
        onConfirm={vi.fn()}
        onCancel={() => {}}
      />,
    );

    expect(screen.getAllByTestId("ack-public.subscriber")).toHaveLength(1);
    expect(screen.getByText("Cached in Redis.")).toBeInTheDocument();
    expect(
      screen.getByText("Also mirrored to the search index."),
    ).toBeInTheDocument();
  });

  it("shows a runbook link when the annotation has one", () => {
    render(
      <TableAckConfirm
        warnings={[
          {
            table: "public.subscriber",
            message: "Cached, purge after.",
            runbookUrl: "https://runbook.example.com/cache",
            requiresAcknowledgement: true,
          },
        ]}
        onConfirm={vi.fn()}
        onCancel={() => {}}
      />,
    );

    expect(screen.getByRole("link", { name: "Runbook" })).toHaveAttribute(
      "href",
      "https://runbook.example.com/cache",
    );
  });
});
