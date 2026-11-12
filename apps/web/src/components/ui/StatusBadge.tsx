import ui from "@/styles/ui.module.css";

/**
 * A status or priority rendered as a coloured pill.
 *
 * Colour is an *additional* cue, never the only one: the badge always spells
 * the status out, so it still reads correctly in monochrome, to a screen
 * reader, and for anyone who can't distinguish the hues
 * (docs/PRODUCT_AND_ARCHITECTURE.md section 6).
 *
 * Any value not in the table below falls back to the neutral pill rather than
 * being dropped or guessed at — a status this UI hasn't been taught about
 * should still appear, plainly.
 */
const TONES: Record<string, string | undefined> = {
  // Ticket status
  OPEN: ui.badgeInfo,
  IN_PROGRESS: ui.badgeWarning,
  RESOLVED: ui.badgeSuccess,
  CLOSED: ui.badge,

  // Approval status
  PENDING: ui.badgeWarning,
  APPROVED: ui.badgeSuccess,
  REJECTED: ui.badgeDanger,
  EXPIRED: ui.badge,

  // Agent run status
  QUEUED: ui.badge,
  RUNNING: ui.badgeInfo,
  AWAITING_APPROVAL: ui.badgeWarning,
  SUCCEEDED: ui.badgeSuccess,
  FAILED: ui.badgeDanger,
  CANCELLED: ui.badge,

  // Ticket priority
  LOW: ui.badge,
  MEDIUM: ui.badgeInfo,
  HIGH: ui.badgeDanger,

  // Membership role
  OWNER: ui.badgeAccent,
  ADMIN: ui.badgeInfo,
  MEMBER: ui.badge,
  VIEWER: ui.badge,
};

export function StatusBadge({ value }: { value: string }) {
  return <span className={TONES[value] ?? ui.badge}>{value.replaceAll("_", " ")}</span>;
}
