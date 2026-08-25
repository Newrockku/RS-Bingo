package com.rsbingo;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;

/**
 * One square of the board: the tile's artwork, with its tier (Showdown) or points
 * (everything else) in the bottom corner.
 *
 * Progress is carried by the border alone — graded from neutral to gold — so the
 * artwork is always drawn at full brightness and stays recognisable at 50px.
 */
class TileCell extends JPanel
{
	// Colours are read from Brand at paint time, never cached in a field. A
	// `static final Color = Brand.COMPLETED` snapshots the palette at class load, so
	// it survived even a full panel rebuild and left tile borders on the old theme
	// while everything else changed.
	private static final int RADIUS = 6;
	private static final int BAR_HEIGHT = 3;
	/** Dark enough to read as an empty track over any artwork. */
	private static final Color BAR_TRACK = new Color(0, 0, 0, 170);

	private final BoardModels.BoardTile tile;
	private final boolean showdown;

	/** Set once the artwork arrives; until then the cell paints its plain state. */
	private BufferedImage image;

	TileCell(BoardModels.BoardTile tile, BoardModels.Board board, TileImageCache images,
			 RsBingoConfig config, String siteUrl, int size, Runnable onClick)
	{
		this.tile = tile;
		this.showdown = board.isShowdown();

		setPreferredSize(new Dimension(size, size));
		setOpaque(false);
		setBackground(Brand.BG_WELL);
		setToolTipText(tooltip());

		if (tile.empty)
		{
			return;
		}

		final String url = config.showTileImages()
			? TileImageCache.resolve(siteUrl, tile.img)
			: null;
		if (url != null)
		{
			images.get(url, img ->
			{
				this.image = img;
				repaint();
			});
		}

		setCursor(new Cursor(Cursor.HAND_CURSOR));
		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				onClick.run();
			}
		});
	}

	@Override
	protected void paintComponent(Graphics graphics)
	{
		super.paintComponent(graphics);

		final Graphics2D g = (Graphics2D) graphics.create();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

			final int w = getWidth();
			final int h = getHeight();

			// Rounded, like the site's tiles. The artwork is clipped to the same shape
			// so it can't paint over the corners.
			g.setColor(Brand.BG_WELL);
			g.fillRoundRect(0, 0, w - 1, h - 1, RADIUS, RADIUS);

			if (tile.empty)
			{
				g.setColor(Brand.BORDER.darker());
				g.drawRoundRect(0, 0, w - 1, h - 1, RADIUS, RADIUS);
				return;
			}

			g.setClip(new java.awt.geom.RoundRectangle2D.Float(0, 0, w - 1, h - 1, RADIUS, RADIUS));

			final float progress = progress();

			if (image != null)
			{
				// Contain rather than crop: tile art is often a full item sprite and
				// cropping it makes tiles hard to tell apart at 50px.
				final double scale = Math.min(w / (double) image.getWidth(), h / (double) image.getHeight());
				final int dw = Math.max(1, (int) Math.round(image.getWidth() * scale));
				final int dh = Math.max(1, (int) Math.round(image.getHeight() * scale));
				g.drawImage(image, (w - dw) / 2, (h - dh) / 2, dw, dh, null);
			}
			else
			{
				// No artwork — either still loading, unavailable, or switched off. Fall
				// back to a graded fill so the board still reads at a glance.
				g.setColor(shade(progress));
				g.fillRoundRect(0, 0, w - 1, h - 1, RADIUS, RADIUS);
			}

			drawProgressBar(g, w, h);

			g.setClip(null);
			g.setColor(borderColor());
			g.drawRoundRect(0, 0, w - 1, h - 1, RADIUS, RADIUS);

			g.setFont(getFont().deriveFont(Font.BOLD, 10f));

			// Bottom right: the tier on a Showdown board, the tile's points elsewhere.
			// The border already carries completion, so the corner is free to say what
			// the tile is worth.
			final String badge = badgeText(g);
			if (badge != null)
			{
				final int tw = g.getFontMetrics().stringWidth(badge);
				// Sits above the progress bar rather than through it.
				drawOutlined(g, badge, w - tw - 3, h - BAR_HEIGHT - 4,
					badgeIsFinished() ? Brand.COMPLETED : Color.WHITE);
			}
		}
		finally
		{
			g.dispose();
		}
	}

	/**
	 * A bar across the bottom of the cell, so you can see which tiles are nearly
	 * there without opening each one.
	 *
	 * Drawn inside the rounded clip, so it takes the cell's corners with it. The
	 * track is always painted, even at zero, so a row of tiles can be compared
	 * against a common baseline rather than by the presence of a bar.
	 */
	private void drawProgressBar(Graphics2D g, int w, int h)
	{
		final int y = h - BAR_HEIGHT - 1;

		g.setColor(BAR_TRACK);
		g.fillRect(1, y, w - 2, BAR_HEIGHT);

		final int filled = (int) Math.round((w - 2) * (barPercent() / 100.0));
		if (filled > 0)
		{
			g.setColor(barIsFull() ? Brand.COMPLETED : Brand.TEXT_MAIN);
			g.fillRect(1, y, filled, BAR_HEIGHT);
		}
	}

	/**
	 * How full the cell's bar is, 0..100.
	 *
	 * On a Showdown tile that means progress toward the *next* tier, which is what
	 * "close to completion" means there — the tier reached is already in the badge
	 * and the border. Everywhere else it is the server's collected/required.
	 */
	private double barPercent()
	{
		if (showdown && tile.hasTierProgress())
		{
			return tile.tierMaxed() ? 100 : tile.pctToNextTier();
		}
		return tile.itemsPercent();
	}

	private boolean barIsFull()
	{
		return showdown && tile.hasTierProgress() ? tile.tierMaxed() : tile.done;
	}

	/**
	 * Whether the corner badge should read as finished, in gold.
	 *
	 * On a Showdown board that means every tier is banked — *not* {@code done}, which
	 * there reports the tile's item checklist and moves independently of the tier.
	 * Colouring a tier badge by {@code done} put gold on T3 tiles whose items happened
	 * to be complete, and left genuinely maxed T5 tiles white.
	 */
	private boolean badgeIsFinished()
	{
		return showdown ? tile.tierMaxed() : tile.done;
	}

	/**
	 * What goes in the bottom-right corner, or null when nothing fits or applies.
	 *
	 * Points are written "5p" so they can't be misread as a Showdown tier, falling
	 * back to the bare number on a board packed tight enough that even that one
	 * character would overrun the cell.
	 */
	private String badgeText(Graphics2D g)
	{
		if (showdown)
		{
			return (tile.tier != null && tile.tier > 0) ? ("T" + tile.tier) : null;
		}
		if (tile.points <= 0)
		{
			return null;
		}

		final String withSuffix = tile.points + "p";
		final int room = getWidth() - 6;
		return g.getFontMetrics().stringWidth(withSuffix) <= room
			? withSuffix
			: String.valueOf(tile.points);
	}

	/** Text sits on top of artwork, so it needs its own contrast. */
	private static void drawOutlined(Graphics2D g, String text, int x, int y, Color colour)
	{
		g.setColor(Color.BLACK);
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				if (dx != 0 || dy != 0)
				{
					g.drawString(text, x + dx, y + dy);
				}
			}
		}
		g.setColor(colour);
		g.drawString(text, x, y);
	}

	/** 0..1 — tier fraction on Showdown, done/not-done everywhere else. */
	private float progress()
	{
		if (showdown && tile.tier != null && tile.maxTier != null && tile.maxTier > 0)
		{
			return Math.max(0f, Math.min(1f, tile.tier / (float) tile.maxTier));
		}
		return tile.done ? 1f : 0f;
	}

	/** Cold-to-warm fill used when there is no artwork to draw. */
	private static Color shade(float progress)
	{
		return blend(Brand.BG_WELL, Brand.COMPLETED.darker(), progress);
	}

	private Color borderColor()
	{
		if (tile.empty)
		{
			return Brand.BORDER.darker();
		}
		final float p = progress();
		if (p >= 1f)
		{
			return Brand.COMPLETED;
		}
		if (p <= 0f)
		{
			return Brand.BORDER;
		}
		// Part-way tiles get a border between neutral and complete, so a Showdown
		// board's spread of tiers is visible without reading every badge.
		return blend(Brand.BORDER, Brand.COMPLETED, p);
	}

	private static Color blend(Color from, Color to, float amount)
	{
		final float p = Math.max(0f, Math.min(1f, amount));
		return new Color(
			(int) (from.getRed() + (to.getRed() - from.getRed()) * p),
			(int) (from.getGreen() + (to.getGreen() - from.getGreen()) * p),
			(int) (from.getBlue() + (to.getBlue() - from.getBlue()) * p));
	}

	private String tooltip()
	{
		if (tile.empty)
		{
			return null;
		}

		final StringBuilder tip = new StringBuilder("<html><b>")
			.append(Text.escape(tile.displayTitle())).append("</b>");

		if (showdown && tile.tier != null)
		{
			tip.append("<br>Tier ").append(tile.tier).append(" of ").append(tile.maxTier);
		}
		else
		{
			tip.append("<br>").append(tile.done ? "Complete" : "Not complete");
		}

		if (tile.xp == null && (tile.hasCounts() || tile.neededCount() > 0))
		{
			tip.append("<br>").append(tile.progressText()).append(" items");
		}

		return tip.append("</html>").toString();
	}
}
