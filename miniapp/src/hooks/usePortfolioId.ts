import { useCallback, useMemo, useState } from "react";
import { getPortfolioIdPreference, setPortfolioIdPreference } from "../lib/session";

const UUID_REGEX = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

export interface PortfolioIdState {
  portfolioId: string;
  setPortfolioId: (value: string) => void;
  normalizedPortfolioId: string;
  isValid: boolean;
}

export function usePortfolioId(): PortfolioIdState {
  const [portfolioId, setPortfolioIdState] = useState(() => getPortfolioIdPreference());

  const setPortfolioId = useCallback((value: string) => {
    setPortfolioIdState(value);
    setPortfolioIdPreference(value);
  }, []);

  const normalizedPortfolioId = useMemo(() => portfolioId.trim(), [portfolioId]);
  const isValid = useMemo(() => normalizedPortfolioId.length > 0 && UUID_REGEX.test(normalizedPortfolioId), [normalizedPortfolioId]);

  return {
    portfolioId,
    setPortfolioId,
    normalizedPortfolioId,
    isValid,
  };
}
