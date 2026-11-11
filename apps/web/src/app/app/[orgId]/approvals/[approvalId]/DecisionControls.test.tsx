import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { DecisionControls } from "./DecisionControls";
import type { DecisionState } from "./actions";

type DecisionAction = (prevState: DecisionState, formData: FormData) => Promise<DecisionState>;

const noop = () => vi.fn<DecisionAction>(async () => ({}));

function renderControls(
  approve: ReturnType<typeof noop> = noop(),
  reject: ReturnType<typeof noop> = noop(),
) {
  render(<DecisionControls approve={approve} reject={reject} />);
  return {
    approve,
    reject,
    approveButton: () => screen.getByRole("button", { name: /Approve/ }),
    rejectButton: () => screen.getByRole("button", { name: /Reject/ }),
  };
}

describe("DecisionControls", () => {
  it("offers approve and reject as two separate submissions", () => {
    // Two forms rather than one with a hidden outcome field: a hidden input is a value the
    // browser could be made to change, and these decisions are not interchangeable.
    const { approveButton, rejectButton } = renderControls();

    expect(approveButton()).toBeInTheDocument();
    expect(rejectButton()).toBeInTheDocument();
  });

  it("sends no reply text when approving", async () => {
    const { approve, approveButton } = renderControls();

    fireEvent.click(approveButton());

    await waitFor(() => expect(approve).toHaveBeenCalledTimes(1));
    // The approved text is whatever is stored; nothing from this form can replace it.
    const submitted = approve.mock.calls[0][1];
    expect([...submitted.keys()]).toHaveLength(0);
  });

  it("calls reject rather than approve when rejecting", async () => {
    const { approve, reject, rejectButton } = renderControls();

    fireEvent.click(rejectButton());

    await waitFor(() => expect(reject).toHaveBeenCalledTimes(1));
    expect(approve).not.toHaveBeenCalled();
  });

  it("announces a recorded decision", async () => {
    const approve = vi.fn<DecisionAction>(async () => ({ decided: "APPROVED" as const }));
    const { approveButton } = renderControls(approve);

    fireEvent.click(approveButton());

    expect(await screen.findByRole("status")).toHaveTextContent("Recorded: APPROVED.");
  });

  it("surfaces a conflict as an alert explaining someone else decided first", async () => {
    const approve = vi.fn<DecisionAction>(async () => ({
      conflict: true,
      error: "This approval has already been decided. Reload to see the current decision.",
    }));
    const { approveButton } = renderControls(approve);

    fireEvent.click(approveButton());

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("already been decided");
    expect(alert).toHaveTextContent("Another reviewer may have decided it first.");
  });

  it("surfaces an ordinary error without claiming a conflict", async () => {
    const approve = vi.fn<DecisionAction>(async () => ({ error: "Something went wrong." }));
    const { approveButton } = renderControls(approve);

    fireEvent.click(approveButton());

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Something went wrong.");
    expect(alert).not.toHaveTextContent("Another reviewer");
  });

  it("describes both buttons so the consequence is announced, not just the label", () => {
    const { approveButton, rejectButton } = renderControls();

    expect(approveButton()).toHaveAttribute("aria-describedby", "decision-help");
    expect(rejectButton()).toHaveAttribute("aria-describedby", "decision-help");
    expect(screen.getByText(/Approving publishes this exact text/)).toBeInTheDocument();
  });
});
