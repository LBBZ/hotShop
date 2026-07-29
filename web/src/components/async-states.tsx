import { AlertTriangle, Inbox, RotateCcw } from "lucide-react";

import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

interface LoadingStateProps {
  label?: string;
  compact?: boolean;
}

export function LoadingState({
  label = "正在同步交易状态",
  compact = false,
}: LoadingStateProps) {
  return (
    <div
      className={cn("state-panel", compact ? "min-h-32" : "min-h-72")}
      role="status"
      aria-live="polite"
    >
      <div className="pulse-loader" aria-hidden="true">
        <span />
        <span />
        <span />
      </div>
      <p>{label}</p>
    </div>
  );
}

interface EmptyStateProps {
  title: string;
  description: string;
  action?: React.ReactNode;
}

export function EmptyState({ title, description, action }: EmptyStateProps) {
  return (
    <section className="state-panel min-h-64" aria-labelledby="empty-title">
      <span className="state-icon" aria-hidden="true">
        <Inbox />
      </span>
      <div className="max-w-md text-center">
        <h2 id="empty-title" className="text-xl font-bold">
          {title}
        </h2>
        <p className="mt-2 text-sm leading-6 text-[var(--ink-muted)]">
          {description}
        </p>
      </div>
      {action}
    </section>
  );
}

interface ErrorStateProps {
  title?: string;
  description: string;
  requestId?: string;
  onRetry?: () => void;
}

export function ErrorState({
  title = "这次同步没有完成",
  description,
  requestId,
  onRetry,
}: ErrorStateProps) {
  return (
    <section
      className="state-panel min-h-64"
      aria-labelledby="error-title"
      role="alert"
    >
      <span className="state-icon state-icon-error" aria-hidden="true">
        <AlertTriangle />
      </span>
      <div className="max-w-md text-center">
        <h2 id="error-title" className="text-xl font-bold">
          {title}
        </h2>
        <p className="mt-2 text-sm leading-6 text-[var(--ink-muted)]">
          {description}
        </p>
        {requestId ? (
          <p className="mt-3 font-utility text-xs text-[var(--ink-subtle)]">
            请求 ID · {requestId}
          </p>
        ) : null}
      </div>
      {onRetry ? (
        <Button type="button" variant="secondary" size="sm" onClick={onRetry}>
          <RotateCcw aria-hidden="true" className="size-4" />
          重新同步
        </Button>
      ) : null}
    </section>
  );
}
