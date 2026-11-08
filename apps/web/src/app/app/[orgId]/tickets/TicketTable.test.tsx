import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { TicketTable } from "./TicketTable";
import type { Ticket } from "@/lib/api/tickets";

function ticket(overrides: Partial<Ticket>): Ticket {
  return {
    id: "ticket-1",
    organizationId: "org-1",
    customerId: "customer-1",
    subject: "Where is my order?",
    description: "d",
    status: "OPEN",
    priority: "MEDIUM",
    createdBy: "user-1",
    version: 0,
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
    ...overrides,
  };
}

describe("TicketTable", () => {
  it("shows an empty state when there are no tickets", () => {
    render(<TicketTable orgId="acme" tickets={[]} />);
    expect(screen.getByText("No tickets match the current filters.")).toBeInTheDocument();
  });

  it("renders each ticket as a link to its detail page", () => {
    render(
      <TicketTable
        orgId="acme"
        tickets={[ticket({ id: "t1", subject: "Billing question" })]}
      />,
    );
    expect(screen.getByRole("link", { name: "Billing question" })).toHaveAttribute(
      "href",
      "/app/acme/tickets/t1",
    );
  });

  it("filters visible rows by subject as the user types, without hiding the rest from the page data", () => {
    render(
      <TicketTable
        orgId="acme"
        tickets={[
          ticket({ id: "t1", subject: "Billing question" }),
          ticket({ id: "t2", subject: "Shipping delay" }),
        ]}
      />,
    );

    fireEvent.change(screen.getByLabelText("Filter visible tickets by subject"), {
      target: { value: "billing" },
    });

    expect(screen.getByText("Billing question")).toBeInTheDocument();
    expect(screen.queryByText("Shipping delay")).not.toBeInTheDocument();
  });

  it("shows a distinct message when the filter excludes every loaded ticket", () => {
    render(<TicketTable orgId="acme" tickets={[ticket({ subject: "Billing question" })]} />);

    fireEvent.change(screen.getByLabelText("Filter visible tickets by subject"), {
      target: { value: "nothing matches this" },
    });

    expect(
      screen.getByText('No tickets on this page match "nothing matches this".'),
    ).toBeInTheDocument();
  });
});
