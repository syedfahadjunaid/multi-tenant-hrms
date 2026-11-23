/**
 * Date helpers. API dates like "2026-11-23" are calendar dates without a time zone, so they're
 * parsed and formatted in UTC: formatting them in local time can shift them by a day.
 */

const utc = (isoDate: string) => new Date(`${isoDate}T00:00:00Z`);

export function formatDate(isoDate: string): string {
  return utc(isoDate).toLocaleDateString(undefined, { dateStyle: "medium", timeZone: "UTC" });
}

export function formatDateRange(start: string, end: string): string {
  if (start === end) return formatDate(start);
  const options: Intl.DateTimeFormatOptions = { weekday: "short", day: "numeric", month: "short", timeZone: "UTC" };
  const from = utc(start).toLocaleDateString(undefined, options);
  const to = utc(end).toLocaleDateString(undefined, { ...options, year: "numeric" });
  return `${from} – ${to}`;
}

export function formatDateTime(isoInstant: string): string {
  return new Date(isoInstant).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}

/** Today in the user's time zone, as "YYYY-MM-DD" (for date inputs). */
export function todayIso(): string {
  const now = new Date();
  return new Date(now.getTime() - now.getTimezoneOffset() * 60_000).toISOString().slice(0, 10);
}

/**
 * Monday-to-Friday days from start to end, both inclusive. Mirrors WorkingDays.java; the
 * server's count is the one that counts, this is only for the live preview in the form.
 */
export function workingDaysBetween(start: string, end: string): number {
  if (!start || !end || end < start) return 0;
  const first = utc(start);
  const last = utc(end);
  if ((last.getTime() - first.getTime()) / 86_400_000 > 366) return 0;

  let count = 0;
  for (const day = first; day <= last; day.setUTCDate(day.getUTCDate() + 1)) {
    const weekday = day.getUTCDay();
    if (weekday !== 0 && weekday !== 6) count++;
  }
  return count;
}
