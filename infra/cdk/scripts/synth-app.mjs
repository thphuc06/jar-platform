// Cross-platform launcher used by cdk.json ("app"). It runs the Java CDK app through the Maven Wrapper:
// mvnw.cmd on Windows, ./mvnw elsewhere. An explicit path is used because Windows may be configured
// with NoDefaultCurrentDirectoryInExePath=1, in which case cmd.exe does not look for "mvnw" in the
// current directory.
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const projectDir = join(dirname(fileURLToPath(import.meta.url)), "..");
const isWindows = process.platform === "win32";
const wrapper = join(projectDir, isWindows ? "mvnw.cmd" : "mvnw");

const result = spawnSync(wrapper, ["-e", "-q", "compile", "exec:java"], {
  cwd: projectDir,
  stdio: "inherit",
  shell: isWindows, // .cmd files must be started through cmd.exe
});
process.exit(result.status ?? 1);
