"use server";

import { redirect } from "next/navigation";
import { ApiError, createOrganization } from "@/lib/api/organizations";

export interface CreateOrganizationState {
  error?: string;
}

/** Backs {@link CreateOrganizationForm}. Redirects straight to the new org's dashboard on success. */
export async function createOrganizationAction(
  _prevState: CreateOrganizationState,
  formData: FormData,
): Promise<CreateOrganizationState> {
  const name = String(formData.get("name") ?? "").trim();
  const slug = String(formData.get("slug") ?? "").trim();

  if (!name || !slug) {
    return { error: "Name and URL slug are both required." };
  }

  let organizationId: string;
  try {
    const organization = await createOrganization(name, slug);
    organizationId = organization.id;
  } catch (error) {
    if (error instanceof ApiError) {
      return { error: error.message };
    }
    throw error;
  }

  redirect(`/app/${organizationId}/dashboard`);
}
