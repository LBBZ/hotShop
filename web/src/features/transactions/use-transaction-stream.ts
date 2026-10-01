import { useStore } from "zustand";
import { userAuth } from "@/auth/domains";
import { SessionExpiredError } from "@/auth/auth-domain";
import { findApiProblemError } from "@/api/core/problem";
import { useEffect, useReducer, useRef } from "react";

import {
  initialTransactionState,
  reduceTransactionEvent,
} from "@/features/transactions/status-machine";
import { streamTransactionEvents } from "@/features/transactions/transaction-stream";

export type StreamConnection =
  | "connecting"
  | "live"
  | "reconnecting"
  | "offline"
  | "unavailable";

export function transactionReconnectDelay(reconnects: number): number {
  const maximum = Math.min(1000 * 2 ** reconnects, 8000);
  return Math.round(maximum * (0.5 + Math.random() * 0.5));
}

export function transactionRetryAfterDelay(
  value: string | null,
  now = Date.now(),
): number {
  if (!value) return 0;
  const trimmed = value.trim();
  const delay = /^\d+$/.test(trimmed)
    ? Number(trimmed) * 1000
    : Date.parse(trimmed) - now;
  return Number.isFinite(delay) ? Math.max(0, delay) : 0;
}

function waitUntilOnline(signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    if (signal.aborted || navigator.onLine) {
      resolve();
      return;
    }
    const finish = () => {
      window.removeEventListener("online", finish);
      signal.removeEventListener("abort", finish);
      resolve();
    };
    window.addEventListener("online", finish, { once: true });
    signal.addEventListener("abort", finish, { once: true });
  });
}

export function waitForReconnectDelay(
  delay: number,
  signal: AbortSignal,
): Promise<void> {
  return new Promise((resolve) => {
    if (signal.aborted) {
      resolve();
      return;
    }
    const deadline = Date.now() + delay;
    let timer: number;
    const schedule = () => {
      const remaining = deadline - Date.now();
      if (remaining <= 0) finish();
      else
        timer = window.setTimeout(schedule, Math.min(remaining, 2_147_483_647));
    };
    function finish() {
      window.clearTimeout(timer);
      signal.removeEventListener("abort", finish);
      resolve();
    }
    signal.addEventListener("abort", finish, { once: true });
    schedule();
  });
}

interface StreamSession {
  userId: string | null;
  path: string | null;
  generation: number;
  state: typeof initialTransactionState;
  connection: StreamConnection;
  lastEventId?: string;
  reconnects: number;
}

type StreamSessionAction =
  | {
      type: "reset";
      path: string | null;
      generation: number;
      userId: string | null;
    }
  | {
      type: "connection";
      path: string;
      generation: number;
      connection: StreamConnection;
    }
  | {
      type: "event";
      path: string;
      generation: number;
      event: Parameters<typeof reduceTransactionEvent>[1];
    }
  | { type: "reconnect"; path: string; generation: number };

function createSession(
  path: string | null,
  generation = 0,
  userId: string | null = null,
): StreamSession {
  return {
    userId,
    path,
    generation,
    state: initialTransactionState,
    connection: "connecting",
    reconnects: 0,
  };
}

function reduceStreamSession(
  session: StreamSession,
  action: StreamSessionAction,
): StreamSession {
  if (action.type === "reset")
    return createSession(action.path, action.generation, action.userId);
  if (session.path !== action.path || session.generation !== action.generation)
    return session;
  if (action.type === "connection") {
    return { ...session, connection: action.connection };
  }
  if (action.type === "reconnect") {
    return { ...session, reconnects: session.reconnects + 1 };
  }
  return {
    ...session,
    state: reduceTransactionEvent(session.state, action.event),
    lastEventId: action.event.eventId,
  };
}

export function useTransactionStream(path: string | null) {
  const userId = useStore(
    userAuth.store,
    (state) => state.session?.userId ?? null,
  );
  const [session, dispatch] = useReducer(
    reduceStreamSession,
    path,
    createSession,
  );
  const generationRef = useRef(0);

  useEffect(() => {
    const generation = ++generationRef.current;
    dispatch({ type: "reset", path, generation, userId });
    if (!path) return;
    const controller = new AbortController();
    let reconnects = 0;
    let active = true;
    let lastEventId: string | undefined;
    let connectionController: AbortController | null = null;
    let streamState = initialTransactionState;

    const isCurrent = (connectionSignal?: AbortSignal) =>
      active &&
      (userAuth.store.getState().session?.userId ?? null) === userId &&
      generationRef.current === generation &&
      !controller.signal.aborted &&
      !connectionSignal?.aborted;

    const handleOffline = () => {
      if (!isCurrent()) return;
      dispatch({
        type: "connection",
        path,
        generation,
        connection: "offline",
      });
      connectionController?.abort();
    };
    const handleOnline = () => {
      if (!isCurrent()) return;
      dispatch({
        type: "connection",
        path,
        generation,
        connection: "reconnecting",
      });
    };
    window.addEventListener("offline", handleOffline);
    window.addEventListener("online", handleOnline);

    const connect = async () => {
      while (isCurrent()) {
        if (!navigator.onLine) {
          dispatch({
            type: "connection",
            path,
            generation,
            connection: "offline",
          });
          await waitUntilOnline(controller.signal);
          if (!isCurrent()) return;
        }
        dispatch({
          type: "connection",
          path,
          generation,
          connection: reconnects === 0 ? "connecting" : "reconnecting",
        });
        connectionController = new AbortController();
        const connectionSignal = connectionController.signal;
        let retryAfter = 0;
        try {
          await streamTransactionEvents(path, {
            signal: connectionSignal,
            lastEventId,
            onOpen: () => {
              if (!isCurrent(connectionSignal)) return;
              dispatch({
                type: "connection",
                path,
                generation,
                connection: navigator.onLine ? "live" : "offline",
              });
            },
            onEvent: (event) => {
              if (!isCurrent(connectionSignal)) return;
              const nextState = reduceTransactionEvent(streamState, event);
              if (nextState === streamState) return;
              streamState = nextState;
              lastEventId = event.eventId;
              dispatch({ type: "event", path, generation, event });
              dispatch({
                type: "connection",
                path,
                generation,
                connection: navigator.onLine ? "live" : "offline",
              });
            },
          });
        } catch (error) {
          if (!isCurrent()) return;
          const problem = findApiProblemError(error);
          if (problem && [429, 503].includes(problem.problem.status)) {
            retryAfter = transactionRetryAfterDelay(
              problem.response.headers.get("Retry-After"),
            );
          }
          if (
            error instanceof SessionExpiredError ||
            (problem &&
              [400, 401, 403, 404, 410].includes(problem.problem.status))
          ) {
            dispatch({
              type: "connection",
              path,
              generation,
              connection: "unavailable",
            });
            controller.abort();
            return;
          }
        } finally {
          connectionController = null;
        }
        if (!isCurrent()) return;
        dispatch({
          type: "connection",
          path,
          generation,
          connection: navigator.onLine ? "reconnecting" : "offline",
        });
        reconnects += 1;
        dispatch({ type: "reconnect", path, generation });
        await waitForReconnectDelay(
          Math.max(transactionReconnectDelay(reconnects), retryAfter),
          controller.signal,
        );
      }
    };
    void connect();
    return () => {
      active = false;
      controller.abort();
      connectionController?.abort();
      window.removeEventListener("offline", handleOffline);
      window.removeEventListener("online", handleOnline);
    };
  }, [path, userId]);

  if (
    session.userId !== userId ||
    session.path !== path ||
    session.generation !== generationRef.current
  ) {
    return {
      state: initialTransactionState,
      connection: "connecting" as StreamConnection,
    };
  }
  return { state: session.state, connection: session.connection };
}
