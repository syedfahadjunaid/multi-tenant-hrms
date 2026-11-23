import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { LeaveRequest } from "../../api/types";

const mocks = vi.hoisted(() => ({ apiFetch: vi.fn() }));
vi.mock("../../api/client", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../api/client")>()),
  apiFetch: mocks.apiFetch,
}));

import { LeaveRequestCard } from "./LeaveRequestCard";

const REQUEST: LeaveRequest = {
  id: "req-1",
  employee: { id: "e1", name: "Bob Builder" },
  approver: { id: "m1", name: "Mark Manager" },
  type: "ANNUAL",
  startDate: "2026-11-23",
  endDate: "2026-11-25",
  workingDays: 3,
  reason: "Family visit",
  status: "PENDING_MANAGER",
  createdAt: "2026-11-20T10:00:00Z",
  allowedActions: [],
};

function renderCard(request: LeaveRequest) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <LeaveRequestCard request={request} showEmployee />
    </QueryClientProvider>,
  );
}

describe("LeaveRequestCard", () => {
  beforeEach(() => {
    mocks.apiFetch.mockReset();
    mocks.apiFetch.mockResolvedValue({ ...REQUEST, status: "REJECTED", allowedActions: [] });
  });

  it("shows the requester only what the server allows: cancelling", () => {
    renderCard({ ...REQUEST, allowedActions: ["CANCEL"] });

    expect(screen.getByRole("button", { name: "Cancel request" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
    expect(screen.getByText("Waiting for Mark Manager to approve.")).toBeInTheDocument();
  });

  it("needs a comment before the manager can reject", async () => {
    const user = userEvent.setup();
    renderCard({ ...REQUEST, allowedActions: ["APPROVE", "REJECT"] });

    const reject = screen.getByRole("button", { name: "Reject" });
    expect(reject).toBeDisabled();

    await user.type(screen.getByLabelText("Comment (required to reject)"), "Team offsite that week");
    await user.click(reject);

    expect(mocks.apiFetch).toHaveBeenCalledWith("/api/leave/requests/req-1/reject", {
      method: "POST",
      body: { comment: "Team offsite that week" },
    });
  });

  it("shows no buttons on a finished request", () => {
    renderCard({ ...REQUEST, status: "APPROVED", allowedActions: [] });

    expect(screen.getByText("Approved")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /approve|reject|cancel request/i })).not.toBeInTheDocument();
  });
});
