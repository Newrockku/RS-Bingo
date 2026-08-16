package com.rsbingo;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Turns a captured game frame into the PNG that gets filed as proof.
 *
 * The frame is stamped with the event codeword, the player and the item. The
 * codeword is the point: a reviewer seeing it knows the shot came from the plugin
 * at capture time rather than being an image picked off disk, because only someone
 * holding the event's codeword could have produced it.
 */
final class ProofShot
{
	private static final int MARGIN = 10;
	private static final Color BAR = new Color(0, 0, 0, 190);

	private ProofShot()
	{
	}

	/**
	 * @return PNG bytes, or null if the frame could not be encoded.
	 */
	static byte[] stamp(Image frame, String event, String codeword, String player, String item)
	{
		final BufferedImage img = toBuffered(frame);
		if (img == null)
		{
			return null;
		}

		final Graphics2D g = img.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
				RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

			// Three lines: which event this is for, who sent it and what for, then the
			// codeword. A reviewer looking at a folder of screenshots can tell them
			// apart from the first line alone.
			final String title = (event == null || event.trim().isEmpty())
				? "rs-bingo" : event.trim();
			final String who = player + "  -  " + item;
			// An event can still be withholding its codeword. The shot is worth filing
			// either way, so fall back to naming the source rather than stamping
			// "codeword: null" onto a reviewer's evidence.
			final String mark = (codeword == null || codeword.trim().isEmpty())
				? "via rs-bingo RuneLite plugin"
				: ("rs-bingo codeword: " + codeword.trim());

			// Top left: the reviewer's eye lands there first, and it is the one corner
			// the game does not fill with the minimap, inventory or chat.
			final Font heading = new Font(Font.SANS_SERIF, Font.BOLD, 24);
			final Font body = new Font(Font.SANS_SERIF, Font.BOLD, 20);

			g.setFont(heading);
			final int headingHeight = g.getFontMetrics().getHeight();
			final int titleWidth = g.getFontMetrics().stringWidth(title);

			g.setFont(body);
			final int lineHeight = g.getFontMetrics().getHeight();
			final int bodyWidth = Math.max(
				g.getFontMetrics().stringWidth(who),
				g.getFontMetrics().stringWidth(mark));

			final int barHeight = headingHeight + (lineHeight * 2) + MARGIN;

			// A backing bar, so the text stays readable over whatever is on screen.
			final int barWidth = Math.max(titleWidth, bodyWidth) + (MARGIN * 2);
			g.setColor(BAR);
			g.fillRoundRect(MARGIN, MARGIN, Math.min(barWidth, img.getWidth() - (MARGIN * 2)),
				barHeight, 10, 10);

			final int textLeft = MARGIN * 2;
			int baseline = MARGIN + headingHeight;

			g.setFont(heading);
			g.setColor(new Color(0xF0D888));
			g.drawString(title, textLeft, baseline);

			g.setFont(body);
			baseline += lineHeight;
			g.setColor(Color.WHITE);
			g.drawString(who, textLeft, baseline);

			baseline += lineHeight;
			g.setColor(new Color(0xD8A830));
			g.drawString(mark, textLeft, baseline);
		}
		finally
		{
			g.dispose();
		}

		try
		{
			final ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(img, "png", out);
			return out.toByteArray();
		}
		catch (IOException e)
		{
			return null;
		}
	}

	/**
	 * DrawManager hands back an Image, which is usually already a BufferedImage but
	 * isn't guaranteed to be; drawing onto it needs one either way.
	 */
	private static BufferedImage toBuffered(Image frame)
	{
		if (frame == null)
		{
			return null;
		}
		if (frame instanceof BufferedImage)
		{
			return (BufferedImage) frame;
		}

		final int w = frame.getWidth(null);
		final int h = frame.getHeight(null);
		if (w <= 0 || h <= 0)
		{
			return null;
		}

		final BufferedImage copy = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = copy.createGraphics();
		try
		{
			g.drawImage(frame, 0, 0, null);
		}
		finally
		{
			g.dispose();
		}
		return copy;
	}
}
