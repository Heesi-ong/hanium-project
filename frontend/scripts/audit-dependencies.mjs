import { execFileSync, spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const CURRENT_DIR = path.dirname(fileURLToPath(import.meta.url));
const FRONTEND_ROOT = path.resolve(CURRENT_DIR, "..");
const SOURCE_ROOT = path.join(FRONTEND_ROOT, "src");

const ALLOWED_ADVISORY_URL =
    "https://github.com/advisories/GHSA-qwww-vcr4-c8h2";
const ALLOWED_PACKAGES = new Set(["react-router", "react-router-dom"]);
const SOURCE_EXTENSIONS = new Set([".js", ".jsx", ".ts", ".tsx"]);

function collectSourceFiles(directory) {
    return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
        const entryPath = path.join(directory, entry.name);
        if (entry.isDirectory()) {
            return collectSourceFiles(entryPath);
        }
        return SOURCE_EXTENSIONS.has(path.extname(entry.name)) ? [entryPath] : [];
    });
}

function assertNoRscUsage() {
    const forbiddenPatterns = [
        {
            pattern: /["']react-router(?:\/[^"']*)?["']/,
            reason: "react-router 직접/서버/RSC import",
        },
        {
            pattern: /\b(?:RSCRouter|createCallServer|routeRSCServerRequest)\b/,
            reason: "unstable RSC API 사용",
        },
    ];

    const violations = [];
    for (const filePath of collectSourceFiles(SOURCE_ROOT)) {
        const source = fs.readFileSync(filePath, "utf8");
        for (const { pattern, reason } of forbiddenPatterns) {
            if (pattern.test(source)) {
                violations.push(
                    `${path.relative(FRONTEND_ROOT, filePath)}: ${reason}`
                );
            }
        }
    }

    if (violations.length > 0) {
        throw new Error(
            "허용 중인 React Router advisory는 unstable RSC 미사용을 전제로 합니다.\n"
                + violations.join("\n")
        );
    }
}

function readInstalledReactRouterVersion() {
    const output = execFileSync(
        process.execPath,
        [
            "-e",
            "console.log(require('react-router-dom/package.json').version)",
        ],
        {
            cwd: FRONTEND_ROOT,
            encoding: "utf8",
        }
    );
    return output.trim();
}

function isAllowedVulnerability(name, vulnerability, report) {
    if (!ALLOWED_PACKAGES.has(name)) {
        return false;
    }

    return vulnerability.via.every((via) => {
        if (typeof via === "string") {
            return ALLOWED_PACKAGES.has(via)
                && report.vulnerabilities[via]
                && isAllowedVulnerability(
                    via,
                    report.vulnerabilities[via],
                    report
                );
        }
        return via.url === ALLOWED_ADVISORY_URL;
    });
}

function runAudit() {
    const npmCommand = process.platform === "win32" ? "npm.cmd" : "npm";
    const audit = spawnSync(
        npmCommand,
        ["audit", "--json"],
        {
            cwd: FRONTEND_ROOT,
            encoding: "utf8",
            maxBuffer: 10 * 1024 * 1024,
        }
    );

    if (!audit.stdout) {
        throw new Error(
            `npm audit 결과를 받지 못했습니다. ${audit.stderr || ""}`.trim()
        );
    }

    let report;
    try {
        report = JSON.parse(audit.stdout);
    } catch {
        throw new Error(
            `npm audit JSON을 해석하지 못했습니다. ${audit.stderr || ""}`.trim()
        );
    }

    if (report.error) {
        throw new Error(
            `npm audit 실행 실패: ${report.error.summary || report.error}`
        );
    }

    const unexpected = Object.entries(report.vulnerabilities || {})
        .filter(([name, vulnerability]) =>
            !isAllowedVulnerability(name, vulnerability, report)
        )
        .map(([name, vulnerability]) =>
            `${name} (${vulnerability.severity})`
        );

    if (unexpected.length > 0) {
        throw new Error(
            `허용되지 않은 npm advisory가 발견됐습니다: ${unexpected.join(", ")}`
        );
    }

    return report;
}

assertNoRscUsage();

const installedVersion = readInstalledReactRouterVersion();
if (!/^7\.18\.\d+$/.test(installedVersion)) {
    throw new Error(
        "React Router advisory 예외는 검토된 7.18.x에서만 허용합니다. "
            + `현재 버전=${installedVersion}`
    );
}

const report = runAudit();
const total = report.metadata?.vulnerabilities?.total || 0;

if (total === 0) {
    console.log("npm audit 통과: 알려진 취약점 0건");
} else {
    console.log(
        "npm audit 조건부 통과: unstable RSC 미사용을 확인했고 "
            + `${ALLOWED_ADVISORY_URL}만 허용했습니다. `
            + `React Router ${installedVersion}, audit 항목 ${total}건`
    );
}
