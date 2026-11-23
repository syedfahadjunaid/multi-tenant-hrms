import type { ButtonHTMLAttributes, ReactNode } from "react";

/**
 * Small shared UI building blocks. Keeping them in one place gives the app a consistent
 * look without pulling in a component library.
 */

type ButtonVariant = "primary" | "secondary" | "ghost";

const BUTTON_STYLES: Record<ButtonVariant, string> = {
  primary: "bg-indigo-600 text-white hover:bg-indigo-500 disabled:bg-indigo-300",
  secondary: "bg-white text-slate-700 ring-1 ring-slate-300 hover:bg-slate-50 disabled:text-slate-400",
  ghost: "text-slate-600 hover:bg-slate-100 hover:text-slate-900 disabled:text-slate-300",
};

export function Button({
  variant = "primary",
  className = "",
  type = "button",
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: ButtonVariant }) {
  return (
    <button
      type={type}
      className={`inline-flex items-center justify-center gap-2 rounded-lg px-3.5 py-2 text-sm font-medium shadow-sm transition focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-600 disabled:cursor-not-allowed ${BUTTON_STYLES[variant]} ${className}`}
      {...props}
    />
  );
}

export function Card({ children, className = "" }: { children: ReactNode; className?: string }) {
  return (
    <section className={`rounded-xl bg-white p-5 shadow-sm ring-1 ring-slate-200 ${className}`}>
      {children}
    </section>
  );
}

export function Spinner({ label = "Loading" }: { label?: string }) {
  return (
    <span role="status" className="inline-flex items-center gap-2 text-sm text-slate-500">
      <span className="size-4 animate-spin rounded-full border-2 border-slate-300 border-t-indigo-600" />
      {label}
    </span>
  );
}

type AlertTone = "error" | "success" | "info";

const ALERT_STYLES: Record<AlertTone, string> = {
  error: "bg-red-50 text-red-800 ring-red-200",
  success: "bg-emerald-50 text-emerald-800 ring-emerald-200",
  info: "bg-sky-50 text-sky-800 ring-sky-200",
};

export function Alert({ tone, children }: { tone: AlertTone; children: ReactNode }) {
  return (
    <div role={tone === "error" ? "alert" : "status"} className={`rounded-lg px-4 py-3 text-sm ring-1 ${ALERT_STYLES[tone]}`}>
      {children}
    </div>
  );
}

export type BadgeTone = "slate" | "indigo" | "amber" | "green" | "red";

const BADGE_STYLES: Record<BadgeTone, string> = {
  slate: "bg-slate-100 text-slate-700 ring-slate-200",
  indigo: "bg-indigo-50 text-indigo-700 ring-indigo-200",
  amber: "bg-amber-50 text-amber-800 ring-amber-200",
  green: "bg-emerald-50 text-emerald-700 ring-emerald-200",
  red: "bg-red-50 text-red-700 ring-red-200",
};

export function Badge({ children, tone = "slate" }: { children: ReactNode; tone?: BadgeTone }) {
  return (
    <span className={`inline-flex items-center rounded-md px-2 py-0.5 text-xs font-medium ring-1 ${BADGE_STYLES[tone]}`}>
      {children}
    </span>
  );
}

export function PageHeader({
  title,
  description,
  actions,
}: {
  title: string;
  description?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
        {description && <p className="mt-1 text-sm text-slate-500">{description}</p>}
      </div>
      {actions && <div className="flex gap-2">{actions}</div>}
    </div>
  );
}

/** A labelled form control with an optional hint and error message underneath. */
export function Field({
  label,
  htmlFor,
  hint,
  error,
  children,
}: {
  label: string;
  htmlFor: string;
  hint?: string;
  error?: string;
  children: ReactNode;
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={htmlFor} className="text-sm font-medium text-slate-700">
        {label}
      </label>
      {children}
      {error ? (
        <p id={`${htmlFor}-error`} className="text-xs text-red-600">
          {error}
        </p>
      ) : (
        hint && <p className="text-xs text-slate-500">{hint}</p>
      )}
    </div>
  );
}

export const inputClass =
  "block w-full rounded-lg border-0 px-3 py-2 text-sm text-slate-900 shadow-sm ring-1 ring-slate-300 placeholder:text-slate-400 focus:ring-2 focus:ring-indigo-600 focus:outline-none aria-invalid:ring-red-400";

export function FullPageMessage({
  title,
  message,
  busy = false,
  children,
}: {
  title: string;
  message?: string;
  busy?: boolean;
  children?: ReactNode;
}) {
  return (
    <main className="flex min-h-screen items-center justify-center p-6">
      <div className="max-w-md text-center">
        {busy && <span className="mx-auto mb-4 block size-8 animate-spin rounded-full border-4 border-slate-200 border-t-indigo-600" />}
        <h1 className="text-lg font-semibold">{title}</h1>
        {message && <p className="mt-2 text-sm text-slate-600">{message}</p>}
        {children && <div className="mt-6">{children}</div>}
      </div>
    </main>
  );
}
