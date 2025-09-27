import { z } from "zod";

const DECIMAL_REGEX = /^\d+(?:\.\d+)?$/;
const ISO_INSTANT_REGEX = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/;
const DATETIME_WITH_SPACE = /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/;
const DATETIME_NO_TZ = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/;

const datetimeSchema = z.preprocess((value) => {
  if (typeof value !== "string") {
    return value;
  }
  const trimmed = value.trim();
  if (trimmed.length === 0) {
    return trimmed;
  }
  if (DATETIME_WITH_SPACE.test(trimmed)) {
    return `${trimmed.replace(" ", "T")}Z`;
  }
  if (DATETIME_NO_TZ.test(trimmed)) {
    return `${trimmed}Z`;
  }
  return trimmed;
}, z.string().regex(ISO_INSTANT_REGEX, "Invalid datetime"));

const requiredUppercase = z.preprocess((value) => {
  if (typeof value !== "string") {
    return value;
  }
  const trimmed = value.trim();
  if (trimmed.length === 0) {
    return trimmed;
  }
  return trimmed.toUpperCase();
}, z.string().min(1, { message: "Required" }));

const optionalUppercase = z.preprocess((value) => {
  if (typeof value !== "string") {
    return undefined;
  }
  const trimmed = value.trim();
  if (trimmed.length === 0) {
    return undefined;
  }
  return trimmed.toUpperCase();
}, z.string().optional());

const sideSchema = z.preprocess((value) => {
  if (typeof value !== "string") {
    return value;
  }
  return value.trim().toUpperCase();
}, z.enum(["BUY", "SELL"], { required_error: "Trade side is required" }));

const positiveDecimal = z.preprocess((value) => {
  if (typeof value !== "string") {
    return value;
  }
  return value.trim();
}, z
  .string()
  .regex(DECIMAL_REGEX, "Invalid decimal")
  .refine((value) => Number.parseFloat(value) > 0, "Must be greater than 0"));

const nonNegativeDecimal = z.preprocess((value) => {
  if (typeof value !== "string") {
    return undefined;
  }
  const trimmed = value.trim();
  if (trimmed.length === 0) {
    return undefined;
  }
  return trimmed;
}, z
  .string()
  .regex(DECIMAL_REGEX, "Invalid decimal")
  .refine((value) => Number.parseFloat(value) >= 0, "Must be greater or equal to 0")
  .optional());

const currencySchema = z.preprocess((value) => {
  if (typeof value !== "string") {
    return value;
  }
  return value.trim().toUpperCase();
}, z.string().regex(/^[A-Z]{3}$/, "Currency must be ISO-4217"));

const optionalCurrencySchema = z.preprocess((value) => {
  if (typeof value !== "string") {
    return undefined;
  }
  const trimmed = value.trim();
  if (trimmed.length === 0) {
    return undefined;
  }
  return trimmed.toUpperCase();
}, z.string().regex(/^[A-Z]{3}$/, "Currency must be ISO-4217").optional());

const optionalTrimmed = z.preprocess((value) => {
  if (typeof value !== "string") {
    return undefined;
  }
  const trimmed = value.trim();
  return trimmed.length === 0 ? undefined : trimmed;
}, z.string().optional());

export const csvRowSchema = z
  .object({
    datetime: datetimeSchema,
    ticker: requiredUppercase,
    exchange: requiredUppercase,
    board: optionalUppercase,
    alias_source: optionalUppercase,
    side: sideSchema,
    quantity: positiveDecimal,
    price: positiveDecimal,
    currency: currencySchema,
    fee: nonNegativeDecimal,
    fee_currency: optionalCurrencySchema,
    tax: nonNegativeDecimal,
    tax_currency: optionalCurrencySchema,
    broker: optionalTrimmed,
    note: optionalTrimmed,
    ext_id: optionalTrimmed,
  })
  .superRefine((data, ctx) => {
    if (data.tax && !data.tax_currency) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message: "Tax currency required when tax is provided",
        path: ["tax_currency"],
      });
    }
  })
  .transform((data) => ({
    ...data,
    fee_currency: data.fee_currency ?? data.currency,
    tax_currency: data.tax ? data.tax_currency ?? data.currency : data.tax_currency,
  }));

export type ValidatedCsvRow = z.infer<typeof csvRowSchema>;

export function validateMappedRow(row: Record<string, unknown>) {
  return csvRowSchema.safeParse(row);
}
