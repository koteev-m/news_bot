interface TabItem {
  path: string;
  label: string;
}

interface TabBarProps {
  items: TabItem[];
  activePath: string;
  onNavigate: (path: string) => void;
}

export function TabBar({ items, activePath, onNavigate }: TabBarProps): JSX.Element {
  return (
    <nav className="tab-bar">
      {items.map((item) => {
        const isActive = activePath === item.path;
        return (
          <button
            type="button"
            key={item.path}
            className={isActive ? "tab-bar__button tab-bar__button--active" : "tab-bar__button"}
            onClick={() => onNavigate(item.path)}
          >
            {item.label}
          </button>
        );
      })}
    </nav>
  );
}
