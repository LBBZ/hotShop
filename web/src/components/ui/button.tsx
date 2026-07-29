import * as React from "react";
import { Slot } from "@radix-ui/react-slot";
import { cva, type VariantProps } from "class-variance-authority";

import { cn } from "@/lib/utils";

const buttonVariants = cva(
  "inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-[var(--radius-control)] text-sm font-bold transition-[color,background-color,border-color,transform] outline-none focus-visible:ring-3 focus-visible:ring-[var(--focus)] focus-visible:ring-offset-2 focus-visible:ring-offset-[var(--surface)] disabled:pointer-events-none disabled:opacity-45 active:translate-y-px",
  {
    variants: {
      variant: {
        primary:
          "bg-[var(--signal)] px-5 py-2.5 text-white shadow-[0_8px_24px_rgba(239,76,91,0.22)] hover:bg-[var(--signal-strong)]",
        secondary:
          "border border-[var(--line-strong)] bg-[var(--surface)] px-5 py-2.5 text-[var(--ink)] hover:bg-[var(--surface-raised)]",
        ghost:
          "px-3 py-2 text-[var(--ink-muted)] hover:bg-[var(--surface-raised)] hover:text-[var(--ink)]",
        dark: "bg-[var(--navy)] px-5 py-2.5 text-white hover:bg-[var(--navy-soft)]",
      },
      size: {
        default: "h-11",
        sm: "h-9 px-3 py-2 text-xs",
        lg: "h-12 px-6 text-base",
        icon: "size-10 p-0",
      },
    },
    defaultVariants: {
      variant: "primary",
      size: "default",
    },
  },
);

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {
  asChild?: boolean;
}

export function Button({
  className,
  variant,
  size,
  asChild = false,
  ...props
}: ButtonProps) {
  const Component = asChild ? Slot : "button";
  return (
    <Component
      data-slot="button"
      className={cn(buttonVariants({ variant, size, className }))}
      {...props}
    />
  );
}
