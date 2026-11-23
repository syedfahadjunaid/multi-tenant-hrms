import { type FormEvent, useState } from "react";

import { ApiError } from "../../api/client";
import { useSubmitLeave } from "../../api/hooks";
import { LEAVE_TYPE_LABELS, type LeaveBalance, type LeaveType } from "../../api/types";
import { Alert, Button, Field, inputClass } from "../../components/ui";
import { todayIso, workingDaysBetween } from "../../lib/dates";

export function LeaveRequestForm({ balance }: { balance: LeaveBalance | null }) {
  const submit = useSubmitLeave();
  const [type, setType] = useState<LeaveType>("ANNUAL");
  const [startDate, setStartDate] = useState("");
  const [endDate, setEndDate] = useState("");
  const [reason, setReason] = useState("");
  const apiError = submit.error instanceof ApiError ? submit.error : null;

  const days = workingDaysBetween(startDate, endDate);
  const remainingAfter = balance && type === "ANNUAL" ? balance.remainingDays - days : null;

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    submit.mutate(
      { type, startDate, endDate, reason },
      {
        onSuccess: () => {
          setStartDate("");
          setEndDate("");
          setReason("");
        },
      },
    );
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <Field label="Type" htmlFor="leave-type">
        <select id="leave-type" className={inputClass} value={type} onChange={(e) => setType(e.target.value as LeaveType)}>
          {(Object.keys(LEAVE_TYPE_LABELS) as LeaveType[]).map((t) => (
            <option key={t} value={t}>
              {LEAVE_TYPE_LABELS[t]}
            </option>
          ))}
        </select>
      </Field>

      <div className="grid grid-cols-2 gap-3">
        <Field label="From" htmlFor="leave-start" error={apiError?.messageFor("startDate")}>
          <input
            id="leave-start"
            type="date"
            required
            className={inputClass}
            min={type === "ANNUAL" ? todayIso() : undefined}
            value={startDate}
            onChange={(e) => {
              setStartDate(e.target.value);
              if (!endDate || endDate < e.target.value) setEndDate(e.target.value);
            }}
            aria-invalid={apiError?.messageFor("startDate") ? true : undefined}
          />
        </Field>
        <Field label="To" htmlFor="leave-end" error={apiError?.messageFor("endDate")}>
          <input
            id="leave-end"
            type="date"
            required
            className={inputClass}
            min={startDate || undefined}
            value={endDate}
            onChange={(e) => setEndDate(e.target.value)}
            aria-invalid={apiError?.messageFor("endDate") ? true : undefined}
          />
        </Field>
      </div>

      <Field label="Reason (optional)" htmlFor="leave-reason">
        <textarea
          id="leave-reason"
          rows={2}
          maxLength={500}
          className={inputClass}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
        />
      </Field>

      {startDate && endDate && (
        <p className="text-sm text-slate-600" aria-live="polite">
          {days} {days === 1 ? "working day" : "working days"}
          {remainingAfter !== null && ` · ${remainingAfter} days of annual leave left afterwards`}
        </p>
      )}

      {apiError && apiError.fieldErrors.length === 0 && <Alert tone="error">{apiError.message}</Alert>}
      {submit.isSuccess && <Alert tone="success">Request sent for approval.</Alert>}

      <Button type="submit" className="w-full" disabled={submit.isPending}>
        {submit.isPending ? "Sending…" : "Request leave"}
      </Button>
    </form>
  );
}
