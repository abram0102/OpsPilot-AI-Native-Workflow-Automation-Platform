import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { AppShell } from "./AppShell";

// The nav highlights the current section via usePathname, which has no App
// Router to read from under jsdom. A fixed path is enough here; the active
// state itself is asserted separately below.
vi.mock("next/navigation", () => ({
  usePathname: () => "/app/acme-demo/tickets",
}));

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

  it("marks only the section matching the current path as current", () => {
    render(
      <AppShell orgId="acme-demo">
        <p>page content</p>
      </AppShell>,
    );

    // The mocked pathname is /app/acme-demo/tickets.
    expect(screen.getByRole("link", { name: "Tickets" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Dashboard" })).not.toHaveAttribute("aria-current");
  });
});
