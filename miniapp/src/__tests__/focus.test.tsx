import { describe, expect, test, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { I18nextProvider } from "react-i18next";
import { SkipLink } from "../components/SkipLink";
import { TabBar } from "../components/TabBar";
import { ToasterProvider } from "../components/Toaster";
import { i18n } from "../i18n";

function renderWithProviders(node: JSX.Element) {
  return render(
    <I18nextProvider i18n={i18n}>
      <ToasterProvider>{node}</ToasterProvider>
    </I18nextProvider>,
  );
}

describe("focus management", () => {
  test("skip link moves focus to main content", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <div>
        <SkipLink />
        <main id="main-content" tabIndex={-1}>
          content
        </main>
      </div>,
    );
    const link = screen.getByRole("link", { name: /skip to content/i });
    await user.click(link);
    const target = document.getElementById("main-content");
    expect(target).not.toBeNull();
    expect(document.activeElement).toBe(target);
  });

  test("tab bar supports arrow key navigation", async () => {
    const user = userEvent.setup();
    const items = [
      { path: "/", label: "Dashboard" },
      { path: "/positions", label: "Positions" },
      { path: "/trades", label: "Trades" },
    ];
    let activePath = items[0].path;
    const onNavigate = vi.fn((path: string) => {
      activePath = path;
      rerenderComponent();
    });

    const { rerender } = renderWithProviders(
      <TabBar items={items} activePath={activePath} onNavigate={onNavigate} ariaLabel="Navigation" />,
    );

    function rerenderComponent() {
      rerender(<TabBar items={items} activePath={activePath} onNavigate={onNavigate} ariaLabel="Navigation" />);
    }

    const tabs = screen.getAllByRole("tab");
    tabs[0].focus();
    await user.keyboard("{ArrowRight}");
    expect(onNavigate).toHaveBeenLastCalledWith("/positions");
    const updatedTabs = screen.getAllByRole("tab");
    expect(document.activeElement).toBe(updatedTabs[1]);

    await user.keyboard("{End}");
    expect(onNavigate).toHaveBeenLastCalledWith("/trades");
    const finalTabs = screen.getAllByRole("tab");
    expect(document.activeElement).toBe(finalTabs[2]);

    await user.keyboard("{Home}");
    expect(onNavigate).toHaveBeenLastCalledWith("/");
    const resetTabs = screen.getAllByRole("tab");
    expect(document.activeElement).toBe(resetTabs[0]);
  });
});
