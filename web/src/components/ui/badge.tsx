import type { HTMLAttributes } from "react";

import { cn } from "@/lib/utils";

type BadgeTone = "neutral" | "signal" | "healthy" | "warning";

const toneClasses: Record<BadgeTone, string> = {
  neutral: "bg-[var(--surface-raised)] text-[var(--ink-muted)]",
  signal: "bg-[var(--signal-wash)] text-[var(--signal-ink)]",
  healthy: "bg-[var(--healthy-wash)] text-[var(--healthy-ink)]",
  warning: "bg-[var(--warning-wash)] text-[var(--warning-ink)]",
};

export interface BadgeProps extends HTMLAttributes<HTMLSpanElement> {
  tone?: BadgeTone;
}

export function Badge({ className, tone = "neutral", ...props }: BadgeProps) {
  return (
    <span
      className={cn(
        "inline-flex min-h-6 items-center rounded-full px-2.5 py-1 font-utility text-[0.68rem] leading-none font-semibold tracking-[0.08em] uppercase",
        toneClasses[tone],
        className,
      )}
      {...props}
    />
  );
}
