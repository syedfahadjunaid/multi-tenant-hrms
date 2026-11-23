import type { ReactNode } from "react";
import { useSearchParams } from "react-router";

import { useCurrentUser, useLeaveApprovals, useLeaveOverview, useMyLeaveRequests } from "../../api/hooks";
import type { LeaveBalance, LeaveOverview } from "../../api/types";
import { Alert, Card, PageHeader, Spinner } from "../../components/ui";
import { LeaveRequestCard } from "./LeaveRequestCard";
import { LeaveRequestForm } from "./LeaveRequestForm";

type Tab = "mine" | "approvals";

export function LeavePage() {
  const user = useCurrentUser();
  const approvals = useLeaveApprovals();
  const [params, setParams] = useSearchParams();

  const waiting = approvals.data?.length ?? 0;
  const showApprovalsTab = user.hasAnyRole("MANAGER", "HR_ADMIN") || waiting > 0;
  const tab: Tab = params.get("tab") === "approvals" && showApprovalsTab ? "approvals" : "mine";

  return (
    <>
      <PageHeader title="Leave" description="Request time off and follow it through approval." />

      {showApprovalsTab && (
        <div role="tablist" aria-label="Leave" className="mb-6 flex gap-1 border-b border-slate-200">
          <TabButton active={tab === "mine"} onClick={() => setParams({})}>
            My leave
          </TabButton>
          <TabButton active={tab === "approvals"} onClick={() => setParams({ tab: "approvals" })}>
            Approvals{waiting > 0 && <span className="ml-1.5 rounded-full bg-indigo-600 px-1.5 text-xs text-white">{waiting}</span>}
          </TabButton>
        </div>
      )}

      {tab === "mine" ? <MyLeave /> : <Approvals />}
    </>
  );
}

function TabButton({ active, onClick, children }: { active: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button
      role="tab"
      aria-selected={active}
      onClick={onClick}
      className={`-mb-px border-b-2 px-4 py-2 text-sm font-medium ${
        active ? "border-indigo-600 text-indigo-700" : "border-transparent text-slate-500 hover:text-slate-800"
      }`}
    >
      {children}
    </button>
  );
}

function MyLeave() {
  const overview = useLeaveOverview();
  const linked = Boolean(overview.data?.employee);
  const mine = useMyLeaveRequests(linked);

  if (overview.isPending) return <Spinner />;
  if (overview.isError) return <Alert tone="error">{overview.error.message}</Alert>;
  if (!linked) {
    return (
      <Alert tone="info">
        Your account isn't linked to an employee record yet, so you can't request leave. Ask HR to add you
        on the Employees page with the same work email you sign in with.
      </Alert>
    );
  }

  return (
    <div className="grid gap-6 lg:grid-cols-3">
      <div className="space-y-6">
        <BalanceCard overview={overview.data} />
        <Card>
          <h2 className="mb-4 font-semibold">Request leave</h2>
          <LeaveRequestForm balance={overview.data.balance} />
        </Card>
      </div>

      <div className="space-y-4 lg:col-span-2">
        <h2 className="font-semibold">My requests</h2>
        {mine.isPending ? (
          <Spinner />
        ) : mine.isError ? (
          <Alert tone="error">{mine.error.message}</Alert>
        ) : mine.data.length === 0 ? (
          <p className="text-sm text-slate-500">You haven't requested any leave yet.</p>
        ) : (
          mine.data.map((request) => <LeaveRequestCard key={request.id} request={request} />)
        )}
      </div>
    </div>
  );
}

function BalanceCard({ overview }: { overview: LeaveOverview }) {
  const balance: LeaveBalance | null = overview.balance;
  if (!balance) return null;
  const usedPercent = Math.min(100, (balance.usedDays / balance.allowanceDays) * 100);
  const pendingPercent = Math.min(100 - usedPercent, (balance.pendingDays / balance.allowanceDays) * 100);

  return (
    <Card>
      <h2 className="text-sm font-semibold text-slate-500">Annual leave {balance.year}</h2>
      <p className="mt-2 text-3xl font-semibold">
        {balance.remainingDays}
        <span className="text-base font-normal text-slate-500"> of {balance.allowanceDays} days left</span>
      </p>
      <div
        className="mt-3 flex h-2 overflow-hidden rounded-full bg-slate-100"
        role="img"
        aria-label={`${balance.usedDays} days used, ${balance.pendingDays} pending`}
      >
        <div className="bg-indigo-600" style={{ width: `${usedPercent}%` }} />
        <div className="bg-amber-400" style={{ width: `${pendingPercent}%` }} />
      </div>
      <p className="mt-2 text-xs text-slate-500">
        {balance.usedDays} used · {balance.pendingDays} waiting for approval
      </p>
      <p className="mt-3 text-xs text-slate-500">
        Approvals: {overview.manager ? `${overview.manager.name}, then HR` : "HR"}
      </p>
    </Card>
  );
}

function Approvals() {
  const approvals = useLeaveApprovals();
  if (approvals.isPending) return <Spinner />;
  if (approvals.isError) return <Alert tone="error">{approvals.error.message}</Alert>;
  if (approvals.data.length === 0) {
    return <p className="text-sm text-slate-500">Nothing is waiting for you. 🎉</p>;
  }
  return (
    <div className="space-y-4">
      {approvals.data.map((request) => (
        <LeaveRequestCard key={request.id} request={request} showEmployee />
      ))}
    </div>
  );
}
