package com.codeclassic.grubby.service.timeline;

import com.codeclassic.grubby.domain.model.ArchitectureSignal;
import com.codeclassic.grubby.domain.model.CommitRecord;
import com.codeclassic.grubby.domain.model.DiffSummary;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Scans commit file paths to detect when key architectural capabilities were first introduced.
 * Path-based detection only — no file content is read, so this runs purely on the stored
 * CommitRecord list and adds zero extra I/O.
 *
 * Each signal fires at most once (first occurrence), recording the date, author, and
 * triggering file so the timeline can show a clear "introduced on X" marker.
 */
@Service
public class ArchitectureEvolutionDetector {

    private record SignalDef(String type, String title, String[] patterns) {}

    private static final List<SignalDef> SIGNAL_DEFS = List.of(
            new SignalDef("CI_CD",         "CI/CD Pipeline Introduced",
                    new String[]{ ".github/workflows/", ".gitlab-ci.yml", "Jenkinsfile",
                            ".circleci/", "azure-pipelines.yml", ".travis.yml", ".drone.yml" }),

            new SignalDef("DOCKER",        "Containerisation Added",
                    new String[]{ "Dockerfile", "docker-compose.yml", "docker-compose.yaml",
                            ".dockerignore" }),

            new SignalDef("TESTING",       "Automated Testing Introduced",
                    new String[]{ "test.java", "tests.java", ".test.ts", ".test.js",
                            ".spec.ts", ".spec.js", "_test.go", "_test.py", "test_",
                            "/test/", "/tests/", "__tests__" }),

            new SignalDef("DATABASE",      "Database Migrations Added",
                    new String[]{ "db/migration/", "db/changelog/", "v1__", "r__",
                            "flyway", "liquibase", "changelog.xml", "migrate/" }),

            new SignalDef("API_SPEC",      "API Specification Added",
                    new String[]{ "openapi.yml", "openapi.yaml", "swagger.yml",
                            "swagger.yaml", "api-docs", "api-spec" }),

            new SignalDef("INFRASTRUCTURE","Infrastructure as Code",
                    new String[]{ ".tf", "kubernetes/", "/k8s/", "helm/",
                            "chart.yaml", "kustomization", "/.kube/" }),

            new SignalDef("SECURITY",      "Security Layer Introduced",
                    new String[]{ "securityconfig", "websecurityconfig", "jwtfilter",
                            "authfilter", "securityfilter", "jwtauthfilter" }),

            new SignalDef("MONITORING",    "Monitoring & Observability",
                    new String[]{ "actuator", "prometheus", "grafana", "jaeger",
                            "zipkin", "logback.xml", "log4j2.xml", "opentelemetry" }),

            new SignalDef("BUILD_TOOL",    "Build Automation Scripts",
                    new String[]{ "makefile", "taskfile", "build.sh", "scripts/",
                            "justfile", "procfile" })
    );

    public List<ArchitectureSignal> detect(List<CommitRecord> commits) {
        Set<String> seen = new HashSet<>();
        List<ArchitectureSignal> result = new ArrayList<>();

        for (CommitRecord commit : commits) {
            for (SignalDef def : SIGNAL_DEFS) {
                if (seen.contains(def.type())) continue;

                for (DiffSummary file : commit.changedFiles()) {
                    if (matchesAny(file.path(), def.patterns())) {
                        result.add(new ArchitectureSignal(
                                commit.date().toString(),
                                def.type(),
                                def.title(),
                                String.format("First seen in commit %s (%s) by %s — %s",
                                        commit.shortHash(), commit.date(),
                                        commit.author(), file.path())
                        ));
                        seen.add(def.type());
                        break;
                    }
                }
            }
            if (seen.size() == SIGNAL_DEFS.size()) break; // all signals found
        }

        result.sort(Comparator.comparing(ArchitectureSignal::date));
        return result;
    }

    private boolean matchesAny(String path, String[] patterns) {
        String lower = path.toLowerCase();
        for (String p : patterns) {
            if (lower.contains(p)) return true;
        }
        return false;
    }
}
