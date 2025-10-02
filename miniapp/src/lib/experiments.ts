import { useCallback, useEffect, useState } from "react";
import { clearJwt, getJwt } from "./session";

const API_BASE = (import.meta.env.VITE_API_BASE ?? "").replace(/\/$/, "");

function withBase(path: string): string {
  if (!API_BASE) {
    return path;
  }
  if (path.startsWith("http")) {
    return path;
  }
  return `${API_BASE}${path}`;
}

export interface Assignment {
  userId: number;
  key: string;
  variant: string;
}

export async function fetchAssignments(): Promise<Assignment[]> {
  const token = getJwt();
  if (!token) {
    throw new Error("Unauthorized");
  }
  const response = await fetch(withBase("/api/experiments/assignments"), {
    headers: {
      Authorization: `Bearer ${token}`,
    },
  });
  if (response.status === 401) {
    clearJwt();
    throw new Error("Unauthorized");
  }
  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || "Failed to fetch assignments");
  }
  const text = await response.text();
  if (!text) {
    return [];
  }
  const data = JSON.parse(text) as Assignment[];
  return data.map((assignment) => ({
    userId: Number(assignment.userId),
    key: assignment.key,
    variant: assignment.variant,
  }));
}

export function useExperiments(): {
  assignments: Assignment[];
  loading: boolean;
  error: Error | null;
  variant: (key: string) => string | undefined;
} {
  const [assignments, setAssignments] = useState<Assignment[]>([]);
  const [error, setError] = useState<Error | null>(null);
  const [loading, setLoading] = useState<boolean>(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    fetchAssignments()
      .then((items) => {
        if (!cancelled) {
          setAssignments(items);
          setError(null);
        }
      })
      .catch((err: Error) => {
        if (!cancelled) {
          setError(err);
          setAssignments([]);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const variant = useCallback(
    (key: string) => assignments.find((assignment) => assignment.key === key)?.variant,
    [assignments],
  );

  return {
    assignments,
    loading,
    error,
    variant,
  };
}
