import { describe, expect, it } from "vitest";

import { workingDaysBetween } from "./dates";

describe("workingDaysBetween (mirrors WorkingDays.java)", () => {
  // 2026-11-23 is a Monday
  it.each([
    ["2026-11-23", "2026-11-23", 1], // a single Monday
    ["2026-11-23", "2026-11-27", 5], // Monday to Friday
    ["2026-11-28", "2026-11-29", 0], // just a weekend
    ["2026-11-27", "2026-11-30", 2], // Friday to Monday
    ["2026-11-23", "2026-12-04", 10], // two full weeks
  ])("%s to %s is %i working days", (start, end, expected) => {
    expect(workingDaysBetween(start, end)).toBe(expected);
  });

  it("is 0 while the form is incomplete or the range is backwards", () => {
    expect(workingDaysBetween("", "2026-11-23")).toBe(0);
    expect(workingDaysBetween("2026-11-27", "2026-11-23")).toBe(0);
  });
});
