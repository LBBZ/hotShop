import { Component, type ErrorInfo, type ReactNode } from "react";

import { ErrorState } from "@/components/async-states";

interface AppErrorBoundaryProps {
  children: ReactNode;
}

interface AppErrorBoundaryState {
  error: Error | null;
}

export class AppErrorBoundary extends Component<
  AppErrorBoundaryProps,
  AppErrorBoundaryState
> {
  override state: AppErrorBoundaryState = { error: null };

  static getDerivedStateFromError(error: Error): AppErrorBoundaryState {
    return { error };
  }

  override componentDidCatch(error: Error, info: ErrorInfo) {
    console.error("Unhandled render error", {
      name: error.name,
      componentStack: info.componentStack,
    });
  }

  private readonly reset = () => {
    this.setState({ error: null });
  };

  override render() {
    if (this.state.error) {
      return (
        <main className="grid min-h-screen place-items-center bg-[var(--canvas)] p-6">
          <div className="w-full max-w-2xl">
            <ErrorState
              title="界面暂时停止响应"
              description="页面状态没有正确完成渲染。请重试；如果问题持续出现，请把请求 ID 或操作时间提供给维护人员。"
              onRetry={this.reset}
            />
          </div>
        </main>
      );
    }

    return this.props.children;
  }
}
