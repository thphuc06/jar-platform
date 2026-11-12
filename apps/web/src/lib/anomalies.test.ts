import { describe, expect, it } from "vitest";
import { anomalyHeadline } from "./anomalies";

describe("anomalyHeadline", () => {
  it("names a single outlier found by MAD", () => {
    expect(anomalyHeadline({ detector: "MAD", direction: "UP", jarName: "Hưởng thụ" })).toBe("Khoản chi đột biến ở hũ Hưởng thụ");
  });

  it("names a sustained shift found by CUSUM in either direction", () => {
    expect(anomalyHeadline({ detector: "CUSUM", direction: "UP", jarName: "Thiết yếu" })).toBe("Chi tiêu tăng kéo dài ở hũ Thiết yếu");
    expect(anomalyHeadline({ detector: "CUSUM", direction: "DOWN", jarName: "Thiết yếu" })).toBe("Chi tiêu giảm kéo dài ở hũ Thiết yếu");
  });

  it("copes with a missing jar name", () => {
    expect(anomalyHeadline({ detector: "MAD", direction: "UP", jarName: "" })).toBe("Khoản chi đột biến");
  });
});
