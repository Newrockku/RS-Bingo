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

	/** Tile details withheld by the event; drawn as a lock, opens nothing. */
	private final boolean locked;
	/** Kept so the tooltip can ask what this tile's counts are measured in — a Hybrid
	 *  board answers that per tile, not per board. */
	private final BoardModels.Board board;

	/** The tile this cell draws. The grid reads it to paint region perimeters in the
	 *  gutters between cells, which no single cell can reach. */
	BoardModels.BoardTile tile()
	{
		return tile;
	}

	/** Set once the artwork arrives; until then the cell paints its plain state. */
	private BufferedImage image;

	TileCell(BoardModels.BoardTile tile, BoardModels.Board board, TileImageCache images,
			 RsBingoConfig config, String siteUrl, int size, Runnable onClick)
	{
		this.tile = tile;
		this.board = board;
		this.showdown = board.isShowdown();
		this.locked = board.hiddenTiles && !tile.empty;

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

		// A withheld tile opens nothing. The server sends no name, artwork or
		// checklist for one, so the detail view would be an empty panel — and the
		// website refuses the same click rather than showing it.
		if (locked)
		{
			return;
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
			//
			// The fill carries progress the way the site's does: a tile lifts from the
			// plain background towards the completed one as it climbs, so a Showdown
			// board's spread of tiers is visible without reading a single badge, and a
			// finished tile is lit rather than merely outlined.
			g.setColor(Brand.BG_WELL);
			g.fillRoundRect(0, 0, w - 1, h - 1, RADIUS, RADIUS);

			if (tile.empty)
			{
				g.setColor(Brand.BORDER.darker());
				g.drawRoundRect(0, 0, w - 1, h - 1, RADIUS, RADIUS);
				return;
			}

			// Withheld: a lock and nothing else. The points badge is deliberately left
			// off — the site replaces the whole cell with its padlock, so showing what
			// a tile is worth here would give away more than the website does.
			if (locked)
			{
				g.setColor(Brand.BORDER);
				g.drawRoundRect(0, 0, w - 1, h - 1, RADIUS, RADIUS);
				paintLock(g, w, h);
				return;
			}

			g.setClip(new java.awt.geom.RoundRectangle2D.Float(0, 0, w - 1, h - 1, RADIUS, RADIUS));

			final float progress = progress();

			// Painted under the artwork, never over it, so the art keeps its own
			// colours — the same order the site uses, where the item sprite sits on
			// the tinted tile rather than being washed by it.
			paintTierWash(g, w, h, progress);

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

	/**
	 * A padlock, centred.
	 *
	 * Proportions matter more than detail at this size: a 7x7 board gives each cell
	 * about 26px. Two things decide whether it reads as a lock — the shackle is
	 * clearly narrower than the body, and it is a true semicircle on short stems.
	 * Drawn from a bounding box taller than it is wide, the arc comes out as a
	 * pointed arch and the whole thing looks like a handbag.
	 */
	private static void paintLock(Graphics2D g, int w, int h)
	{
		final int min = Math.min(w, h);

		final int bodyW = Math.max(7, Math.round(min * 0.44f));
		final int bodyH = Math.max(5, Math.round(min * 0.30f));
		final int stem = Math.max(1, Math.round(min * 0.06f));

		// Widths are kept the same parity so that halving the difference is exact.
		// Centring the two independently leaves the shackle half a pixel off the
		// body whenever the cell width makes their parities differ, which at 26px is
		// plainly visible as a lopsided lock.
		int shackleW = Math.max(4, Math.round(bodyW * 0.62f));
		if (((bodyW - shackleW) & 1) != 0)
		{
			shackleW++;
		}

		// Semicircle: a bounding box as tall as it is wide, swept 0-180, is a half
		// circle of height shackleW/2.
		final int arcH = shackleW;
		final int shackleTotal = (arcH / 2) + stem;

		final int totalH = bodyH + shackleTotal;
		final int bodyY = (h - totalH) / 2 + shackleTotal;
		final int bodyX = (w - bodyW) / 2;
		// Derived from the body, not from the cell, so the two always share a centre.
		final int shackleX = bodyX + ((bodyW - shackleW) / 2);
		final int arcTop = bodyY - shackleTotal;

		g.setColor(Brand.TEXT_DIM);

		final java.awt.Stroke previous = g.getStroke();
		g.setStroke(new java.awt.BasicStroke(Math.max(1f, min / 16f),
			java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER));
		g.drawArc(shackleX, arcTop, shackleW, arcH, 0, 180);
		// Stems from the arc's ends down onto the body.
		final int stemTop = arcTop + arcH / 2;
		g.drawLine(shackleX, stemTop, shackleX, bodyY);
		g.drawLine(shackleX + shackleW, stemTop, shackleX + shackleW, bodyY);
		g.setStroke(previous);

		g.fillRoundRect(bodyX, bodyY, bodyW, bodyH, 2, 2);
	}

	/**
	 * The diagonal tint a tile carries as it climbs its tiers, so a finished tile is
	 * lit rather than merely outlined and a Showdown board's spread is readable
	 * without checking a single badge.
	 *
	 * This mirrors the site's rule for {@code .tile.completed}, which washes the tile
	 * with the completion colour at 35% down to 12% alpha across the diagonal — not
	 * with the {@code --bg-completed} token, which is a much darker colour used for
	 * panels behind the board. Scaling the alpha by progress turns the site's on/off
	 * state into the gradient across tiers.
	 */
	private static void paintTierWash(Graphics2D g, int w, int h, float progress)
	{
		if (progress <= 0f)
		{
			return;
		}

		final Color c = Brand.COMPLETED;
		final int near = Math.round(255 * 0.35f * progress);
		final int far = Math.round(255 * 0.12f * progress);

		g.setPaint(new java.awt.GradientPaint(
			0, 0, new Color(c.getRed(), c.getGreen(), c.getBlue(), near),
			w, h, new Color(c.getRed(), c.getGreen(), c.getBlue(), far)));
		g.fillRoundRect(0, 0, w - 1, h - 1, RADIUS, RADIUS);
	}

	/** Cold-to-warm fill used when there is no artwork to draw. */
	private static Color shade(float progress)
	{
		return Brand.blend(Brand.BG_WELL, Brand.COMPLETED.darker(), progress);
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
		return Brand.blend(Brand.BORDER, Brand.COMPLETED, p);
	}

	private String tooltip()
	{
		if (tile.empty)
		{
			return null;
		}

		final StringBuilder tip = new StringBuilder("<html><b>")
			.append(Text.escape(tile.displayTitle())).append("</b>");

		if (locked)
		{
			tip.append("<br>Hidden until the organiser releases the board");
			final String when = Text.untilRelease(board.tilesReleaseAt, java.time.Instant.now());
			if (when != null)
			{
				tip.append("<br>Revealed in ").append(when);
			}
			return tip.append("</html>").toString();
		}

		if (showdown && tile.tier != null)
		{
			tip.append("<br>Tier ").append(tile.tier).append(" of ").append(tile.maxTier);
		}
		else
		{
			tip.append("<br>").append(tile.done ? "Complete" : "Not complete");
		}

		// Not on Showdown: the tier above is what the tile is judged on, and an item
		// count there reads as progress towards something that is not being measured.
		if (!showdown && tile.xp == null && (tile.hasCounts() || tile.neededCount() > 0))
		{
			// Unit-aware: on a Hybrid board these counts are points toward a threshold
			// for a showdown tile, and items for a blackout one, on the same grid.
			tip.append("<br>").append(tile.progressTextWithUnit(board));
		}

		return tip.append("</html>").toString();
	}
}
