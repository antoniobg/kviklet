import { test, expect } from "@playwright/test";
import {
  LoginPage,
  RequestsPage,
  RequestsReviewPage,
  SettingsPage,
} from "./pages";

/**
 * Covers the acknowledgement gate from the browser: an approved statement that writes to an
 * annotated table cannot be run until the person running it confirms the warning, and the
 * confirmation is recorded on the request.
 *
 * Uses a manually created annotation so the run needs no external sync endpoint.
 */
test.describe("Table annotations", () => {
  let loginPage: LoginPage;
  let settingsPage: SettingsPage;
  let requestsPage: RequestsPage;

  const connectionName = "AnnotationPostgres";
  const annotatedTable = "test";

  test.beforeEach(async ({ page }) => {
    loginPage = new LoginPage(page);
    settingsPage = new SettingsPage(page);
    requestsPage = new RequestsPage(page);

    await page.goto("/");
    await loginPage.login("admin@admin.com", "admin");
  });

  test("a write to an annotated table must be acknowledged before it runs", async ({
    page,
  }) => {
    await settingsPage.navigateToConnections();
    await settingsPage.createConnection(
      connectionName,
      "Postgres",
      "postgres",
      "postgres",
      "postgres",
      "5432",
      "postgres",
      undefined,
      0,
    );

    await settingsPage.addTableAnnotation({
      tablePattern: annotatedTable,
      message: "Mirrored into the read cache. Purge it after this runs.",
    });

    const requestName = "Update the annotated table";
    await requestsPage.createRequest(
      connectionName,
      requestName,
      "Touches a table that is cached elsewhere",
      `UPDATE public.${annotatedTable} SET test = 'annotated' WHERE id = 2;`,
    );

    // The warning is on the page itself, so a reviewer sees it without doing anything.
    const warnings = page.getByTestId("table-warnings");
    await expect(warnings).toBeVisible();
    await expect(warnings).toContainText(
      "Mirrored into the read cache. Purge it after this runs.",
    );

    // Running asks for confirmation instead of executing straight away.
    await page.getByTestId("run-query-button").click();
    const confirmButton = page.getByTestId("confirm-table-ack");
    await expect(confirmButton).toBeVisible();

    // Execution stays unavailable until the table is ticked.
    await expect(confirmButton).toBeDisabled();

    await page.getByTestId(`ack-public.${annotatedTable}`).click();
    await expect(confirmButton).toBeEnabled();
    await confirmButton.click();

    await page.waitForSelector('[data-testid="result-component"]');

    // The acknowledgement is part of the record, not just a client-side prompt.
    await expect(page.getByTestId("acknowledged-tables")).toContainText(
      `public.${annotatedTable}`,
    );
  });

  test("a read of an annotated table runs without confirmation", async ({
    page,
  }) => {
    await settingsPage.navigateToConnections();
    await settingsPage.createConnection(
      `${connectionName}Read`,
      "Postgres",
      "postgres",
      "postgres",
      "postgres",
      "5432",
      "postgres",
      undefined,
      0,
    );

    await settingsPage.addTableAnnotation({
      tablePattern: annotatedTable,
      message: "Mirrored into the read cache.",
    });

    const requestName = "Read the annotated table";
    await requestsPage.createRequest(
      `${connectionName}Read`,
      requestName,
      "Only reads",
      `SELECT * FROM public.${annotatedTable};`,
    );

    // A select cannot leave a cache stale, so neither the banner nor the gate appears.
    await expect(page.getByTestId("table-warnings")).toHaveCount(0);

    const review = new RequestsReviewPage(page, requestName);
    await review.executeRequest();
    await expect(page.getByTestId("confirm-table-ack")).toHaveCount(0);
  });
});
