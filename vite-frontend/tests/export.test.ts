import { expect, it } from "vitest";
import { escapeCsvCell } from "@/lib/export";

it("escapes CSV separators, line breaks, quotes and spreadsheet formulas", () => {
  expect(escapeCsvCell('名称,"线路"\r\n地址')).toBe('"名称,""线路""\r\n地址"');
  expect(escapeCsvCell(' =HYPERLINK("https://example.test")')).toBe(
    '"\' =HYPERLINK(""https://example.test"")"',
  );
  expect(escapeCsvCell(-100)).toBe("-100");
  expect(escapeCsvCell(null)).toBe("");
});
