import { Routes, Route } from "react-router-dom";
import { Dashboard } from "./pages/Dashboard";
import { Positions } from "./pages/Positions";
import { Trades } from "./pages/Trades";
import { Import } from "./pages/Import";
import { Calendar } from "./pages/Calendar";
import { Reports } from "./pages/Reports";
import { Settings } from "./pages/Settings";
import { Help } from "./pages/Help";
import { Feedback } from "./pages/Feedback";
import type { ThemeMode } from "./lib/session";
import type { InitDataUnsafe } from "./lib/telegram";

interface SettingsProps {
  theme: ThemeMode;
  onThemeChange: (theme: ThemeMode) => void;
  initDataUnsafe: InitDataUnsafe | null;
}

interface AppRoutesProps {
  settingsProps: SettingsProps;
}

export function AppRoutes({ settingsProps }: AppRoutesProps): JSX.Element {
  return (
    <Routes>
      <Route path="/" element={<Dashboard />} />
      <Route path="/positions" element={<Positions />} />
      <Route path="/trades" element={<Trades />} />
      <Route path="/import" element={<Import />} />
      <Route path="/calendar" element={<Calendar />} />
      <Route path="/reports" element={<Reports />} />
      <Route path="/settings" element={<Settings {...settingsProps} />} />
      <Route path="/help" element={<Help />} />
      <Route path="/feedback" element={<Feedback />} />
    </Routes>
  );
}
