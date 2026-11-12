// Launches the CDK app through the Maven wrapper in a way that works on Windows and Linux.
//
// `cdk.json` runs this file as "node scripts/cdk-app.mjs". A plain "./mvnw ..." entry cannot be
// portable: cmd.exe cannot start "./mvnw" and sh cannot start "mvnw.cmd". Node is always present
// because the CDK CLI itself needs it.
import { spawnSync } from "node:child_process";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const projectDir = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const windows = process.platform === "win32";
// absolute path: cmd.exe does not look in the working directory when started through Node
const wrapper = resolve(projectDir, windows ? "mvnw.cmd" : "mvnw");

// -q keeps Maven's own output out of the way; CDK_OUTDIR and CDK_CONTEXT_JSON are inherited.
const result = spawnSync(windows ? `"${wrapper}"` : wrapper, ["-q", "-B", "--no-transfer-progress", "compile", "exec:java"], {
  cwd: projectDir,
  stdio: "inherit",
  // .cmd files can only be started through a shell since Node 18.20 / 20.12
  shell: windows,
});

if (result.error) {
  console.error(`Could not start ${wrapper}: ${result.error.message}`);
  process.exit(1);
}
process.exit(result.status ?? 1);
