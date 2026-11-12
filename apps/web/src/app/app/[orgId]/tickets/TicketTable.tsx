"use client";

import { useState } from "react";
import Link from "next/link";
import { StatusBadge } from "@/components/ui/StatusBadge";
import ui from "@/styles/ui.module.css";
import type { Ticket } from "@/lib/api/tickets";
import styles from "./TicketTable.module.css";

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

  if (tickets.length === 0) {
    return (
      <div className={ui.empty}>
        <p>No tickets match the current filters.</p>
      </div>
    );
  }

  return (
    <div className={ui.tableCard}>
      <div className={styles.tableToolbar}>
        <div className={ui.field}>
          <label htmlFor="ticket-filter">Filter visible tickets by subject</label>
          <input
            id="ticket-filter"
            type="search"
            value={filterText}
            placeholder="Subject contains…"
            onChange={(event) => setFilterText(event.target.value)}
          />
        </div>
      </div>

      <div className={ui.tableScroll}>
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
                <td className={styles.subjectCell}>
                  <Link href={`/app/${orgId}/tickets/${ticket.id}`}>{ticket.subject}</Link>
                </td>
                <td>
                  <StatusBadge value={ticket.status} />
                </td>
                <td>
                  <StatusBadge value={ticket.priority} />
                </td>
                <td className={styles.timeCell}>{new Date(ticket.updatedAt).toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {visible.length === 0 && (
        <p className={styles.noMatch}>No tickets on this page match &quot;{filterText}&quot;.</p>
      )}
    </div>
  );
}
