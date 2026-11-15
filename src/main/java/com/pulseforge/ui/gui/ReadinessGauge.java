package com.pulseforge.ui.gui;

import com.pulseforge.domain.ReadinessResult;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;

/** Circular readiness gauge (F03 dashboard widget). */
public class ReadinessGauge extends JComponent {

    private ReadinessResult result;

    public ReadinessGauge() {
        setPreferredSize(new Dimension(240, 240));
    }

    public void setResult(ReadinessResult result) {
        this.result = result;
        repaint();
    }

    static Color tierColor(ReadinessResult r) {
        if (r == null) {
            return Color.GRAY;
        }
        return switch (r.tier()) {
            case PRIME -> new Color(0x2E, 0x9E, 0x5B);
            case BALANCED -> new Color(0x2F, 0x80, 0xC2);
            case FATIGUED -> new Color(0xE0, 0x93, 0x1F);
            case DEPLETED -> new Color(0xC9, 0x3B, 0x3B);
        };
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int size = Math.min(getWidth(), getHeight()) - 30;
        int x = (getWidth() - size) / 2;
        int y = (getHeight() - size) / 2;
        g2.setStroke(new BasicStroke(16, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setColor(new Color(225, 228, 232));
        g2.draw(new Arc2D.Double(x, y, size, size, 0, 360, Arc2D.OPEN));
        String big = "--";
        String small = "No data";
        if (result != null) {
            g2.setColor(tierColor(result));
            g2.draw(new Arc2D.Double(x, y, size, size, 90, -360 * result.score() / 100.0, Arc2D.OPEN));
            big = String.valueOf(Math.round(result.score()));
            small = result.tier().label() + (result.approximation() ? " (approx.)" : "");
        }
        g2.setColor(getForeground());
        g2.setFont(getFont().deriveFont(Font.BOLD, size / 3.2f));
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(big, (getWidth() - fm.stringWidth(big)) / 2, getHeight() / 2 + fm.getAscent() / 3);
        g2.setFont(getFont().deriveFont(Font.PLAIN, 14f));
        fm = g2.getFontMetrics();
        g2.drawString(small, (getWidth() - fm.stringWidth(small)) / 2, getHeight() / 2 + size / 4);
        g2.dispose();
    }
}
