import { useState } from "react";

import { useLeaveDecision, useLeaveRequestDetail } from "../../api/hooks";
import { type HistoryEntry, LEAVE_TYPE_LABELS, type LeaveRequest, type LeaveStatus } from "../../api/types";
import { Alert, Badge, type BadgeTone, Button, Card, Spinner, inputClass } from "../../components/ui";
import { formatDateRange, formatDateTime } from "../../lib/dates";

const STATUS: Record<LeaveStatus, { label: string; tone: BadgeTone }> = {
  PENDING_MANAGER: { label: "Waiting for manager", tone: "amber" },
  PENDING_HR: { label: "Waiting for HR", tone: "amber" },
  APPROVED: { label: "Approved", tone: "green" },
  REJECTED: { label: "Rejected", tone: "red" },
  CANCELLED: { label: "Cancelled", tone: "slate" },
};

export function StatusBadge({ status }: { status: LeaveStatus }) {
  return <Badge tone={STATUS[status].tone}>{STATUS[status].label}</Badge>;
}

/**
 * One leave request. The buttons come straight from `allowedActions`, which the server
 * computes for the signed-in user, so the UI never guesses at permissions.
 */
export function LeaveRequestCard({ request, showEmployee = false }: { request: LeaveRequest; showEmployee?: boolean }) {
  const [showHistory, setShowHistory] = useState(false);
  const can = (action: LeaveRequest["allowedActions"][number]) => request.allowedActions.includes(action);
  const canDecide = can("APPROVE") || can("REJECT");

  return (
    <Card>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h3 className="font-semibold">
            {showEmployee ? `${request.employee.name} · ` : ""}
            {LEAVE_TYPE_LABELS[request.type]}
          </h3>
          <p className="mt-0.5 text-sm text-slate-600">
            {formatDateRange(request.startDate, request.endDate)} · {request.workingDays}{" "}
            {request.workingDays === 1 ? "working day" : "working days"}
          </p>
        </div>
        <StatusBadge status={request.status} />
      </div>

      {request.reason && <p className="mt-3 text-sm whitespace-pre-wrap text-slate-700">“{request.reason}”</p>}

      {request.status === "PENDING_MANAGER" && request.approver && !canDecide && (
        <p className="mt-3 text-xs text-slate-500">Waiting for {request.approver.name} to approve.</p>
      )}

      {canDecide && <DecisionForm request={request} />}

      <div className="mt-4 flex flex-wrap items-center gap-2">
        {can("CANCEL") && <CancelButton id={request.id} />}
        <Button variant="ghost" onClick={() => setShowHistory((v) => !v)} aria-expanded={showHistory}>
          {showHistory ? "Hide history" : "Show history"}
        </Button>
      </div>

      {showHistory && <History id={request.id} />}
    </Card>
  );
}

/** Approve with an optional note, or reject with a required reason. */
function DecisionForm({ request }: { request: LeaveRequest }) {
  const decision = useLeaveDecision();
  const [comment, setComment] = useState("");
  const commentId = `comment-${request.id}`;
  const error = decision.error;

  return (
    <div className="mt-4 space-y-2 rounded-lg bg-slate-50 p-3 ring-1 ring-slate-200">
      <label htmlFor={commentId} className="text-xs font-medium text-slate-600">
        Comment (required to reject)
      </label>
      <textarea
        id={commentId}
        rows={2}
        maxLength={500}
        value={comment}
        onChange={(e) => setComment(e.target.value)}
        className={inputClass}
      />
      {error && <Alert tone="error">{error.message}</Alert>}
      <div className="flex gap-2">
        {request.allowedActions.includes("APPROVE") && (
          <Button
            disabled={decision.isPending}
            onClick={() => decision.mutate({ id: request.id, action: "APPROVE", comment })}
          >
            Approve
          </Button>
        )}
        {request.allowedActions.includes("REJECT") && (
          <Button
            variant="secondary"
            disabled={decision.isPending || !comment.trim()}
            title={comment.trim() ? undefined : "Add a comment explaining why"}
            onClick={() => decision.mutate({ id: request.id, action: "REJECT", comment })}
          >
            Reject
          </Button>
        )}
      </div>
    </div>
  );
}

function CancelButton({ id }: { id: string }) {
  const decision = useLeaveDecision();
  return (
    <>
      <Button
        variant="secondary"
        disabled={decision.isPending}
        onClick={() => {
          if (window.confirm("Cancel this leave request? This can't be undone.")) {
            decision.mutate({ id, action: "CANCEL" });
          }
        }}
      >
        {decision.isPending ? "Cancelling…" : "Cancel request"}
      </Button>
      {decision.error && <span className="text-xs text-red-600">{decision.error.message}</span>}
    </>
  );
}

const VERBS: Record<HistoryEntry["action"], string> = {
  SUBMIT: "submitted the request",
  REASSIGN: "changed who approves it",
  APPROVE: "approved",
  REJECT: "rejected",
  CANCEL: "cancelled",
};

function History({ id }: { id: string }) {
  const detail = useLeaveRequestDetail(id, true);
  if (detail.isPending) return <div className="mt-3"><Spinner label="Loading history" /></div>;
  if (detail.isError) return <div className="mt-3"><Alert tone="error">{detail.error.message}</Alert></div>;

  return (
    <ol className="mt-4 space-y-3 border-l-2 border-slate-200 pl-4">
      {detail.data.history.map((entry, i) => (
        <li key={i} className="text-sm">
          <p>
            <span className="font-medium">{entry.actorName}</span> {VERBS[entry.action]}
            <span className="text-slate-500"> · {formatDateTime(entry.occurredAt)}</span>
          </p>
          {entry.comment && <p className="mt-0.5 text-slate-600">“{entry.comment}”</p>}
        </li>
      ))}
    </ol>
  );
}
