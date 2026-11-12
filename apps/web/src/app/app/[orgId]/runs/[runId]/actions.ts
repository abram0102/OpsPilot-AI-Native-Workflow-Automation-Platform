"use server";

import { revalidatePath } from "next/cache";
import { ApiError, cancelRun } from "@/lib/api/runs";

export interface CancelRunState {
  error?: string;
  /** The run had already finished — a conflict, not a failure. */
  conflict?: boolean;
  cancelled?: boolean;
}

/**
 * Backs the cancel button on a run page.
 *
 * <p>A 409 means the run reached a terminal state between the page rendering and the click. That
 * is reported as "it already finished", not as an error, because the reviewer's next step is to
 * look at the outcome rather than to retry — and because reporting a generic failure for a run
 * that genuinely completed would be the misleading result T27's acceptance criteria rule out.
 */
export async function cancelRunAction(
  orgId: string,
  runId: string,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  _prevState: CancelRunState,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  _formData: FormData,
): Promise<CancelRunState> {
  try {
    await cancelRun(orgId, runId);
    revalidatePath(`/app/${orgId}/runs`);
    revalidatePath(`/app/${orgId}/runs/${runId}`);
    return { cancelled: true };
  } catch (error) {
    if (error instanceof ApiError) {
      if (error.status === 409) {
        revalidatePath(`/app/${orgId}/runs/${runId}`);
        return {
          conflict: true,
          error: "This run already reached a final state. Reload to see its outcome.",
        };
      }
      return { error: error.message };
    }
    throw error;
  }
}
