/*
 * Copyright (c) 2026, Philippe Riand. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Philippe Riand designates this
 * particular file as subject to the "Classpath" exception as provided
 * in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package org.monflabs.nashorn.performance;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.monflabs.nashorn.performance.ScriptExecutor.ENGINE;

/**
 * Collects one wall/cpu time measurement per (suite, file, engine) and renders it as CSV, as
 * a console table, or as a self-contained HTML report with inline SVG bar charts. A
 * measurement's {@link Status} distinguishes "the engine doesn't support this mode on this
 * JVM" from "the engine attempted the benchmark and threw" - both leave the matrix without a
 * number, but they mean different things.
 */
public class BenchmarkCollector {

    public enum Status {
        OK, FAILED, NOT_AVAILABLE
    }

    private static final ENGINE[] ENGINES = ENGINE.values();

    // Indexed by ENGINE.ordinal(); one fixed color per engine so the same engine reads the same
    // color across every chart in the report.
    private static final String[] ENGINE_COLORS = {
            "#2563eb", "#7c3aed", "#059669", "#10b981", "#d97706", "#dc2626", "#0891b2"
    };

    private static final int CHART_WIDTH = 460;
    private static final int CHART_HEIGHT = 90;
    private static final int VALUE_LABEL_HEIGHT = 14;
    private static final int BAR_GAP = 6;

    public static class Result {
        public static String makeKey(String suite, String file) {
            return suite + "::" + file;
        }

        final String suite;
        final String file;
        final Status[] status = new Status[ENGINES.length];
        final long[] wallTimeMs = new long[ENGINES.length];
        final long[] cpuTimeMs = new long[ENGINES.length];
        final Double[] score = new Double[ENGINES.length];

        Result(String suite, String file) {
            this.suite = suite;
            this.file = file;
        }
    }

    private final Map<String, Result> results = new LinkedHashMap<>();

    /**
     * @param score the benchmark's own internally computed score (see
     *              {@link org.monflabs.nashorn.performance.ScriptExecutor#getLastScore()}) -
     *              {@code null} when the suite has no such convention (SunSpider, ubench) or the
     *              run didn't produce one.
     */
    public void addResult(String suite, String file, ENGINE engine, Status status, long wallTimeMs, long cpuTimeMs,
            Double score) {
        String key = Result.makeKey(suite, file);
        Result r = results.computeIfAbsent(key, k -> new Result(suite, file));
        r.status[engine.ordinal()] = status;
        r.wallTimeMs[engine.ordinal()] = wallTimeMs;
        r.cpuTimeMs[engine.ordinal()] = cpuTimeMs;
        r.score[engine.ordinal()] = score;
    }

    private Result[] sortedResults() {
        Result[] list = results.values().toArray(new Result[0]);
        Arrays.sort(list, (r1, r2) -> {
            int v = r1.suite.compareTo(r2.suite);
            return v != 0 ? v : r1.file.compareTo(r2.file);
        });
        return list;
    }

    private static String cell(Status status, long value) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case OK -> Long.toString(value);
            case FAILED -> "FAILED";
            case NOT_AVAILABLE -> "N/A";
        };
    }

    /** Octane/v8-benchmarks-v6's own significant-digit formatting (higher is better) - see
     *  BenchmarkSuite.FormatScore in their vendored base.js. */
    private static String formatScore(double value) {
        return value > 100 ? Long.toString(Math.round(value)) : String.format("%.3g", value);
    }

    private static String scoreCell(Status status, Double score) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case OK -> score == null ? "" : formatScore(score);
            case FAILED -> "FAILED";
            case NOT_AVAILABLE -> "N/A";
        };
    }

    public String csv() {
        StringBuilder b = new StringBuilder();
        b.append("Suite,File");
        for (ENGINE engine : ENGINES) {
            b.append(',').append(engine.name()).append(" WallTime(ms)");
            b.append(',').append(engine.name()).append(" CpuTime(ms)");
            b.append(',').append(engine.name()).append(" Score");
        }
        b.append('\n');

        for (Result r : sortedResults()) {
            b.append(r.suite).append(',').append(r.file);
            for (int i = 0; i < ENGINES.length; i++) {
                b.append(',').append(cell(r.status[i], r.wallTimeMs[i]));
                b.append(',').append(cell(r.status[i], r.cpuTimeMs[i]));
                b.append(',').append(scoreCell(r.status[i], r.score[i]));
            }
            b.append('\n');
        }
        return b.toString();
    }

    public String toConsoleTable() {
        StringBuilder b = new StringBuilder();
        Result[] list = sortedResults();

        int fileWidth = "File".length();
        for (Result r : list) {
            fileWidth = Math.max(fileWidth, r.file.length());
        }
        int colWidth = 14;

        b.append(String.format("%-10s %-" + fileWidth + "s", "Suite", "File"));
        for (ENGINE engine : ENGINES) {
            b.append(String.format(" %" + colWidth + "s", engine.name()));
        }
        b.append('\n');

        for (Result r : list) {
            b.append(String.format("%-10s %-" + fileWidth + "s", r.suite, r.file));
            for (int i = 0; i < ENGINES.length; i++) {
                b.append(String.format(" %" + colWidth + "s", cell(r.status[i], r.wallTimeMs[i])));
            }
            b.append('\n');
        }
        return b.toString();
    }

    /**
     * A self-contained HTML report (no external stylesheet, script or image) - a data table
     * identical in content to {@link #csv()}, followed by one bar chart per (suite, file)
     * comparing every engine's wall time, one bar per engine, scaled to that row's own highest
     * value so a fast and a slow benchmark are both readable on their own chart.
     */
    public String toHtmlReport() {
        Result[] list = sortedResults();
        StringBuilder b = new StringBuilder();
        b.append("<!DOCTYPE html>\n<html><head><meta charset=\"UTF-8\">")
         .append("<title>JavaScript engines performance report</title>\n")
         .append("<style>\n")
         .append("body{font-family:sans-serif;margin:2em;color:#111}\n")
         .append("table{border-collapse:collapse;margin-bottom:2em}\n")
         .append("th,td{border:1px solid #ccc;padding:4px 8px;font-size:13px;text-align:right}\n")
         .append("th:first-child,th:nth-child(2),td:first-child,td:nth-child(2){text-align:left}\n")
         .append(".legend{margin-bottom:1.5em;font-size:13px}\n")
         .append(".legend span{display:inline-block;margin-right:1.2em}\n")
         .append(".swatch{display:inline-block;width:10px;height:10px;margin-right:4px}\n")
         .append(".row{display:flex;align-items:center;margin-bottom:4px}\n")
         .append(".row-label{width:220px;font-size:13px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}\n")
         .append("h2{margin-top:2em}\n")
         .append("</style></head><body>\n")
         .append("<h1>JavaScript engines performance report</h1>\n");

        appendLegend(b);
        appendTable(b, list);
        appendCharts(b, list);

        b.append("</body></html>\n");
        return b.toString();
    }

    private void appendLegend(StringBuilder b) {
        b.append("<div class=\"legend\">");
        for (ENGINE engine : ENGINES) {
            b.append("<span><span class=\"swatch\" style=\"background:").append(ENGINE_COLORS[engine.ordinal()])
             .append("\"></span>").append(escapeHtml(engine.name())).append("</span>");
        }
        b.append("</div>\n");
    }

    private void appendTable(StringBuilder b, Result[] list) {
        b.append("<table>\n<tr><th>Suite</th><th>File</th>");
        for (ENGINE engine : ENGINES) {
            b.append("<th>").append(escapeHtml(engine.name())).append(" wall(ms)</th>");
            b.append("<th>").append(escapeHtml(engine.name())).append(" score</th>");
        }
        b.append("</tr>\n");
        for (Result r : list) {
            b.append("<tr><td>").append(escapeHtml(r.suite)).append("</td><td>").append(escapeHtml(r.file)).append("</td>");
            for (int i = 0; i < ENGINES.length; i++) {
                b.append("<td>").append(escapeHtml(cell(r.status[i], r.wallTimeMs[i]))).append("</td>");
                b.append("<td>").append(escapeHtml(scoreCell(r.status[i], r.score[i]))).append("</td>");
            }
            b.append("</tr>\n");
        }
        b.append("</table>\n");
    }

    private void appendCharts(StringBuilder b, Result[] list) {
        String currentSuite = null;
        for (Result r : list) {
            if (!r.suite.equals(currentSuite)) {
                currentSuite = r.suite;
                b.append("<h2>").append(escapeHtml(currentSuite)).append("</h2>\n");
            }
            b.append("<div class=\"row\"><div class=\"row-label\" title=\"").append(escapeHtml(r.file)).append("\">")
             .append(escapeHtml(r.file)).append("</div>");
            appendChart(b, r);
            b.append("</div>\n");
        }
    }

    private void appendChart(StringBuilder b, Result r) {
        long max = 0;
        for (int i = 0; i < ENGINES.length; i++) {
            if (r.status[i] == Status.OK) {
                max = Math.max(max, r.wallTimeMs[i]);
            }
        }
        int n = ENGINES.length;
        int barWidth = (CHART_WIDTH - (n + 1) * BAR_GAP) / n;
        int svgHeight = CHART_HEIGHT + VALUE_LABEL_HEIGHT;

        b.append("<svg width=\"").append(CHART_WIDTH).append("\" height=\"").append(svgHeight).append("\">\n");
        for (int i = 0; i < n; i++) {
            int x = BAR_GAP + i * (barWidth + BAR_GAP);
            ENGINE engine = ENGINES[i];
            String color = ENGINE_COLORS[i];
            Status status = r.status[i];
            b.append("<g><title>").append(escapeHtml(engine.name())).append(": ")
             .append(escapeHtml(cell(status, r.wallTimeMs[i]))).append(status == Status.OK ? "ms" : "").append("</title>");
            if (status == Status.OK && max > 0) {
                int barHeight = (int) Math.max(2, Math.round((double) r.wallTimeMs[i] / max * CHART_HEIGHT));
                int y = VALUE_LABEL_HEIGHT + (CHART_HEIGHT - barHeight);
                b.append("<rect x=\"").append(x).append("\" y=\"").append(y)
                 .append("\" width=\"").append(barWidth).append("\" height=\"").append(barHeight)
                 .append("\" fill=\"").append(color).append("\"/>")
                 .append("<text x=\"").append(x + barWidth / 2.0).append("\" y=\"").append(y - 3)
                 .append("\" font-size=\"9\" text-anchor=\"middle\">").append(r.wallTimeMs[i]).append("</text>");
            } else {
                String label = status == null ? "" : (status == Status.FAILED ? "FAILED" : "N/A");
                b.append("<text x=\"").append(x + barWidth / 2.0).append("\" y=\"").append(VALUE_LABEL_HEIGHT + CHART_HEIGHT - 4)
                 .append("\" font-size=\"9\" text-anchor=\"middle\" fill=\"#999\">").append(label).append("</text>");
            }
            b.append("</g>\n");
        }
        b.append("</svg>\n");
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
