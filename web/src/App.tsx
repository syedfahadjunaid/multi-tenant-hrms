import { useState } from "react";
import { createBrowserRouter, type RouteObject, RouterProvider } from "react-router";

import { Layout } from "./components/Layout";
import { RequireRole } from "./components/RequireRole";
import { AssistantPage } from "./pages/AssistantPage";
import { EmployeesPage } from "./pages/EmployeesPage";
import { HomePage } from "./pages/HomePage";
import { LeavePage } from "./pages/leave/LeavePage";
import { NotFoundPage, RouteErrorPage } from "./pages/ErrorPages";

const routes: RouteObject[] = [
  {
    element: <Layout />,
    errorElement: <RouteErrorPage />,
    children: [
      { index: true, element: <HomePage /> },
      {
        path: "employees",
        element: (
          <RequireRole anyOf={["HR_ADMIN", "MANAGER"]}>
            <EmployeesPage />
          </RequireRole>
        ),
      },
      { path: "leave", element: <LeavePage /> },
      { path: "assistant", element: <AssistantPage /> },
      { path: "*", element: <NotFoundPage /> },
    ],
  },
];

export function App() {
  // Created only after sign-in, so the router starts from the URL we returned to
  // (not Keycloak's ?code=... callback URL).
  const [router] = useState(() => createBrowserRouter(routes));
  return <RouterProvider router={router} />;
}
