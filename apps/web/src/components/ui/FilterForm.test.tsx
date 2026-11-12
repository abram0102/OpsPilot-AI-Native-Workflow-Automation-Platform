import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { FilterForm } from "./FilterForm";

describe("FilterForm", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("submits as soon as a control changes, so no Apply button is needed", () => {
    // jsdom implements requestSubmit but will not navigate, so spy on it: the
    // behaviour under test is "the form was asked to submit", not the GET.
    const requestSubmit = vi
      .spyOn(HTMLFormElement.prototype, "requestSubmit")
      .mockImplementation(() => {});

    render(
      <FilterForm label="Filter tickets">
        <label htmlFor="status">Status</label>
        <select id="status" name="status" defaultValue="">
          <option value="">All</option>
          <option value="OPEN">OPEN</option>
        </select>
      </FilterForm>,
    );

    expect(requestSubmit).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText("Status"), { target: { value: "OPEN" } });

    expect(requestSubmit).toHaveBeenCalledTimes(1);
  });

  it("stays a plain GET form, so the filters remain real query parameters", () => {
    render(
      <FilterForm label="Filter tickets">
        <input name="status" aria-label="Status" />
      </FilterForm>,
    );

    const form = screen.getByRole("form", { name: "Filter tickets" });
    expect(form).toHaveAttribute("method", "get");
  });
});
