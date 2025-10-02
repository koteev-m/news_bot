import type { MouseEvent } from "react";
import { useTranslation } from "react-i18next";

export function SkipLink(): JSX.Element {
  const { t } = useTranslation();

  const handleClick = (event: MouseEvent<HTMLAnchorElement>) => {
    event.preventDefault();
    const main = document.getElementById("main-content");
    if (main instanceof HTMLElement) {
      main.focus();
    }
  };

  return (
    <a className="skip-link" href="#main-content" onClick={handleClick}>
      {t("skip.toContent")}
    </a>
  );
}
