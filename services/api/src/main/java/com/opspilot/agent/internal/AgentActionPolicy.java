package com.opspilot.agent.internal;

import com.opspilot.agent.AgentActionType;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The authoritative, model-independent policy for agent actions (docs/TASKS.md T21).
 *
 * <p>T19 already gates proposals in the Python worker, and this is deliberately a *second*
 * implementation rather than a shared one. docs/SECURITY_AND_TESTING.md requires "independent
 * server-side policy", and the word is doing work: the Python gate stops a bad proposal being made,
 * while this stops a bad action being executed, and it must hold even if the worker were bypassed,
 * compromised, buggy or simply an older version. Nothing here trusts that a proposal was ever
 * gated.
 *
 * <p>Pure functions over the request's own arguments — no database, no model, no configuration that
 * a caller could influence.
 */
final class AgentActionPolicy {

  /** Mirrors the worker's rule, independently. Lowercase words, single hyphens, nothing else. */
  private static final Pattern TAG_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

  private static final int MAX_TAG_LENGTH = 40;

  /**
   * The same set the worker allows. Duplicated on purpose: a shared constant would mean a change on
   * one side silently widened the other, and this copy is the one that guards the write.
   */
  private static final Set<String> ALLOWED_TAGS =
      Set.of(
          "shipping", "billing", "account", "other", "urgent", "needs-human", "awaiting-customer");

  private AgentActionPolicy() {}

  /** Whether an action requires a human decision before it may be executed at all. */
  static boolean requiresApproval(AgentActionType actionType) {
    // docs/PRODUCT_AND_ARCHITECTURE.md U07: a safe internal tag may be automatic; publishing a
    // drafted response requires ADMIN/OWNER approval. There is no confidence score, category or
    // caller that changes this answer.
    return actionType == AgentActionType.PUBLISH_INTERNAL_REPLY;
  }

  /**
   * Validates an action's arguments, returning a rejection reason or {@code null} when acceptable.
   *
   * <p>Returns a reason rather than throwing so the caller can record a {@code REJECTED} outcome —
   * a refused action is a normal, auditable result, not an exception.
   */
  static String rejectionReason(AgentActionType actionType, Map<String, String> arguments) {
    return switch (actionType) {
      case ADD_INTERNAL_TAG -> tagRejectionReason(arguments);
      case PUBLISH_INTERNAL_REPLY -> publishRejectionReason(arguments);
    };
  }

  private static String tagRejectionReason(Map<String, String> arguments) {
    if (arguments == null || arguments.size() != 1 || !arguments.containsKey("tag")) {
      return "ADD_INTERNAL_TAG takes exactly one argument, 'tag'.";
    }
    String tag = arguments.get("tag");
    if (tag == null || tag.isBlank() || tag.length() > MAX_TAG_LENGTH) {
      return "A tag must be between 1 and " + MAX_TAG_LENGTH + " characters.";
    }
    if (!TAG_PATTERN.matcher(tag).matches()) {
      return "A tag must be lowercase words separated by single hyphens.";
    }
    if (!ALLOWED_TAGS.contains(tag)) {
      // A well-formed tag is still not a permitted one; otherwise an agent could invent its own
      // taxonomy one ticket at a time.
      return "That tag is not on the allowlist.";
    }
    return null;
  }

  private static String publishRejectionReason(Map<String, String> arguments) {
    // docs/API_CONTRACT.md: "arguments {approvalId: uuid} only; content taken from immutable
    // approved proposal, not worker request". An argument beyond the id is an attempt to supply
    // the very thing approval exists to pin down.
    if (arguments == null || arguments.size() != 1 || !arguments.containsKey("approvalId")) {
      return "PUBLISH_INTERNAL_REPLY takes exactly one argument, 'approvalId'.";
    }
    if (arguments.get("approvalId") == null || arguments.get("approvalId").isBlank()) {
      return "'approvalId' is required.";
    }
    return null;
  }
}
