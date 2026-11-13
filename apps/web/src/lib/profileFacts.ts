/**
 * The two facts asked after the five risk questions: age band and income situation. They are profile facts for the
 * advisor, not part of the risk score. The value of an option is the CODE jars-api stores and validates (the
 * `UpdateMeRequest` of contracts/openapi/jars-api.yaml); the label is free to change. A band, never a birth date.
 */
export interface FactOption<T extends string> {
  value: T;
  label: string;
}

export type AgeBand = "UNDER_25" | "AGE_25_34" | "AGE_35_44" | "AGE_45_54" | "AGE_55_PLUS";
export type IncomeSituation = "REGULAR_SALARY" | "IRREGULAR_INCOME" | "NO_INCOME_YET";

export const AGE_BANDS: readonly FactOption<AgeBand>[] = [
  { value: "UNDER_25", label: "Dưới 25 tuổi" },
  { value: "AGE_25_34", label: "25-34 tuổi" },
  { value: "AGE_35_44", label: "35-44 tuổi" },
  { value: "AGE_45_54", label: "45-54 tuổi" },
  { value: "AGE_55_PLUS", label: "Từ 55 tuổi trở lên" },
];

export const INCOME_SITUATIONS: readonly FactOption<IncomeSituation>[] = [
  { value: "REGULAR_SALARY", label: "Lương đều đặn hằng tháng" },
  { value: "IRREGULAR_INCOME", label: "Thu nhập không đều (tự do, hoa hồng, thời vụ)" },
  { value: "NO_INCOME_YET", label: "Chưa có thu nhập" },
];

export interface ProfileFacts {
  ageBand: AgeBand;
  incomeSituation: IncomeSituation;
}

/** The body of `PATCH /me` for the two chosen options (indexes into the lists above), or null while one is missing. */
export function buildProfileFacts(ageIndex: number | undefined, incomeIndex: number | undefined): ProfileFacts | null {
  const age = ageIndex === undefined ? undefined : AGE_BANDS[ageIndex];
  const income = incomeIndex === undefined ? undefined : INCOME_SITUATIONS[incomeIndex];
  return age && income ? { ageBand: age.value, incomeSituation: income.value } : null;
}

/** The label of a code the server sent, `null` when the user has not answered (or the code is one this client does not know). */
export function ageBandLabel(code: string | null | undefined): string | null {
  return AGE_BANDS.find((o) => o.value === code)?.label ?? null;
}

export function incomeSituationLabel(code: string | null | undefined): string | null {
  return INCOME_SITUATIONS.find((o) => o.value === code)?.label ?? null;
}
