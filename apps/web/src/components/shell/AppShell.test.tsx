import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { AppShell } from "./AppShell";

describe("AppShell", () => {
  it("shows the organization id and renders the dashboard nav link", () => {
    render(
      <AppShell orgId="acme-demo">
        <p>page content</p>
      </AppShell>,
    );

    expect(screen.getByText("Org: acme-demo")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Dashboard" })).toHaveAttribute(
      "href",
      "/app/acme-demo/dashboard",
    );
  });

  it("renders children inside a labeled main landmark", () => {
    render(
      <AppShell orgId="acme-demo">
        <p>page content</p>
      </AppShell>,
    );

    const main = screen.getByRole("main");
    expect(main).toHaveAttribute("id", "main-content");
    expect(main).toHaveTextContent("page content");
  });
});
