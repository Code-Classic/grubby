package com.codeclassic.grubby.service.analysis;

import com.codeclassic.grubby.domain.model.CodeAnalysisSummary;
import com.codeclassic.grubby.domain.model.EndpointDescriptor;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * AST-based analyzer using JavaParser to extract Spring MVC endpoints and relevant files.
 */
@Service
public class CodeAnalyzerService {

    private static final Logger log = LoggerFactory.getLogger(CodeAnalyzerService.class);

    private static final Set<String> CONTROLLER_ANNOTATIONS = Set.of("RestController", "Controller");

    public CodeAnalysisSummary analyze(Path repoRoot,
                                       List<Path> javaFiles,
                                       String featureContext,
                                       RepoFileWalker walker) {
        List<EndpointDescriptor> endpoints = new ArrayList<>();
        List<CodeAnalysisSummary.RelevantFile> relevant = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        for (Path p : javaFiles) {
            try {
                String code = Files.readString(p);
                CompilationUnit cu = StaticJavaParser.parse(code);

                // Track if this file contains a controller to include in relevant files
                boolean fileHasController = false;

                for (ClassOrInterfaceDeclaration clazz : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                    if (!isController(clazz)) continue;
                    fileHasController = true;

                    String basePath = extractClassLevelPath(clazz).orElse("");
                    String controllerName = clazz.getNameAsString();

                    for (MethodDeclaration method : clazz.getMethods()) {
                        List<EndpointDescriptor> methodEndpoints = extractMethodEndpoints(controllerName, basePath, method);
                        endpoints.addAll(methodEndpoints);
                    }
                }

                if (fileHasController) {
                    String rel = repoRoot.relativize(p).toString();
                    String head = safeHead(walker, p);
                    relevant.add(new CodeAnalysisSummary.RelevantFile(rel, 100, head));
                }
            } catch (Exception e) {
                // Tolerate parse errors and continue
                log.debug("AST parse failed for {}: {}", p, e.getMessage());
            }
        }

        if (endpoints.isEmpty()) {
            notes.add("No endpoints found by AST analyzer; the project may not be Spring MVC or annotations may be unconventional.");
        }

        return new CodeAnalysisSummary(endpoints, relevant, notes);
    }

    private boolean isController(ClassOrInterfaceDeclaration clazz) {
        for (AnnotationExpr ann : clazz.getAnnotations()) {
            String name = ann.getNameAsString();
            if (CONTROLLER_ANNOTATIONS.contains(name)) return true;
        }
        return false;
    }

    private Optional<String> extractClassLevelPath(ClassOrInterfaceDeclaration clazz) {
        for (AnnotationExpr ann : clazz.getAnnotations()) {
            String name = ann.getNameAsString();
            if ("RequestMapping".equals(name)) {
                return extractPathFromAnnotation(ann);
            }
        }
        return Optional.empty();
    }

    private List<EndpointDescriptor> extractMethodEndpoints(String controllerName,
                                                            String basePath,
                                                            MethodDeclaration method) {
        List<EndpointDescriptor> out = new ArrayList<>();
        for (AnnotationExpr ann : method.getAnnotations()) {
            String name = ann.getNameAsString();
            switch (name) {
                case "GetMapping":
                case "PostMapping":
                case "PutMapping":
                case "DeleteMapping":
                case "PatchMapping": {
                    String http = name.replace("Mapping", "").toUpperCase();
                    String path = extractPathFromAnnotation(ann).orElse("");
                    out.add(new EndpointDescriptor(http, concatPaths(basePath, path), controllerName, method.getNameAsString(), "Detected via annotations"));
                    break;
                }
                case "RequestMapping": {
                    String http = extractHttpMethodFromRequestMapping(ann).orElse("GET");
                    String path = extractPathFromAnnotation(ann).orElse("");
                    out.add(new EndpointDescriptor(http, concatPaths(basePath, path), controllerName, method.getNameAsString(), "Detected via RequestMapping"));
                    break;
                }
                default:
                    // ignore others
            }
        }
        return out;
    }

    private Optional<String> extractPathFromAnnotation(AnnotationExpr ann) {
        if (ann instanceof SingleMemberAnnotationExpr sma) {
            return Optional.ofNullable(stripQuotes(sma.getMemberValue().toString()));
        }
        if (ann instanceof NormalAnnotationExpr na) {
            for (MemberValuePair p : na.getPairs()) {
                String n = p.getNameAsString();
                if (n.equals("value") || n.equals("path")) {
                    return Optional.ofNullable(stripQuotes(p.getValue().toString()));
                }
            }
        }
        return Optional.empty();
    }

    private String stripQuotes(String s) {
        if (s == null) return null;
        String t = s.trim();
        if ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'"))) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    private Optional<String> extractHttpMethodFromRequestMapping(AnnotationExpr ann) {
        if (ann instanceof NormalAnnotationExpr na) {
            for (MemberValuePair p : na.getPairs()) {
                if (p.getNameAsString().equals("method")) {
                    Expression v = p.getValue();
                    // Could be a single value or an array of RequestMethod enums
                    if (v.isFieldAccessExpr()) {
                        String enumName = v.asFieldAccessExpr().getNameAsString();
                        return Optional.of(enumName);
                    }
                    if (v.isArrayInitializerExpr()) {
                        NodeList<Expression> values = v.asArrayInitializerExpr().getValues();
                        if (!values.isEmpty() && values.get(0).isFieldAccessExpr()) {
                            return Optional.of(values.get(0).asFieldAccessExpr().getNameAsString());
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private String concatPaths(String base, String path) {
        String b = base == null ? "" : base.trim();
        String p = path == null ? "" : path.trim();
        if (b.isEmpty()) return normalizePath(p);
        if (p.isEmpty()) return normalizePath(b);
        String s = (b.endsWith("/") ? b.substring(0, b.length()-1) : b) + (p.startsWith("/") ? "" : "/") + p;
        return normalizePath(s);
    }

    private String normalizePath(String s) {
        if (s == null || s.isBlank()) return "/";
        return s.replaceAll("//+", "/");
    }

    private String safeHead(RepoFileWalker walker, Path p) {
        try {
            return walker.readHead(p);
        } catch (IOException e) {
            return "";
        }
    }
}
