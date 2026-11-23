/** Types mirroring the core API's JSON (see the Java DTO records). */

export type Role = "HR_ADMIN" | "MANAGER" | "EMPLOYEE";
export const ALL_ROLES: readonly Role[] = ["HR_ADMIN", "MANAGER", "EMPLOYEE"];

export const ROLE_LABELS: Record<Role, string> = {
  HR_ADMIN: "HR admin",
  MANAGER: "Manager",
  EMPLOYEE: "Employee",
};

export type Me = {
  subject: string;
  username: string;
  displayName: string;
  firstName: string;
  tenantId: string;
  roles: string[];
};

export type Page<T> = {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
};

export type Employee = {
  id: string;
  employeeCode: string;
  firstName: string;
  lastName: string;
  email: string;
  department: string | null;
  jobTitle: string | null;
  hireDate: string;
  managerId: string | null;
  managerName: string | null;
  createdAt: string;
};

export type NewEmployee = Omit<Employee, "id" | "createdAt" | "managerName">;

export type Source = {
  documentId: string;
  title: string;
  score: number;
};

export type AskResponse = {
  answer: string;
  sources: Source[];
  /** A model id, "offline" (no API key configured) or "none" (no matching documents). */
  model: string;
};

export type NewDocument = {
  title: string;
  content: string;
  allowedRoles: Role[];
};

export type AddDocumentResponse = {
  documentId: string;
  chunks: number;
};

// ---- Leave ------------------------------------------------------------------------------

export type LeaveType = "ANNUAL" | "SICK" | "UNPAID";
export type LeaveStatus = "PENDING_MANAGER" | "PENDING_HR" | "APPROVED" | "REJECTED" | "CANCELLED";
export type LeaveAction = "APPROVE" | "REJECT" | "CANCEL";

export const LEAVE_TYPE_LABELS: Record<LeaveType, string> = {
  ANNUAL: "Annual leave",
  SICK: "Sick leave",
  UNPAID: "Unpaid leave",
};

export type EmployeeRef = { id: string; name: string };

export type LeaveBalance = {
  year: number;
  allowanceDays: number;
  usedDays: number;
  pendingDays: number;
  remainingDays: number;
};

/** `employee` is null when the signed-in account isn't linked to an employee record yet. */
export type LeaveOverview = {
  employee: EmployeeRef | null;
  manager: EmployeeRef | null;
  balance: LeaveBalance | null;
};

export type LeaveRequest = {
  id: string;
  employee: EmployeeRef;
  approver: EmployeeRef | null;
  type: LeaveType;
  startDate: string;
  endDate: string;
  workingDays: number;
  reason: string | null;
  status: LeaveStatus;
  createdAt: string;
  /** What the signed-in user may do with this request, decided by the server. */
  allowedActions: LeaveAction[];
};

export type HistoryEntry = {
  /** REASSIGN: the employee's manager changed while the request waited for the old one. */
  action: "SUBMIT" | "REASSIGN" | LeaveAction;
  fromStatus: LeaveStatus | null;
  toStatus: LeaveStatus;
  actorName: string;
  comment: string | null;
  occurredAt: string;
};

export type LeaveRequestDetail = { request: LeaveRequest; history: HistoryEntry[] };

export type NewLeaveRequest = {
  type: LeaveType;
  startDate: string;
  endDate: string;
  reason: string;
};
