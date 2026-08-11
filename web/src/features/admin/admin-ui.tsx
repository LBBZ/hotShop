/* eslint-disable react-refresh/only-export-components */
import {
  type DependencyList,
  type ReactNode,
  useCallback,
  useEffect,
  useState,
} from "react";
import { ExternalLink, RefreshCw } from "lucide-react";

import { findApiProblemError } from "@/api/core/problem";
import {
  EmptyState,
  ErrorState,
  LoadingState,
} from "@/components/async-states";
import { Button } from "@/components/ui/button";
import { createAdminTraceLinkBuilder } from "@/observability/admin-trace-link";
import type { CursorPage } from "@/features/admin/admin-api";

export interface LoadState<T> {
  data?: T;
  error?: unknown;
  loading: boolean;
  reload: () => void;
}

export function useAdminResource<T>(
  loader: () => Promise<T>,
  dependencies: DependencyList,
): LoadState<T> {
  const [nonce, setNonce] = useState(0);
  const [state, setState] = useState<Omit<LoadState<T>, "reload">>({
    loading: true,
  });

  // The caller supplies stable scalar dependencies; loader intentionally follows them.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const run = useCallback(loader, dependencies);

  useEffect(() => {
    let active = true;
    setState((current) => ({ ...current, loading: true, error: undefined }));
    void run()
      .then((data) => {
        if (active) setState({ data, loading: false });
      })
      .catch((error: unknown) => {
        if (active) setState({ error, loading: false });
      });
    return () => {
      active = false;
    };
  }, [run, nonce]);

  return { ...state, reload: () => setNonce((value) => value + 1) };
}

export function PageHeading({
  eyebrow,
  title,
  description,
  actions,
}: {
  eyebrow: string;
  title: string;
  description: string;
  actions?: ReactNode;
}) {
  return (
    <header className="admin-page-heading">
      <div>
        <p className="eyebrow">{eyebrow}</p>
        <h2>{title}</h2>
        <p>{description}</p>
      </div>
      {actions ? <div className="admin-heading-actions">{actions}</div> : null}
    </header>
  );
}

export function DataPanel({
  title,
  children,
  detail,
}: {
  title: string;
  children: ReactNode;
  detail?: string;
}) {
  return (
    <section className="admin-panel" aria-labelledby={`panel-${title}`}>
      <header className="admin-panel-heading">
        <h3 id={`panel-${title}`}>{title}</h3>
        {detail ? <span>{detail}</span> : null}
      </header>
      {children}
    </section>
  );
}

export function ResourceBoundary<T>({
  resource,
  emptyTitle,
  emptyDescription,
  isEmpty,
  children,
}: {
  resource: LoadState<T>;
  emptyTitle: string;
  emptyDescription: string;
  isEmpty: (data: T) => boolean;
  children: (data: T) => ReactNode;
}) {
  if (resource.loading && !resource.data) {
    return <LoadingState label="正在读取管理事实…" />;
  }
  if (resource.error) {
    const problem = findApiProblemError(resource.error)?.problem;
    return (
      <ErrorState
        description={
          problem?.detail ??
          (resource.error instanceof Error
            ? resource.error.message
            : "管理接口没有返回可用结果。")
        }
        requestId={problem?.requestId}
        onRetry={resource.reload}
      />
    );
  }
  if (!resource.data || isEmpty(resource.data)) {
    return <EmptyState title={emptyTitle} description={emptyDescription} />;
  }
  return <>{children(resource.data)}</>;
}

export function Pager({
  page,
  onNext,
  loading,
}: {
  page: CursorPage<unknown>;
  onNext: (cursor: string) => void;
  loading?: boolean;
}) {
  return page.hasMore && page.nextCursor ? (
    <div className="admin-pager">
      <Button
        type="button"
        variant="secondary"
        disabled={loading}
        onClick={() => onNext(page.nextCursor!)}
      >
        <RefreshCw aria-hidden="true" className="size-4" />
        加载下一页
      </Button>
    </div>
  ) : (
    <p className="admin-page-end">已到达当前查询范围末尾</p>
  );
}

export function TraceLink({ traceId }: { traceId?: string | null }) {
  const configuredTraceUrl: unknown = import.meta.env.VITE_ADMIN_TRACE_URL;
  const href = createAdminTraceLinkBuilder(
    typeof configuredTraceUrl === "string"
      ? configuredTraceUrl
      : "http://localhost:3000/explore",
  )(traceId);
  if (!href) return <span className="admin-muted">无有效 Trace</span>;
  return (
    <a className="trace-link" href={href} target="_blank" rel="noreferrer">
      <span>{traceId}</span>
      <ExternalLink aria-hidden="true" />
      <span className="sr-only">在可观测平台打开 Trace</span>
    </a>
  );
}

export function formatMoment(value: string | Date | null | undefined) {
  if (!value) return "—";
  const date = value instanceof Date ? value : new Date(value);
  return Number.isNaN(date.valueOf())
    ? "—"
    : new Intl.DateTimeFormat("zh-CN", {
        dateStyle: "medium",
        timeStyle: "medium",
      }).format(date);
}

export function statusTone(value: string) {
  if (/SUCCESS|PAID|COMPLETED|ACTIVE|CONSISTENT/u.test(value)) return "healthy";
  if (/FAIL|ERROR|CRITICAL|DENIED|CANCEL/u.test(value)) return "signal";
  return "warning";
}
