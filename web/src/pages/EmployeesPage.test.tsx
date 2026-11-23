import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { Employee, Page } from "../api/types";

const mocks = vi.hoisted(() => ({ apiFetch: vi.fn(), roles: [] as string[] }));

// Replace only apiFetch; keep the real ApiError class.
vi.mock("../api/client", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api/client")>()),
  apiFetch: mocks.apiFetch,
}));

import { ApiError } from "../api/client";
import { EmployeesPage } from "./EmployeesPage";

const DANA: Employee = {
  id: "1",
  employeeCode: "ACME-001",
  firstName: "Dana",
  lastName: "Lee",
  email: "dana@acme.test",
  department: "Engineering",
  jobTitle: "Engineer",
  hireDate: "2024-03-01",
  managerId: null,
  managerName: null,
  createdAt: "2024-03-01T09:00:00Z",
};

function fakeApi(path: string, options?: { method?: string }) {
  if (path === "/api/me") {
    return Promise.resolve({
      subject: "s",
      username: "test",
      displayName: "Test User",
      firstName: "Test",
      tenantId: "acme",
      roles: mocks.roles,
    });
  }
  if (path.startsWith("/api/employees") && options?.method !== "POST") {
    const page: Page<Employee> = { items: [DANA], page: 0, size: 10, totalItems: 1, totalPages: 1 };
    return Promise.resolve(page);
  }
  return Promise.reject(new Error(`unexpected call ${path}`));
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <EmployeesPage />
    </QueryClientProvider>,
  );
}

describe("EmployeesPage", () => {
  beforeEach(() => {
    mocks.apiFetch.mockReset();
    mocks.apiFetch.mockImplementation(fakeApi);
  });

  it("lets HR admins see employees and add new ones", async () => {
    mocks.roles = ["HR_ADMIN", "EMPLOYEE"];
    renderPage();

    expect(await screen.findByText("1 people at acme")).toBeInTheDocument();
    expect(screen.getByText("Dana Lee")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Add employee" })).toBeInTheDocument();
  });

  it("hides the add button from managers (the API also rejects them)", async () => {
    mocks.roles = ["MANAGER", "EMPLOYEE"];
    renderPage();

    expect(await screen.findByText("1 people at acme")).toBeInTheDocument();
    expect(screen.getByText("Dana Lee")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Add employee" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /change manager/i })).not.toBeInTheDocument();
  });

  describe("changing managers", () => {
    // Sam reports to Dana
    const SAM: Employee = { ...DANA, id: "2", employeeCode: "ACME-002", firstName: "Sam", lastName: "Park", managerId: "1", managerName: "Dana Lee" };

    beforeEach(() => {
      mocks.roles = ["HR_ADMIN"];
      mocks.apiFetch.mockImplementation((path: string, options?: { method?: string; body?: unknown }) => {
        if (path === "/api/me") return fakeApi(path);
        if (options?.method === "PUT") return Promise.resolve({ ...SAM, managerId: null, managerName: null });
        const page: Page<Employee> = { items: [DANA, SAM], page: 0, size: 100, totalItems: 2, totalPages: 1 };
        return Promise.resolve(page);
      });
    });

    it("never offers someone's own reports as their manager", async () => {
      const user = userEvent.setup();
      renderPage();

      await user.click(await screen.findByRole("button", { name: "Change manager for Dana Lee" }));
      const picker = screen.getByRole("combobox", { name: "New manager for Dana Lee" });
      // Sam reports to Dana, so Sam managing Dana would be a loop
      expect(within(picker).queryByRole("option", { name: "Sam Park" })).not.toBeInTheDocument();
      expect(within(picker).queryByRole("option", { name: "Dana Lee" })).not.toBeInTheDocument();
    });

    it("lets HR remove a manager", async () => {
      const user = userEvent.setup();
      renderPage();

      await user.click(await screen.findByRole("button", { name: "Change manager for Sam Park" }));
      await user.selectOptions(screen.getByRole("combobox", { name: "New manager for Sam Park" }), "");
      await user.click(screen.getByRole("button", { name: "Save" }));

      expect(mocks.apiFetch).toHaveBeenCalledWith("/api/employees/2/manager", {
        method: "PUT",
        body: { managerId: null },
      });
      expect(await screen.findByText(/Sam Park no longer has a manager/)).toBeInTheDocument();
    });
  });

  it("shows the server's validation message next to the field", async () => {
    mocks.roles = ["HR_ADMIN"];
    mocks.apiFetch.mockImplementation((path: string, options?: { method?: string }) =>
      options?.method === "POST"
        ? Promise.reject(
            new ApiError(400, "Some fields are invalid.", [{ field: "email", message: "email is already in use" }]),
          )
        : fakeApi(path, options),
    );
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: "Add employee" }));
    await user.type(screen.getByLabelText("First name"), "Sam");
    await user.type(screen.getByLabelText("Last name"), "Park");
    await user.type(screen.getByLabelText("Work email"), "sam@acme.test");
    await user.type(screen.getByLabelText("Employee code"), "acme-002");
    fireEvent.change(screen.getByLabelText("Hire date"), { target: { value: "2024-05-01" } });
    await user.click(screen.getByRole("button", { name: "Save employee" }));

    expect(await screen.findByText("email is already in use")).toBeInTheDocument();
    expect(screen.getByLabelText("Work email")).toHaveAttribute("aria-invalid", "true");
    expect(mocks.apiFetch).toHaveBeenCalledWith(
      "/api/employees",
      expect.objectContaining({ body: expect.objectContaining({ employeeCode: "ACME-002" }) }),
    );
  });
});
