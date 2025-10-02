import { Component, type ErrorInfo, type ReactNode } from "react";

interface ErrorBoundaryProps {
  children: ReactNode;
  onRetry?: () => void;
  renderError?: (error: Error, onRetry: () => void) => ReactNode;
}

interface ErrorBoundaryState {
  error: Error | null;
}

export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  public state: ErrorBoundaryState = { error: null };

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error };
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo): void {
    if (import.meta.env.DEV) {
      // eslint-disable-next-line no-console
      console.error("Unhandled error", error, errorInfo);
    }
  }

  private handleRetry = (): void => {
    this.setState({ error: null });
    if (this.props.onRetry) {
      this.props.onRetry();
    }
  };

  override render(): ReactNode {
    const { children, renderError } = this.props;
    const { error } = this.state;

    if (error) {
      if (renderError) {
        return renderError(error, this.handleRetry);
      }
      return (
        <div className="error-boundary" role="alert" aria-live="assertive">
          <p>{error.message}</p>
          <button type="button" onClick={this.handleRetry}>
            Retry
          </button>
        </div>
      );
    }

    return children;
  }
}
