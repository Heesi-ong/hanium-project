import apiClient, { unwrapApiResponse } from "./apiClient";

export async function completeOnboarding({ purpose, experienceLevel, improvementGoal }) {
    const response = await apiClient.post("/api/users/me/onboarding", {
        purpose,
        experienceLevel,
        improvementGoal,
    });

    return unwrapApiResponse(response);
}
