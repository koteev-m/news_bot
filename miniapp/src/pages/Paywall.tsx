import React, { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { ctaClick, fetchOffers, PaywallPayload } from "../lib/pricing";
import { getJwt } from "../lib/session";

export default function Paywall(): JSX.Element {
  const { t } = useTranslation();
  const [payload, setPayload] = useState<PaywallPayload | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    let active = true;
    const jwt = getJwt() ?? undefined;
    fetchOffers(import.meta.env.VITE_API_BASE as string, jwt)
      .then((response) => {
        if (active) {
          setPayload(response);
        }
      })
      .catch((err) => {
        if (active) {
          setError((err as Error).message);
        }
      });
    return () => {
      active = false;
    };
  }, []);

  if (error) {
    return <p role="alert">{t("error.generic", "Something went wrong / Что-то пошло не так")}</p>;
  }

  if (!payload) {
    return <p role="status" aria-live="polite">{t("loading", "Loading… / Загрузка…")}</p>;
  }

  const headingKey = `paywall.title.${payload.copyVariant}`;
  const subtitleKey = `paywall.subtitle.${payload.copyVariant}`;
  const ctaKey = `paywall.cta.${payload.copyVariant}`;

  const handleCta = async (tier: string) => {
    setSubmitting(true);
    try {
      const jwt = getJwt() ?? undefined;
      await ctaClick(import.meta.env.VITE_API_BASE as string, tier, payload.priceVariant, jwt);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <section aria-labelledby="paywall-title" id="main-content">
      <h1 id="paywall-title">{t(headingKey, { defaultValue: payload.headingEn })}</h1>
      <p>{t(subtitleKey, { defaultValue: payload.subEn })}</p>
      <ul>
        {payload.offers.map((offer) => (
          <li key={offer.tier}>
            <div>
              <strong>{t(`paywall.plan.${offer.tier}`, { defaultValue: offer.tier })}</strong> —
              {" "}
              {t("paywall.price.xtr", { defaultValue: `${offer.priceXtr} Stars / month`, xtr: offer.priceXtr })}
            </div>
            <button
              type="button"
              disabled={submitting}
              aria-busy={submitting}
              onClick={() => handleCta(offer.tier)}
            >
              {t(ctaKey, { defaultValue: payload.ctaEn })}
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}
