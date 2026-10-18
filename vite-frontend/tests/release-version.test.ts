import { expect, it } from "vitest";
import { compareVersions, selectRelease } from "../src/utils/release-version";
it("orders beta numbers and stable versions correctly", () => {
  expect(compareVersions("2.84.0-beta.18", "2.84.0-beta.16")).toBeGreaterThan(0);
  expect(compareVersions("2.84.0-beta.10", "2.84.0-beta.9")).toBeGreaterThan(0);
  expect(compareVersions("2.84.0", "2.84.0-beta.18")).toBeGreaterThan(0);
});
it("offers published beta updates to beta installations while stable stays stable", () => {
  const releases = [{tag_name:"2.83.3"}, {tag_name:"2.84.0-beta.18",prerelease:true},
    {tag_name:"2.84.0-beta.19",prerelease:true,draft:true}];
  expect(selectRelease(releases,"2.84.0-beta.16")?.tag_name).toBe("2.84.0-beta.18");
  expect(selectRelease(releases,"2.83.3")?.tag_name).toBe("2.83.3");
});
