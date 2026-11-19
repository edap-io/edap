/*
 * Copyright 2026 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.grpc.benchmark;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 柱状图渲染 —— <b>ASCII 版</b>（进 markdown 正文）+ <b>PNG 版</b>（Java2D，JDK 自带无三方依赖）。
 *
 * <p>★ <b>图里的文字一律用 ASCII</b>：Java2D 取不到中文字形时会画成方框/空白，
 * 与其出一张半残的图，不如把标签写成 {@code eRPC-FAST} / {@code ascii-0B-c1} 这种短标识。</p>
 *
 * <p>★ <b>ASCII 版用 {@code █}（U+2588）画条</b>：等宽终端里宽度稳定，
 * 任何 markdown 渲染器/浏览器都能看 ⇒ 即使 PNG 没生成出来，报告正文也自带图。</p>
 */
public final class BenchChart {

    /**
     * 一组柱 —— 一个场景下的若干个「臂」。
     */
    public static final class Group {
        /** 组标题（场景名，例如 {@code ascii-0B-c8}）。 */
        public final String title;
        /** 每根柱的标签（臂名）。 */
        public final String[] labels;
        /** 每根柱的值。 */
        public final double[] values;

        public Group(String title, String[] labels, double[] values) {
            this.title = title;
            this.labels = labels;
            this.values = values;
        }
    }

    /** 条形图的字符宽度（每根条最多多少个字符）。 */
    private static final int BAR_WIDTH = 34;

    private BenchChart() {
    }

    // =====================================================================
    // ASCII 版
    // =====================================================================

    /**
     * 渲染成 ASCII 条形图。
     *
     * @param groups   分组
     * @param valueFmt  值的格式串（{@link String#format}）
     */
    public static String ascii(List<Group> groups, String valueFmt) {
        StringBuilder sb = new StringBuilder();
        // 每组独立归一化（组内比大小），并标出组内最大值
        for (Group g : groups) {
            double max = 0;
            for (double v : g.values) {
                max = Math.max(max, v);
            }
            sb.append(g.title).append("  (max=").append(String.format(valueFmt, max)).append(")\n");
            int labelWidth = 0;
            for (String l : g.labels) {
                labelWidth = Math.max(labelWidth, l.length());
            }
            for (int i = 0; i < g.labels.length; i++) {
                double v = g.values[i];
                int cells = max <= 0 ? 0 : (int) Math.round(v / max * BAR_WIDTH);
                StringBuilder bar = new StringBuilder();
                for (int c = 0; c < cells; c++) {
                    bar.append('█');
                }
                if (cells == 0 && v > 0) {
                    bar.append('▏');
                }
                sb.append(String.format("  %-" + labelWidth + "s %s %s%n",
                    g.labels[i], bar, String.format(valueFmt, v)));
            }
        }
        return sb.toString();
    }

    // =====================================================================
    // PNG 版（Java2D）
    // =====================================================================

    /** 每根柱的配色（按臂下标循环）。 */
    private static final Color[] PALETTE = {
        new Color(0x2E6FDB), new Color(0xE07B39), new Color(0x3FA34D), new Color(0xB0489B),
        new Color(0x8A8F98), new Color(0xC9A227)
    };

    /**
     * 渲染成分组柱状图 PNG。
     *
     * @param groups   分组
     * @param title    图标题（ASCII）
     * @param yLabel   纵轴含义（ASCII）
     * @param file     输出文件
     */
    public static void png(List<Group> groups, String title, String yLabel, Path file) throws IOException {
        png(groups, title, yLabel, null, file);
    }

    /**
     * 渲染成分组柱状图 PNG（带柱顶数值标签）。
     *
     * @param valueFmt 柱顶标签的格式串；{@code null} ⇒ 不画标签
     */
    public static void png(List<Group> groups, String title, String yLabel, String valueFmt, Path file)
        throws IOException {
        int groupWidth = 150;
        int groupHeight = 110;
        int left = 90;
        int right = 30;
        int top = 70;
        int bottom = 70;
        int legendHeight = 26;

        int arms = groups.isEmpty() ? 0 : groups.get(0).labels.length;
        int plotWidth = groups.size() * groupWidth;
        int width = left + plotWidth + right;
        int height = top + groupHeight + bottom + legendHeight;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);

            g.setColor(new Color(0x1A1A1A));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
            g.drawString(title, left, 26);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            g.setColor(new Color(0x666666));
            g.drawString(yLabel, left, 44);

            // 纵轴的最大值
            double max = 0;
            for (Group grp : groups) {
                for (double v : grp.values) {
                    max = Math.max(max, v);
                }
            }
            if (max <= 0) {
                max = 1;
            }

            // 网格 + 纵轴刻度
            g.setStroke(new java.awt.BasicStroke(1f));
            int ticks = 4;
            for (int t = 0; t <= ticks; t++) {
                double frac = t / (double) ticks;
                int y = top + groupHeight - (int) Math.round(frac * groupHeight);
                g.setColor(new Color(0xDDDDDD));
                g.drawLine(left, y, left + plotWidth, y);
                g.setColor(new Color(0x555555));
                g.drawString(String.format("%.0f", max * frac), 6, y + 4);
            }

            // 柱
            int barArea = groupWidth - 24;
            int barWidth = Math.max(6, barArea / Math.max(1, arms) - 6);
            Font valueFont = new Font(Font.SANS_SERIF, Font.PLAIN, 10);
            for (int gi = 0; gi < groups.size(); gi++) {
                Group grp = groups.get(gi);
                int gx = left + gi * groupWidth;
                for (int ai = 0; ai < grp.values.length; ai++) {
                    double v = grp.values[ai];
                    int h = (int) Math.round(v / max * groupHeight);
                    int x = gx + 12 + ai * (barWidth + 6);
                    int y = top + groupHeight - h;
                    g.setColor(PALETTE[ai % PALETTE.length]);
                    g.fillRect(x, y, barWidth, Math.max(1, h));
                    if (valueFmt != null) {
                        g.setFont(valueFont);
                        g.setColor(new Color(0x444444));
                        String text = String.format(valueFmt, v);
                        int tw = g.getFontMetrics().stringWidth(text);
                        g.drawString(text, x + (barWidth - tw) / 2, Math.max(top + 9, y - 3));
                    }
                }
                // 组标题
                g.setColor(new Color(0x333333));
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
                String label = grp.title;
                int lw = g.getFontMetrics().stringWidth(label);
                g.drawString(label, gx + Math.max(2, (groupWidth - lw) / 2), top + groupHeight + 18);
            }

            // 图例
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            int lx = left;
            int ly = top + groupHeight + bottom + 4;
            for (int ai = 0; ai < arms; ai++) {
                g.setColor(PALETTE[ai % PALETTE.length]);
                g.fillRect(lx, ly - 9, 11, 11);
                g.setColor(new Color(0x333333));
                String name = groups.get(0).labels[ai];
                g.drawString(name, lx + 16, ly);
                lx += 16 + g.getFontMetrics().stringWidth(name) + 24;
            }

            // 坐标轴
            g.setColor(new Color(0x888888));
            g.drawLine(left, top, left, top + groupHeight);
            g.drawLine(left, top + groupHeight, left + plotWidth, top + groupHeight);
        } finally {
            g.dispose();
        }

        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ImageIO.write(image, "png", file.toFile());
    }
}
