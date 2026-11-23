import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { apiFetch } from "./client";
import type {
  AddDocumentResponse,
  AskResponse,
  Employee,
  LeaveAction,
  LeaveOverview,
  LeaveRequest,
  LeaveRequestDetail,
  Me,
  NewDocument,
  NewEmployee,
  NewLeaveRequest,
  Page,
  Role,
} from "./types";

/**
 * Data hooks built on TanStack Query: caching, loading/error state and refetching come for
 * free, and components never call fetch() themselves.
 */

export const EMPLOYEES_PAGE_SIZE = 10;

export function useMe() {
  return useQuery({
    queryKey: ["me"],
    queryFn: () => apiFetch<Me>("/api/me"),
    staleTime: Infinity,
  });
}

/**
 * The signed-in user: name, roles and tenant, as the API reads them from the access token
 * (the SPA itself never sees a token).
 * Hiding buttons by role is only for a tidy UI. The API enforces every permission itself.
 */
export function useCurrentUser() {
  const me = useMe();
  const roles = me.data?.roles ?? [];
  const hasAnyRole = (...wanted: Role[]) => wanted.some((role) => roles.includes(role));

  return {
    me,
    displayName: me.data?.displayName ?? "",
    firstName: me.data?.firstName ?? "",
    tenantId: me.data?.tenantId,
    roles,
    hasAnyRole,
  };
}

export function useEmployees(page: number) {
  return useQuery({
    queryKey: ["employees", page],
    queryFn: ({ signal }) =>
      apiFetch<Page<Employee>>(
        `/api/employees?page=${page}&size=${EMPLOYEES_PAGE_SIZE}&sort=lastName,asc`,
        { signal },
      ),
    placeholderData: keepPreviousData, // keep showing the old page while the next one loads
  });
}

/** Everyone in the company (up to 100), e.g. to pick a manager from. */
export function useAllEmployees(enabled = true) {
  return useQuery({
    queryKey: ["employees", "all"],
    queryFn: ({ signal }) => apiFetch<Page<Employee>>("/api/employees?size=100&sort=lastName,asc", { signal }),
    enabled,
  });
}

export function useChangeManager() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ employeeId, managerId }: { employeeId: string; managerId: string | null }) =>
      apiFetch<Employee>(`/api/employees/${employeeId}/manager`, { method: "PUT", body: { managerId } }),
    onSuccess: async () => {
      // Pending leave approvals may have moved to the new manager, so refresh both.
      await queryClient.invalidateQueries({ queryKey: ["employees"] });
      await queryClient.invalidateQueries({ queryKey: ["leave"] });
    },
  });
}

export function useCreateEmployee() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (employee: NewEmployee) =>
      apiFetch<Employee>("/api/employees", { method: "POST", body: employee }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["employees"] }),
  });
}

export function useAsk() {
  return useMutation({
    mutationFn: (question: string) =>
      apiFetch<AskResponse>("/api/assistant/ask", { method: "POST", body: { question } }),
  });
}

export function useAddDocument() {
  return useMutation({
    mutationFn: (document: NewDocument) =>
      apiFetch<AddDocumentResponse>("/api/assistant/documents", { method: "POST", body: document }),
  });
}

// ---- Leave ------------------------------------------------------------------------------

export function useLeaveOverview() {
  return useQuery({
    queryKey: ["leave", "me"],
    queryFn: ({ signal }) => apiFetch<LeaveOverview>("/api/leave/me", { signal }),
  });
}

export function useMyLeaveRequests(enabled: boolean) {
  return useQuery({
    queryKey: ["leave", "mine"],
    queryFn: ({ signal }) => apiFetch<LeaveRequest[]>("/api/leave/requests/mine", { signal }),
    enabled,
  });
}

export function useLeaveApprovals() {
  return useQuery({
    queryKey: ["leave", "approvals"],
    queryFn: ({ signal }) => apiFetch<LeaveRequest[]>("/api/leave/requests/approvals", { signal }),
  });
}

export function useLeaveRequestDetail(id: string, enabled: boolean) {
  return useQuery({
    queryKey: ["leave", "detail", id],
    queryFn: ({ signal }) => apiFetch<LeaveRequestDetail>(`/api/leave/requests/${id}`, { signal }),
    enabled,
  });
}

export function useSubmitLeave() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: NewLeaveRequest) =>
      apiFetch<LeaveRequest>("/api/leave/requests", { method: "POST", body: request }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["leave"] }),
  });
}

export function useLeaveDecision() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, action, comment }: { id: string; action: LeaveAction; comment?: string }) =>
      apiFetch<LeaveRequest>(`/api/leave/requests/${id}/${action.toLowerCase()}`, {
        method: "POST",
        body: action === "CANCEL" ? undefined : { comment: comment?.trim() ?? "" },
      }),
    // Balances, both lists and the timeline all change, so refresh everything leave-related.
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["leave"] }),
  });
}
