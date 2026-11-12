import Link from "next/link";
import ui from "@/styles/ui.module.css";

/**
 * Shown for a run id that does not resolve inside this organization — whether it never existed or
 * belongs to another tenant. Those two cases are deliberately indistinguishable here, because the
 * API refuses to distinguish them: confirming that an id exists elsewhere would itself leak.
 */
export default function RunNotFound() {
  return (
    <div className={ui.pageNarrow}>
      <h1>No such run</h1>
      <p className={ui.lead}>
        This run does not exist in this organization. It may have been started in a different
        workspace.
      </p>
      <p>
        <Link href="../">← Back to runs</Link>
      </p>
    </div>
  );
}
