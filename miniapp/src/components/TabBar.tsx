import { useEffect, useRef } from "react";
import type { KeyboardEvent } from "react";

interface TabItem {
  path: string;
  label: string;
}

interface TabBarProps {
  items: TabItem[];
  activePath: string;
  onNavigate: (path: string) => void;
  ariaLabel?: string;
}

export function TabBar({ items, activePath, onNavigate, ariaLabel }: TabBarProps): JSX.Element {
  const tabRefs = useRef<Array<HTMLButtonElement | null>>([]);

  useEffect(() => {
    const activeIndex = items.findIndex((item) => item.path === activePath);
    if (activeIndex >= 0) {
      const activeTab = tabRefs.current[activeIndex];
      activeTab?.focus();
    }
  }, [activePath, items]);

  const handleKeyDown = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    const total = items.length;
    if (total === 0) {
      return;
    }
    let nextIndex = index;
    switch (event.key) {
      case "ArrowRight":
      case "ArrowDown":
        nextIndex = (index + 1) % total;
        break;
      case "ArrowLeft":
      case "ArrowUp":
        nextIndex = (index - 1 + total) % total;
        break;
      case "Home":
        nextIndex = 0;
        break;
      case "End":
        nextIndex = total - 1;
        break;
      case "Enter":
      case " ":
        event.preventDefault();
        onNavigate(items[index].path);
        return;
      default:
        return;
    }
    event.preventDefault();
    const target = tabRefs.current[nextIndex];
    if (target) {
      target.focus();
    }
    onNavigate(items[nextIndex].path);
  };

  return (
    <nav className="tab-bar" role="tablist" aria-label={ariaLabel ?? "Primary navigation"}>
      {items.map((item, index) => {
        const isActive = activePath === item.path;
        const id = `tab-${item.path.replace(/\//g, "") || "root"}`;
        return (
          <button
            type="button"
            key={item.path}
            id={id}
            ref={(element) => {
              tabRefs.current[index] = element;
            }}
            className={isActive ? "tab-bar__button tab-bar__button--active" : "tab-bar__button"}
            role="tab"
            aria-selected={isActive}
            aria-controls="main-content"
            tabIndex={isActive ? 0 : -1}
            onClick={() => onNavigate(item.path)}
            onKeyDown={(event) => handleKeyDown(event, index)}
          >
            {item.label}
          </button>
        );
      })}
    </nav>
  );
}
