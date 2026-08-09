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
  | "offline";

export function transactionReconnectDelay(reconnects: number): number {
  return Math.min(1000 * 2 ** reconnects, 8000);
}

function waitUntilOnline(signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
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
    const timer = window.setTimeout(finish, delay);
    function finish() {
      window.clearTimeout(timer);
      signal.removeEventListener("abort", finish);
      resolve();
    }
    signal.addEventListener("abort", finish, { once: true });
  });
}

interface StreamSession {
  path: string | null;
  generation: number;
  state: typeof initialTransactionState;
  connection: StreamConnection;
  lastEventId?: string;
  reconnects: number;
}

type StreamSessionAction =
  | { type: "reset"; path: string | null; generation: number }
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

function createSession(path: string | null, generation = 0): StreamSession {
  return {
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
    return createSession(action.path, action.generation);
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
  const [session, dispatch] = useReducer(
    reduceStreamSession,
    path,
    createSession,
  );
  const generationRef = useRef(0);

  useEffect(() => {
    const generation = ++generationRef.current;
    dispatch({ type: "reset", path, generation });
    if (!path) return;
    const controller = new AbortController();
    let reconnects = 0;
    let active = true;
    let lastEventId: string | undefined;
    let connectionController: AbortController | null = null;
    let streamState = initialTransactionState;

    const isCurrent = (connectionSignal?: AbortSignal) =>
      active &&
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
          if (
            error instanceof DOMException &&
            error.name === "AbortError" &&
            navigator.onLine
          )
            return;
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
          transactionReconnectDelay(reconnects),
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
  }, [path]);

  if (session.path !== path || session.generation !== generationRef.current) {
    return {
      state: initialTransactionState,
      connection: "connecting" as StreamConnection,
    };
  }
  return { state: session.state, connection: session.connection };
}
