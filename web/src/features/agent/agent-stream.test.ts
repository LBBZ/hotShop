import { describe, expect, it } from "vitest";

import {
  consumeAgentSse,
  guardAgentEvent,
  type AgentStreamEvent,
} from "@/features/agent/agent-stream";

const sessionId = "11111111-1111-4111-8111-111111111111";
const runId = "22222222-2222-4222-8222-222222222222";
const messageId = "33333333-3333-4333-8333-333333333333";

function event(
  type: AgentStreamEvent["type"],
  sequence: number,
  data: Record<string, unknown>,
  overrides: Record<string, unknown> = {},
) {
  return { type, sessionId, runId, messageId, sequence, data, ...overrides };
}

function responseFromChunks(chunks: string[]) {
  const encoder = new TextEncoder();
  return new Response(
    new ReadableStream({
      start(controller) {
        for (const chunk of chunks) controller.enqueue(encoder.encode(chunk));
        controller.close();
      },
    }),
    { headers: { "content-type": "text/event-stream; charset=utf-8" } },
  );
}

describe("Agent SSE contract", () => {
  it("accepts strict citations and rejects unknown fields or another run", () => {
    const citation = event("rag.completed", 1, {
      outcome: "hit",
      citations: [
        {
          documentId: "faq-1",
          title: "售后政策",
          version: "1",
          source: "knowledge",
          chunkId: "faq-1-0",
        },
      ],
    });
    expect(guardAgentEvent(citation, { sessionId, runId })).toEqual(citation);
    expect(
      guardAgentEvent(
        { ...citation, authorization: "Bearer secret" },
        { sessionId, runId },
      ),
    ).toBeNull();
    expect(
      guardAgentEvent(
        { ...citation, runId: "44444444-4444-4444-8444-444444444444" },
        { sessionId, runId },
      ),
    ).toBeNull();
  });

  it("rejects malformed event data and sensitive extra keys", () => {
    expect(
      guardAgentEvent(
        event("message.delta", 1, { delta: "safe", token: "secret" }),
        {
          sessionId,
          runId,
        },
      ),
    ).toBeNull();
    expect(
      guardAgentEvent(
        event("purchase_draft.created", 2, {
          draftId: "draft-1",
          actionType: "CREATE_ORDER",
          confirmationRequired: true,
          items: [{ productId: "1", quantity: 2 }],
        }),
        { sessionId, runId },
      ),
    ).not.toBeNull();
    expect(
      guardAgentEvent(
        event("purchase_draft.created", 3, {
          draftId: "draft-1",
          actionType: "CREATE_ORDER",
          confirmationRequired: true,
          items: [{ productId: "1", quantity: 2, confirmationToken: "secret" }],
        }),
        { sessionId, runId },
      ),
    ).toBeNull();
    expect(
      guardAgentEvent(
        event("error", 1, { code: "FAILED", message: "bounded" }),
        {
          sessionId,
          runId,
        },
      ),
    ).toBeNull();
    expect(
      guardAgentEvent(
        event("usage", 1, {
          inputTokens: 1,
          outputTokens: -1,
          estimatedCostUsd: 0,
        }),
        {
          sessionId,
          runId,
        },
      ),
    ).toBeNull();
  });

  it("parses CRLF split chunks, ignores malformed and non-monotonic events", async () => {
    const first = JSON.stringify(event("message.delta", 1, { delta: "你好" }));
    const duplicate = JSON.stringify(
      event("message.delta", 1, { delta: "迟到" }),
    );
    const done = JSON.stringify(event("done", 2, { state: "COMPLETED" }));
    const stream = [
      `event: message.delta\r`,
      `\ndata: ${first}\r\n\r\nevent: message.delta\ndata: {bad}\n\n`,
      `event: message.delta\ndata: ${duplicate}\n\nevent: done\ndata: ${done}\n\n`,
    ];
    const received: AgentStreamEvent[] = [];
    await consumeAgentSse(
      responseFromChunks(stream),
      { sessionId, runId },
      (item) => received.push(item),
      new AbortController().signal,
    );
    expect(received.map((item) => item.type)).toEqual([
      "message.delta",
      "done",
    ]);
    expect(received[0]?.data.delta).toBe("你好");
  });

  it("fails closed for a non-SSE response", async () => {
    await expect(
      consumeAgentSse(
        new Response("{}", { headers: { "content-type": "application/json" } }),
        { sessionId, runId },
        () => undefined,
        new AbortController().signal,
      ),
    ).rejects.toThrow("unexpected content type");
  });
});
