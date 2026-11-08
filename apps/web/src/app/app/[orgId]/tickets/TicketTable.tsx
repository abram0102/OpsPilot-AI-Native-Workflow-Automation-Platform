"use client";

import { useState } from "react";
import Link from "next/link";
import type { Ticket } from "@/lib/api/tickets";

interface TicketTableProps {
  orgId: string;
  tickets: Ticket[];
}

/**
 * Client-side text filter over the currently loaded page only — the API has no full-text search
 * endpoint yet (docs/API_CONTRACT.md's ticket list only takes `status`/`priority`/`cursor`/`limit`),
 * so this is deliberately labeled "Filter visible tickets", not "Search", to avoid implying it
 * searches tickets outside the current page.
 */
export function TicketTable({ orgId, tickets }: TicketTableProps) {
  const [filterText, setFilterText] = useState("");

  const visible = tickets.filter((ticket) =>
    ticket.subject.toLowerCase().includes(filterText.toLowerCase()),
  );

  return (
    <div>
      <label htmlFor="ticket-filter">Filter visible tickets by subject</label>
      <br />
      <input
        id="ticket-filter"
        type="search"
        value={filterText}
        onChange={(event) => setFilterText(event.target.value)}
      />
      {tickets.length === 0 ? (
        <p>No tickets match the current filters.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Subject</th>
              <th>Status</th>
              <th>Priority</th>
              <th>Updated</th>
            </tr>
          </thead>
          <tbody>
            {visible.map((ticket) => (
              <tr key={ticket.id}>
                <td>
                  <Link href={`/app/${orgId}/tickets/${ticket.id}`}>{ticket.subject}</Link>
                </td>
                <td>{ticket.status}</td>
                <td>{ticket.priority}</td>
                <td>{new Date(ticket.updatedAt).toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {tickets.length > 0 && visible.length === 0 && (
        <p>No tickets on this page match &quot;{filterText}&quot;.</p>
      )}
    </div>
  );
}
