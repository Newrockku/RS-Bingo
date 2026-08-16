package com.rsbingo;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.text.DefaultCaret;
import javax.swing.JPanel;
import net.runelite.client.ui.FontManager;

/**
 * The site's look, in Swing.
 *
 * Colours are the "Obsidian Gold" theme from js/themes.js — the project's default
 * and the one every screenshot is taken in — rather than anything invented here.
 * The names map one-to-one onto the CSS custom properties so the two can be
 * compared: {@code --bg-well} is {@link #BG_WELL}, {@code --accent} is
 * {@link #ACCENT}, and so on.
 *
 * The visual grammar is the same as the modal's: gold uppercase section labels
 * above near-black rounded "wells" that hold the content.
 */
final class Brand
{
	// The live palette. Not final: the panel can be switched to any of the site's
	// themes at runtime. These starting values are Obsidian Gold, the site's default,
	// and are what the panel falls back to if the theme endpoint can't be reached —
	// so a missing server means "looks as shipped", never "loses its styling".
	/** --bg-tile: the panel itself. */
	static Color BG_TILE = new Color(0x141410);
	/** --bg-well: recessed boxes inside it. */
	static Color BG_WELL = new Color(0x040404);
	/** --bg-completed-well: the same box on a finished tile. */
	static Color BG_COMPLETED_WELL = new Color(0x1A1408);
	/** --border-main */
	static Color BORDER = new Color(0x2A2820);
	/** --accent */
	static Color ACCENT = new Color(0xD8A830);
	/** --text-main */
	static Color TEXT_MAIN = new Color(0xC09828);
	/** --text-bright */
	static Color TEXT_BRIGHT = new Color(0xF0D888);
	/** --text-dim */
	static Color TEXT_DIM = new Color(0x706858);
	/** --text-completed */
	static Color COMPLETED = new Color(0xD8A830);

	/**
	 * Repaints the palette from one of the site's themes.
	 *
	 * Keys are the CSS custom properties as js/themes.js writes them, so the mapping
	 * below is the whole translation layer between the website's styling and this
	 * panel's. Anything absent keeps its current value rather than defaulting to
	 * black, which is what a partial theme would otherwise produce.
	 */
	static void applyPalette(java.util.Map<String, String> vars)
	{
		if (vars == null || vars.isEmpty())
		{
			return;
		}

		BG_TILE = parse(vars.get("--bg-tile"), BG_TILE);
		BG_WELL = parse(vars.get("--bg-well"), BG_WELL);
		BG_COMPLETED_WELL = parse(vars.get("--bg-completed-well"), BG_COMPLETED_WELL);
		BORDER = parse(vars.get("--border-main"), BORDER);
		ACCENT = parse(vars.get("--accent"), ACCENT);
		TEXT_MAIN = parse(vars.get("--text-main"), TEXT_MAIN);
		TEXT_BRIGHT = parse(vars.get("--text-bright"), TEXT_BRIGHT);
		TEXT_DIM = parse(vars.get("--text-dim"), TEXT_DIM);
		COMPLETED = parse(vars.get("--text-completed"), COMPLETED);
	}

	/** "#d8a830" -> Color, keeping the old value on anything unparseable. */
	private static Color parse(String hex, Color fallback)
	{
		if (hex == null)
		{
			return fallback;
		}
		final String v = hex.trim().replace("#", "");
		if (v.length() != 6)
		{
			return fallback;
		}
		try
		{
			return new Color(Integer.parseInt(v, 16));
		}
		catch (NumberFormatException e)
		{
			return fallback;
		}
	}

	private static final int RADIUS = 6;

	/** RuneLite's fixed side-panel width. */
	static final int PANEL_WIDTH = 225;
	/** Outer insets used by both cards, per side. */
	static final int PAD = 4;
	/** The vertical scrollbar, which is always allowed for so text never reflows. */
	static final int SCROLLBAR = 8;

	/** Full width for a standalone block: the panel less padding and the scrollbar. */
	static final int CONTENT_WIDTH = PANEL_WIDTH - (PAD * 4) - SCROLLBAR;

	/** The same less a row's own padding. */
	static final int ROW_WIDTH = CONTENT_WIDTH - (PAD * 2);
	static final int ROW_GAP = 4;

	private Brand()
	{
	}

	/**
	 * A label that wraps: read-only, transparent, no border — styled to look like a
	 * JLabel but able to break across lines.
	 *
	 * A JTextArea rather than an HTML JLabel because Swing treats {@code width:Npx}
	 * as a hint, not a constraint, and long text rendered past the panel and lost its
	 * right edge mid-word. Plain text also can't be tripped up by markup in an
	 * organiser's tile description.
	 */
	static JTextArea wrapping(Font font, Color foreground)
	{
		final JTextArea ta = new JTextArea();
		ta.setLineWrap(true);
		ta.setWrapStyleWord(true);
		ta.setEditable(false);
		ta.setFocusable(false);
		ta.setOpaque(false);
		ta.setBorder(null);
		ta.setFont(font);
		ta.setForeground(foreground);
		ta.setAlignmentX(Component.LEFT_ALIGNMENT);

		// Without this, setText() moves the caret and the text area drags the
		// enclosing scroll pane down to follow it.
		if (ta.getCaret() instanceof DefaultCaret)
		{
			((DefaultCaret) ta.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
		}
		return ta;
	}

	/**
	 * Sets a wrapping block's text and tells it the width it will be laid out at.
	 * Without the width it reports the height of a single line and everything past
	 * the first wrap is clipped — the more so once a scrollbar takes another 8px.
	 */
	static void setWrapped(JTextArea area, String text)
	{
		area.setText(text);
		area.setSize(CONTENT_WIDTH, Short.MAX_VALUE);
	}

	/**
	 * A label/value row. The value keeps its natural width on the right and the label
	 * takes what's left and wraps into it — at this width "Boss: Corrupted Gauntlet"
	 * and "12000 pts/KC" do not fit on one line, and the alternative is one of them
	 * being clipped.
	 */
	static JPanel valueRow(String left, String right, Color leftColor, Color rightColor, Color background)
	{
		final JPanel row = new JPanel(new java.awt.BorderLayout(ROW_GAP, 0))
		{
			@Override
			public Dimension getMaximumSize()
			{
				// Height follows the wrapped label; without this BoxLayout stretches
				// the row to soak up leftover vertical space.
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		row.setBackground(background);
		row.setOpaque(background != null);
		row.setBorder(BorderFactory.createEmptyBorder(2, PAD, 2, PAD));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);

		final JTextArea label = wrapping(FontManager.getRunescapeSmallFont(), leftColor);
		label.setText(left);

		final JLabel value = new JLabel(right);
		value.setFont(FontManager.getRunescapeSmallFont());
		value.setForeground(rightColor);
		value.setVerticalAlignment(javax.swing.SwingConstants.TOP);

		// A JTextArea works out its wrapped height from its current width, and inside
		// a BorderLayout it is asked for that height before it has been given one — so
		// it answers "one line" and the rest of the label is cut off. Tell it the
		// width it is going to get.
		final int labelWidth = Math.max(40, ROW_WIDTH - value.getPreferredSize().width - ROW_GAP);
		label.setSize(labelWidth, Short.MAX_VALUE);

		row.add(label, java.awt.BorderLayout.CENTER);
		row.add(value, java.awt.BorderLayout.EAST);
		return row;
	}

	/** A vertical stack of rows. Transparent, so a well behind it shows through. */
	static JPanel section()
	{
		final JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setOpaque(false);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	/** A gold uppercase section label, as the modal sets them. */
	static JLabel sectionLabel(String text)
	{
		final JLabel l = new JLabel(text.toUpperCase());
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ACCENT);
		l.setBorder(BorderFactory.createEmptyBorder(10, PAD - 2, 4, PAD - 2));
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	/** A recessed rounded box. Stacks its children vertically. */
	static JPanel well()
	{
		final RoundedPanel p = new RoundedPanel(BG_WELL, BORDER);
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBorder(BorderFactory.createEmptyBorder(6, PAD + 2, 6, PAD + 2));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	/** A small gold-outlined pill, for the points count and tag chips. */
	static JPanel badge(String text, Color foreground)
	{
		final RoundedPanel p = new RoundedPanel(null, foreground.darker());
		p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
		p.setBorder(BorderFactory.createEmptyBorder(1, 5, 1, 5));

		final JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(foreground);
		p.add(l);

		// Pills must not stretch to the panel width when stacked vertically.
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	static Font bold(float size)
	{
		return FontManager.getRunescapeBoldFont().deriveFont(size);
	}

	/**
	 * Centres a component across the full panel width.
	 *
	 * Setting {@code CENTER_ALIGNMENT} on one child of a BoxLayout whose siblings are
	 * left-aligned does not do this — the layout reconciles the mixed alignments and
	 * the component ends up somewhere arbitrary. A full-width wrapper that centres
	 * its own child is unambiguous.
	 */
	static JPanel centered(Component inner)
	{
		final JPanel p = new JPanel(new java.awt.GridBagLayout())
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		p.setOpaque(false);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		p.add(inner);
		return p;
	}

	/**
	 * A panel with rounded corners.
	 *
	 * Swing has no rounded background, so the fill and border are painted here and
	 * the component is left non-opaque — otherwise the square default background
	 * paints over the corners.
	 */
	static class RoundedPanel extends JPanel
	{
		private final Color fill;
		private final Color line;

		RoundedPanel(Color fill, Color line)
		{
			this.fill = fill;
			this.line = line;
			setOpaque(false);
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			final Graphics2D g = (Graphics2D) graphics.create();
			try
			{
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				if (fill != null)
				{
					g.setColor(fill);
					g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, RADIUS, RADIUS);
				}
				if (line != null)
				{
					g.setColor(line);
					g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, RADIUS, RADIUS);
				}
			}
			finally
			{
				g.dispose();
			}
			super.paintComponent(graphics);
		}
	}

	/**
	 * The modal's progress bar: a dark rounded track with a gold gradient fill.
	 * Swing's JProgressBar can't be talked into rounded ends or a gradient without
	 * a custom UI delegate, which is more code than simply drawing it.
	 */
	static class ProgressBar extends JPanel
	{
		private double percent;
		private boolean complete;

		ProgressBar()
		{
			setOpaque(false);
			setPreferredSize(new Dimension(10, 8));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, 8));
			setAlignmentX(Component.LEFT_ALIGNMENT);
		}

		void setProgress(double pct, boolean isComplete)
		{
			this.percent = Math.max(0, Math.min(100, pct));
			this.complete = isComplete;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			final Graphics2D g = (Graphics2D) graphics.create();
			try
			{
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				final int w = getWidth();
				final int h = getHeight();

				g.setColor(BG_WELL);
				g.fillRect(0, 0, w - 1, h - 1);
				g.setColor(BORDER);
				g.drawRect(0, 0, w - 1, h - 1);

				final int fillWidth = (int) Math.round((w - 2) * (percent / 100.0));
				if (fillWidth <= 0)
				{
					return;
				}

				// Darker gold to bright gold, left to right — the same sweep the site
				// uses, which reads as progress even at eight pixels tall.
				//
				// Square ends, so the fill's width is exactly the percentage. Rounded
				// caps had to be padded to at least the bar's height or they collapsed
				// into a lens shape, which overstated small values.
				g.setPaint(new GradientPaint(
					0, 0, complete ? COMPLETED.darker() : TEXT_MAIN.darker(),
					fillWidth, 0, complete ? TEXT_BRIGHT : ACCENT));
				g.fillRect(1, 1, fillWidth, h - 3);
			}
			finally
			{
				g.dispose();
			}
		}
	}
}
