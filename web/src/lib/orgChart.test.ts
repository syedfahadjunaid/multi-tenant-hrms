import { describe, expect, it } from "vitest";

import type { Employee } from "../api/types";
import { eligibleManagers } from "./orgChart";

function person(id: string, managerId: string | null): Employee {
  return {
    id,
    managerId,
    managerName: null,
    employeeCode: id,
    firstName: id,
    lastName: "",
    email: `${id}@acme.test`,
    department: null,
    jobTitle: null,
    hireDate: "2024-01-01",
    createdAt: "2024-01-01T00:00:00Z",
  };
}

//  ann
//  ├─ ben
//  │  └─ cat
//  └─ dan
//  eve (no manager)
const ORG = [person("ann", null), person("ben", "ann"), person("cat", "ben"), person("dan", "ann"), person("eve", null)];

const ids = (employees: Employee[]) => employees.map((e) => e.id).sort();

describe("eligibleManagers", () => {
  it("excludes the employee and everyone below them", () => {
    expect(ids(eligibleManagers("ann", ORG))).toEqual(["eve"]);
    expect(ids(eligibleManagers("ben", ORG))).toEqual(["ann", "dan", "eve"]);
  });

  it("allows anyone for someone at the bottom of the chart", () => {
    expect(ids(eligibleManagers("cat", ORG))).toEqual(["ann", "ben", "dan", "eve"]);
  });
});
