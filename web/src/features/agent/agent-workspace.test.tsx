import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { userAuth } from "@/auth/domains";
import { mapProblemResponse } from "@/api/core/problem";
import type { AgentStreamEvent } from "@/features/agent/agent-stream";

const mocks = vi.hoisted(() => ({
  createSession: vi.fn(),
  addMessage: vi.fn(),
  startRun: vi.fn(),
  cancelRun: vi.fn(),
  consume: vi.fn(),
}));
vi.mock("@/api/clients", () => ({
  apiClients: {
    agent: {
      user: {
        createSessionApiV1AgentSessionsPost: mocks.createSession,
        addMessageApiV1AgentSessionsSessionIdMessagesPost: mocks.addMessage,
        startRunApiV1AgentSessionsSessionIdRunsPost: mocks.startRun,
        cancelRunApiV1AgentRunsRunIdDelete: mocks.cancelRun,
      },
    },
  },
}));
vi.mock("@/features/agent/agent-stream", () => ({
  consumeAgentSse: mocks.consume,
}));
vi.mock("@/features/transactions/use-transaction-stream", () => ({
  useTransactionStream: () => ({
    state: { events: [], seenEventIds: new Set() },
    connection: "connecting",
  }),
}));
import { AgentWorkspace } from "@/features/agent/agent-workspace";

const sessionId = "11111111-1111-4111-8111-111111111111";
const runId = "22222222-2222-4222-8222-222222222222";
const messageId = "33333333-3333-4333-8333-333333333333";
const login = (userId: string) =>
  userAuth.store.getState().setSession({
    userId,
    username: userId,
    role: "ROLE_USER",
    accessToken: `token-${userId}`,
    expiresAt: "2030-01-01T00:00:00Z",
  });
function submit(content: string) {
  fireEvent.change(screen.getByLabelText("请求"), {
    target: { value: content },
  });
  fireEvent.click(screen.getByRole("button", { name: "发送请求" }));
}
function mount() {
  return render(
    <MemoryRouter>
      <AgentWorkspace boundary="user" />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  for (const mock of Object.values(mocks)) mock.mockReset();
  login("A");
  mocks.createSession.mockResolvedValue({ id: sessionId });
  mocks.addMessage.mockResolvedValue({ id: messageId });
  mocks.startRun.mockResolvedValue({ id: runId });
  mocks.cancelRun.mockResolvedValue({ id: runId, state: "CANCELLED" });
  mocks.consume.mockImplementation(
    (
      _response: Response,
      _expected: unknown,
      emit: (event: AgentStreamEvent) => void,
    ) => {
      emit({
        type: "message.delta",
        sessionId,
        runId,
        sequence: 1,
        data: { delta: "回答内容" },
      });
      return Promise.resolve("COMPLETED");
    },
  );
  vi.spyOn(userAuth, "fetch").mockResolvedValue(new Response());
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  userAuth.store.getState().clearSession();
});

describe("Agent request lifecycle", () => {
  it("keeps the question and creates a new conversation after the server loses its session", async () => {
    mount();
    submit("第一次提问");
    await waitFor(() => expect(mocks.consume).toHaveBeenCalledOnce());
    const missing = await mapProblemResponse(
      Response.json(
        { detail: "Resource not found", code: "NOT_FOUND" },
        { status: 404 },
      ),
    );
    mocks.addMessage.mockRejectedValueOnce(missing);
    submit("服务重启后继续查商品");
    await screen.findByText(
      "上一段对话已失效，请重新发送问题以开始新对话。购买仍需你确认。",
    );
    expect(screen.getByLabelText("请求")).toHaveValue("服务重启后继续查商品");
    expect(mocks.createSession).toHaveBeenCalledOnce();
    expect(mocks.startRun).toHaveBeenCalledOnce();
    fireEvent.click(screen.getByRole("button", { name: "发送请求" }));
    await waitFor(() => expect(mocks.createSession).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(mocks.consume).toHaveBeenCalledTimes(2));
  });
  it("cancels a run whose creation response arrives after the user cancels", async () => {
    let release: ((value: { id: string }) => void) | undefined;
    mocks.startRun.mockImplementation(
      () =>
        new Promise<{ id: string }>((resolve) => {
          release = resolve;
        }),
    );
    mount();
    submit("创建阶段取消");
    await waitFor(() => expect(mocks.startRun).toHaveBeenCalledOnce());
    fireEvent.click(screen.getByRole("button", { name: "取消运行" }));
    await act(async () => {
      release?.({ id: runId });
      await Promise.resolve();
    });
    await waitFor(() =>
      expect(mocks.cancelRun).toHaveBeenCalledWith({ runId }),
    );
    expect(mocks.consume).not.toHaveBeenCalled();
    expect(screen.getByText("请求已取消，可以继续提问。")).toBeVisible();
  });
  it("does not start a run after cancellation while creating the message", async () => {
    let release: ((value: { id: string }) => void) | undefined;
    mocks.addMessage.mockImplementation(
      () =>
        new Promise<{ id: string }>((resolve) => {
          release = resolve;
        }),
    );
    mount();
    submit("取消消息");
    await waitFor(() => expect(mocks.addMessage).toHaveBeenCalledOnce());
    fireEvent.click(screen.getByRole("button", { name: "取消运行" }));
    await act(async () => {
      release?.({ id: messageId });
      await Promise.resolve();
    });
    expect(mocks.startRun).not.toHaveBeenCalled();
  });
  it("reuses a conversation for follow-up turns and starts fresh only on explicit reset", async () => {
    mount();
    submit("第一轮");
    await screen.findByText("回答内容");
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "发送请求" })).toBeDisabled(),
    );
    submit("继续刚才的问题");
    await waitFor(() => expect(mocks.startRun).toHaveBeenCalledTimes(2));
    expect(mocks.createSession).toHaveBeenCalledOnce();
    expect(mocks.addMessage).toHaveBeenLastCalledWith(
      expect.objectContaining({
        sessionId,
        messageRequest: { content: "继续刚才的问题" },
      }),
      expect.anything(),
    );
    await waitFor(() =>
      expect(screen.getByText("之前的对话（1）")).toBeVisible(),
    );
    fireEvent.click(screen.getByRole("button", { name: "开始新对话" }));
    submit("新话题");
    await waitFor(() => expect(mocks.createSession).toHaveBeenCalledTimes(2));
  });
  it("clears conversation and creates a new session when the account changes", async () => {
    mount();
    submit("A的私人问题");
    await screen.findByText("回答内容");
    act(() => login("B"));
    expect(screen.queryByText("回答内容")).not.toBeInTheDocument();
    submit("B的问题");
    await waitFor(() => expect(mocks.createSession).toHaveBeenCalledTimes(2));
  });
  it("shows an incomplete stream as a failure and cancels its remaining run", async () => {
    mocks.consume.mockRejectedValue(
      new Error("Agent 连接中断，回答尚未完成。请重试。"),
    );
    mount();
    submit("中断测试");
    await screen.findByRole("alert");
    expect(
      screen.getByText("Agent 连接中断，回答尚未完成。请重试。"),
    ).toBeVisible();
    await waitFor(() =>
      expect(mocks.cancelRun).toHaveBeenCalledWith({ runId }),
    );
  });
});

it("waits for cancellation acknowledgement before starting a follow-up run", async () => {
  let releaseCancellation: (() => void) | undefined;
  mocks.cancelRun.mockImplementation(
    () =>
      new Promise<void>((resolve) => {
        releaseCancellation = resolve;
      }),
  );
  mocks.consume.mockImplementationOnce(() => new Promise(() => {}));
  mount();
  submit("正在进行的请求");
  await waitFor(() => expect(mocks.consume).toHaveBeenCalledOnce());
  fireEvent.click(screen.getByRole("button", { name: "取消运行" }));
  await waitFor(() => expect(mocks.cancelRun).toHaveBeenCalledOnce());
  submit("立即追问");
  await act(async () => {
    await Promise.resolve();
  });
  expect(mocks.startRun).toHaveBeenCalledOnce();
  await act(async () => {
    releaseCancellation?.();
    await Promise.resolve();
  });
  await waitFor(() => expect(mocks.startRun).toHaveBeenCalledTimes(2));
});

it("waits for a late run creation and its cancellation before a follow-up", async () => {
  let releaseCreation: ((run: { id: string }) => void) | undefined;
  let releaseCancellation: (() => void) | undefined;
  const cancelled = new Promise<void>((resolve) => {
    releaseCancellation = resolve;
  });
  mocks.startRun.mockImplementationOnce(
    () =>
      new Promise<{ id: string }>((resolve) => {
        releaseCreation = resolve;
      }),
  );
  mocks.cancelRun.mockReturnValue(cancelled);
  mount();
  submit("创建中的请求");
  await waitFor(() => expect(mocks.startRun).toHaveBeenCalledOnce());
  fireEvent.click(screen.getByRole("button", { name: "取消运行" }));
  submit("马上继续");
  await act(async () => {
    releaseCreation?.({ id: runId });
    await Promise.resolve();
  });
  await waitFor(() => expect(mocks.cancelRun).toHaveBeenCalled());
  expect(mocks.startRun).toHaveBeenCalledOnce();
  await act(async () => {
    releaseCancellation?.();
    await Promise.resolve();
  });
  await waitFor(() => expect(mocks.startRun).toHaveBeenCalledTimes(2));
});
