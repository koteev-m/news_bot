export interface TelegramWebAppThemeParams {
  bg_color?: string;
  text_color?: string;
  hint_color?: string;
  link_color?: string;
  button_color?: string;
  button_text_color?: string;
}

export interface TelegramBackButton {
  isVisible: boolean;
  onClick(callback: () => void): void;
  show(): void;
  hide(): void;
}

export interface TelegramMainButton {
  text: string;
  isVisible: boolean;
  setText(text: string): TelegramMainButton;
  onClick(callback: () => void): void;
  show(): void;
  hide(): void;
}

export interface TelegramWebApp {
  initData: string;
  initDataUnsafe: unknown;
  themeParams: TelegramWebAppThemeParams;
  colorScheme: "light" | "dark";
  ready(): void;
  expand(): void;
  close(): void;
  MainButton: TelegramMainButton;
  BackButton: TelegramBackButton;
}

export interface TelegramNamespace {
  WebApp?: TelegramWebApp;
  [key: string]: unknown;
}

declare global {
  interface Window {
    Telegram?: TelegramNamespace;
  }
}

export {};
