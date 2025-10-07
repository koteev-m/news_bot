export type Offer = {
  tier: "PRO" | "PRO_PLUS" | "VIP";
  priceXtr: number;
  starsPackage?: number | null;
};

export type PaywallPayload = {
  copyVariant: string;
  priceVariant: string;
  headingEn: string;
  subEn: string;
  ctaEn: string;
  offers: Offer[];
};

function buildHeaders(jwt?: string): HeadersInit {
  if (!jwt) {
    return { "content-type": "application/json" };
  }
  return { "content-type": "application/json", Authorization: `Bearer ${jwt}` };
}

export async function fetchOffers(apiBase: string, jwt?: string): Promise<PaywallPayload> {
  const response = await fetch(`${apiBase}/api/pricing/offers`, {
    headers: jwt ? { Authorization: `Bearer ${jwt}` } : undefined,
  });
  if (!response.ok) {
    throw new Error(String(response.status));
  }
  return (await response.json()) as PaywallPayload;
}

export async function ctaClick(apiBase: string, plan: string, variant: string, jwt?: string): Promise<void> {
  await fetch(`${apiBase}/api/pricing/cta`, {
    method: "POST",
    headers: buildHeaders(jwt),
    body: JSON.stringify({ plan, variant }),
  });
}
