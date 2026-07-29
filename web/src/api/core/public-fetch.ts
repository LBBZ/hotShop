import { apiEnvironment, toApiUrl } from "@/api/core/environment";
import { mapProblemResponse } from "@/api/core/problem";
import { withRequestId } from "@/api/core/request-id";

export const publicFetch: typeof fetch = async (input, init = {}) => {
  const response = await fetch(toApiUrl(apiEnvironment.baseUrl, input), {
    ...init,
    credentials: "include",
    headers: withRequestId(init.headers),
  });

  if (!response.ok) {
    throw await mapProblemResponse(response);
  }
  return response;
};
