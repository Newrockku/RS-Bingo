package com.rsbingo;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.JComboBox;
import javax.swing.text.DefaultCaret;
import java.util.function.Consumer;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The tile "modal". A RuneLite side panel is ~225px wide, so a floating dialog
 * would be unusable — this is a full-panel view swapped in over the grid with a
 * back button, which is the idiomatic equivalent.
 *
 * It shows what game.html's modal shows: title, artwork, points, a progress bar,
 * the Showdown tier line, the description, and the checklist or breakdowns.
 *
 * The one thing it leaves out is the tag list. Tags name the bosses and skills that
 * Point Rates already lists *with* their values, and on a 225px panel that
 * duplicate ran to several lines.
 *
 * Anything that can wrap is a read-only {@link JTextArea} rather than an HTML
 * {@link JLabel}. Swing's {@code width:Npx} style is a hint, not a constraint — it
 * let long tag lists and descriptions render past the panel and lose their right
 * edge mid-word. A text area wraps to the width it is actually given, and as plain
 * text it also can't be tripped up by markup in an organiser's tile description.
 */
class TileDetailPanel extends JPanel
{
		/** XP figures run to millions; the site groups them and so do we. */
	private static final java.text.NumberFormat THOUSANDS =
		java.text.NumberFormat.getIntegerInstance(java.util.Locale.US);

	private final TileImageCache images;
	private final RsBingoConfig config;
	private final Submitter submitter;

	/**
	 * How the tile view files a submission. Implemented by the panel, which knows the
	 * event, the team and the logged-in character.
	 */
	interface Submitter
	{
		/** The character to submit as, or null when they are not on the viewed team. */
		String submittingAs();

		/** False while the event is still withholding the codeword submissions carry. */
		boolean canSubmit();

		void submit(BoardModels.BoardTile tile, BoardModels.SubmitOption option, Consumer<String> onStatus);
	}

	private final JLabel imageLabel = new JLabel();
	private final javax.swing.JTextPane title;
	private final JLabel status = new JLabel();
	private final JLabel meta = new JLabel();
	private final Brand.ProgressBar progress = new Brand.ProgressBar();
	private final JLabel tierLine = new JLabel();
	private final JTextArea description;
	private final JPanel checklist = Brand.section();

	private final JLabel xpTotal = new JLabel();
	private final JLabel xpGoal = new JLabel();
	private final JLabel xpHeading = Brand.sectionLabel("Team XP Breakdown");
	private final JPanel xpPlayers = Brand.section();

	private final JLabel descriptionHeading = Brand.sectionLabel("Description");
	private final JLabel ratesHeading = Brand.sectionLabel("Point Rates");
	private final JPanel rates = Brand.section();
	private final JLabel progressHeading = Brand.sectionLabel("Player Progress");
	private final JPanel playerProgress = Brand.section();
	private final JLabel checklistHeading = Brand.sectionLabel("Checklist");

	private final JLabel submitHeading = Brand.sectionLabel("Submit");
	private final JPanel submitWell = Brand.well();
	private final JComboBox<BoardModels.SubmitOption> submitBox = new JComboBox<>();
	private final JButton submitButton = new JButton("Take screenshot & submit");
	private final JLabel submitAs = new JLabel();
	/** Breathing room so the button doesn't sit flush against the picker. */
	private final JPanel submitGap = spacer(6);
	private final JTextArea submitStatus = Brand.wrapping(FontManager.getRunescapeSmallFont(), Brand.TEXT_MAIN);

	/** Wells wrapping the sections above, held so they can be hidden with them. */
	private final JPanel descriptionWell = Brand.well();
	private final JPanel xpWell = Brand.well();
	private final JPanel checklistWell = Brand.well();

	TileDetailPanel(TileImageCache images, RsBingoConfig config, Submitter submitter, Runnable onBack)
	{
		this.images = images;
		this.config = config;
		this.submitter = submitter;

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(Brand.BG_TILE);
		setBorder(BorderFactory.createEmptyBorder(6, Brand.PAD, 10, Brand.PAD));

		final JButton back = new JButton("< Back to board");
		back.setFocusPainted(false);
		// The Windows look-and-feel paints its own button face and ignores
		// setBackground; unfilling the content area lets ours show through.
		back.setContentAreaFilled(false);
		back.setOpaque(true);
		back.setBackground(Brand.BG_WELL);
		back.setForeground(Brand.TEXT_MAIN);
		back.setFont(FontManager.getRunescapeSmallFont());
		back.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(Brand.BORDER),
			BorderFactory.createEmptyBorder(4, 6, 4, 6)));
		back.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		back.addActionListener(e -> onBack.run());

		// The artwork is the tile's identity — the site centres it and gives it room.
		imageLabel.setHorizontalAlignment(SwingConstants.CENTER);
		imageLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
		imageLabel.setBorder(BorderFactory.createEmptyBorder(4, 0, 8, 0));

		title = centeredPane(Brand.bold(15f), Brand.TEXT_BRIGHT);

		status.setFont(FontManager.getRunescapeSmallFont());
		meta.setFont(FontManager.getRunescapeSmallFont());
		meta.setForeground(Brand.TEXT_DIM);

		tierLine.setFont(FontManager.getRunescapeSmallFont());
		tierLine.setForeground(Brand.TEXT_MAIN);
		tierLine.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));

		// The site gives the XP figure its own oversized readout, centred in a well;
		// it is the whole point of the tile, so it gets the same emphasis here.
		xpTotal.setFont(Brand.bold(22f));
		xpTotal.setForeground(Brand.TEXT_BRIGHT);
		xpTotal.setAlignmentX(Component.CENTER_ALIGNMENT);
		xpGoal.setFont(FontManager.getRunescapeSmallFont());
		xpGoal.setForeground(Brand.TEXT_DIM);
		xpGoal.setAlignmentX(Component.CENTER_ALIGNMENT);

		description = Brand.wrapping(FontManager.getRunescapeSmallFont(), Brand.TEXT_MAIN);

		for (Component c : new Component[]{back, title, status, meta,
			progress, tierLine, description, checklist})
		{
			((JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT);
		}

		descriptionWell.add(description);
		xpWell.add(Brand.centered(xpTotal));
		xpWell.add(Brand.centered(xpGoal));
		checklistWell.add(checklist);

		add(back);
		add(title);
		add(Brand.centered(imageLabel));
		add(Brand.centered(status));
		add(Brand.centered(meta));
		add(Box.createVerticalStrut(8));
		add(progress);
		add(tierLine);
		add(Box.createVerticalStrut(2));
		add(xpWell);
		add(descriptionHeading);
		add(descriptionWell);
		add(ratesHeading);
		add(rates);
		add(progressHeading);
		add(playerProgress);
		add(xpHeading);
		add(xpPlayers);
		submitAs.setFont(FontManager.getRunescapeSmallFont());
		submitAs.setForeground(Brand.TEXT_DIM);

		submitBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		submitBox.setBackground(Brand.BG_WELL);
		submitBox.setForeground(Brand.TEXT_BRIGHT);
		submitBox.setFont(FontManager.getRunescapeSmallFont());

		submitButton.setFocusPainted(false);
		submitButton.setContentAreaFilled(false);
		submitButton.setOpaque(true);
		submitButton.setBackground(Brand.BG_WELL);
		submitButton.setForeground(Brand.ACCENT);
		submitButton.setFont(FontManager.getRunescapeSmallFont());
		submitButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		submitButton.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(Brand.BORDER),
			BorderFactory.createEmptyBorder(4, 6, 4, 6)));
		submitButton.addActionListener(e -> onSubmitClicked());

		for (Component c : new Component[]{submitAs, submitBox, submitGap, submitButton, submitStatus})
		{
			((JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT);
			submitWell.add(c);
		}

		add(checklistHeading);
		add(checklistWell);
		add(submitHeading);
		add(submitWell);
	}

	/** The tile currently shown, so the submit button knows what it is filing against. */
	private BoardModels.BoardTile showing;

	private void onSubmitClicked()
	{
		final BoardModels.SubmitOption option = (BoardModels.SubmitOption) submitBox.getSelectedItem();
		if (showing == null || option == null)
		{
			return;
		}

		submitButton.setEnabled(false);
		Brand.setWrapped(submitStatus, "Capturing…");
		submitter.submit(showing, option, message ->
		{
			Brand.setWrapped(submitStatus, message);
			submitButton.setEnabled(true);
		});
	}

	/**
	 * The submit controls, shown only when this character can actually use them:
	 * the tile has to take submissions, a codeword has to be configured, and the
	 * logged-in account has to be on the team being viewed. Anything else and the
	 * whole section stays hidden rather than offering a button that will fail.
	 */
	private void buildSubmit(BoardModels.BoardTile tile, BoardModels.Board board)
	{
		final String as = submitter.submittingAs();

		// No section at all when there is nothing to act on: not on this team, the
		// tile takes no submissions, or the tile is already finished. A tile can be
		// complete with items still unclaimed — "any 1 of 5" needs one — so the
		// remaining options are real but pointless, and offering them just invites
		// evidence a reviewer has to reject.
		final boolean eligible = as != null && !tile.done && !tile.submitItems.isEmpty();
		submitHeading.setVisible(eligible);
		submitWell.setVisible(eligible);
		if (!eligible)
		{
			return;
		}

		submitAs.setText("as " + as);
		submitButton.setEnabled(true);

		// Outside the event's window there is nothing to offer. The server rejects
		// these anyway; saying so here beats letting someone take a screenshot and
		// upload it only to be told no.
		final String closed = board.submissionsClosedReason();
		if (closed != null)
		{
			submitBox.setVisible(false);
			submitGap.setVisible(false);
			submitButton.setVisible(false);
			Brand.setWrapped(submitStatus, closed);
			return;
		}

		// Anything approved or already awaiting review is left out: offering it again
		// only produces a duplicate for a reviewer to reject.
		submitBox.removeAllItems();
		for (BoardModels.SubmitOption option : tile.submitItems)
		{
			if (!option.alreadySubmitted())
			{
				submitBox.addItem(option);
			}
		}

		final boolean anythingLeft = submitBox.getItemCount() > 0;
		submitBox.setVisible(anythingLeft);
		submitGap.setVisible(anythingLeft);
		submitButton.setVisible(anythingLeft);
		Brand.setWrapped(submitStatus, anythingLeft
			? ""
			: "Everything on this tile is already submitted or approved.");
	}

	void show(BoardModels.BoardTile tile, BoardModels.Board board)
	{
		final boolean showdown = board.isShowdown();

		// Header: the site writes "3 | Prison Break", dropping the position on
		// Showdown tiles where the tier matters more than the square.
		setCentered(title, showdown && tile.hasTierProgress()
			? tile.displayTitle()
			: tile.pos + " | " + tile.displayTitle());

		imageLabel.setIcon(null);
		final String url = config.showTileImages()
			? TileImageCache.resolve(config.baseUrl(), tile.img)
			: null;
		if (url != null)
		{
			images.get(url, img -> imageLabel.setIcon(new ImageIcon(fit(img, 165, 110))));
		}

		if (showdown && tile.tier != null)
		{
			final int max = tile.maxTier == null ? 0 : tile.maxTier;
			status.setText("Tier " + tile.tier + " of " + max);
			status.setForeground(tile.tierMaxed() ? Brand.COMPLETED : Brand.TEXT_DIM);
		}
		else
		{
			status.setText(tile.done ? "Complete" : "Not complete");
			status.setForeground(tile.done ? Brand.COMPLETED : Brand.TEXT_DIM);
		}

		final StringBuilder m = new StringBuilder();
		m.append(tile.points).append(" PTS");
		if (tile.xp == null && (tile.hasCounts() || tile.neededCount() > 0))
		{
			m.append("  ·  ").append(tile.progressText()).append(" items");
		}
		meta.setText(m.toString());

		// Tags are deliberately not shown: they name the same bosses and skills that
		// Point Rates lists with their values attached, and on a 225px panel the
		// duplicate list cost several lines without adding anything.
		final boolean hasRates = !tile.rates.isEmpty();

		applyProgress(tile, showdown);

		final boolean hasDescription = tile.description != null && !tile.description.isEmpty();
		descriptionHeading.setVisible(hasDescription);
		descriptionWell.setVisible(hasDescription);
		if (hasDescription)
		{
			Brand.setWrapped(description, tile.description);
		}

		showing = tile;
		buildSubmit(tile, board);
		buildRates(tile);
		buildPlayerProgress(tile, hasRates);
		buildXp(tile);
		// A Showdown tile's items already appear in both sections above, priced and
		// attributed; the site drops the plain checklist there for the same reason.
		// An XP tile has no items at all — its breakdown replaces the checklist.
		buildChecklist(tile, board, !hasRates && tile.xp == null);

		revalidate();
		repaint();
	}

	private void applyProgress(BoardModels.BoardTile tile, boolean showdown)
	{
		if (showdown && tile.hasTierProgress())
		{
			progress.setVisible(true);
			tierLine.setVisible(true);

			if (tile.tierMaxed())
			{
				progress.setProgress(100, true);
				tierLine.setText("T" + tile.tier + " - " + Text.compact(tile.score) + " pts - MAX");
				return;
			}

			progress.setProgress(tile.pctToNextTier(), false);

			final String reached = (tile.tier == null || tile.tier == 0) ? "No tier" : ("T" + tile.tier);
			tierLine.setText(String.format("%s - %s / %s - %.1f%% to T%d",
				reached,
				Text.compact(tile.score),
				Text.compact(tile.nextTierAt()),
				tile.pctToNextTier(),
				(tile.tier == null ? 0 : tile.tier) + 1));
			return;
		}

		tierLine.setVisible(false);

		// An XP tile's bar tracks XP toward the goal; it has no items to count.
		if (tile.xp != null)
		{
			progress.setVisible(true);
			progress.setProgress(tile.xp.percent(), tile.done);
			return;
		}

		if (tile.done || tile.hasCounts() || tile.neededCount() > 0)
		{
			progress.setVisible(true);
			progress.setProgress(tile.itemsPercent(), tile.done);
		}
		else
		{
			progress.setVisible(false);
		}
	}

	/**
	 * An XP tile's readout: the team's total against the goal, then who earned it.
	 * These tiles carry no items, so this is all there is to show — previously the
	 * panel said "No item checklist on this tile" and left it at that.
	 */
	private void buildXp(BoardModels.BoardTile tile)
	{
		final BoardModels.XpProgress xp = tile.xp;
		final boolean present = xp != null;

		xpWell.setVisible(present);
		xpHeading.setVisible(present);
		xpPlayers.setVisible(present);
		xpPlayers.removeAll();

		if (!present)
		{
			return;
		}

		xpTotal.setText(THOUSANDS.format(Math.round(xp.collected)));
		xpTotal.setForeground(tile.done ? Brand.COMPLETED : Brand.TEXT_BRIGHT);
		xpGoal.setText("/ " + THOUSANDS.format(Math.round(xp.required)) + " XP");

		if (xp.players.isEmpty())
		{
			final JTextArea none = Brand.wrapping(FontManager.getRunescapeSmallFont(), Brand.TEXT_DIM);
			Brand.setWrapped(none, "No XP gained on this tile yet.");
			xpPlayers.add(none);
			return;
		}

		for (BoardModels.XpPlayer player : xp.players)
		{
			xpPlayers.add(Brand.valueRow(
				player.name == null ? "" : player.name,
				THOUSANDS.format(Math.round(player.xp)) + " XP",
				Brand.TEXT_BRIGHT,
				Brand.ACCENT,
				Brand.BG_WELL));
			xpPlayers.add(Box.createVerticalStrut(2));
		}
	}

	/** "Point Rates": what one kill / one XP / one drop is worth on this tile. */
	private void buildRates(BoardModels.BoardTile tile)
	{
		rates.removeAll();
		ratesHeading.setVisible(!tile.rates.isEmpty());
		rates.setVisible(!tile.rates.isEmpty());

		for (BoardModels.Rate rate : tile.rates)
		{
			rates.add(Brand.valueRow(
				rate.label == null ? "" : rate.label,
				rate.rate == null ? "" : rate.rate,
				Brand.TEXT_MAIN,
				Brand.TEXT_BRIGHT,
				Brand.BG_WELL));
			rates.add(Box.createVerticalStrut(2));
		}
	}

	/**
	 * "Player Progress": each teammate's total on this tile, then the kills, XP and
	 * drops that made it up.
	 *
	 * @param expected whether this tile should have a breakdown at all — the server
	 *                 only sends one for the tile that was asked for, so an empty
	 *                 list on a rates-bearing tile means "still loading", not "nobody
	 *                 has done anything".
	 */
	private void buildPlayerProgress(BoardModels.BoardTile tile, boolean expected)
	{
		playerProgress.removeAll();
		progressHeading.setVisible(expected);
		playerProgress.setVisible(expected);

		if (!expected)
		{
			return;
		}

		if (tile.players.isEmpty())
		{
			final JTextArea none = Brand.wrapping(FontManager.getRunescapeSmallFont(), Brand.TEXT_DIM);
			Brand.setWrapped(none, "No progress on this tile yet.");
			playerProgress.add(none);
			return;
		}

		for (BoardModels.PlayerProgress player : tile.players)
		{
			playerProgress.add(Brand.valueRow(
				player.name == null ? "" : player.name,
				Text.compact(player.points) + " pts",
				Brand.TEXT_BRIGHT,
				Brand.ACCENT,
				Brand.BG_WELL));

			for (BoardModels.ProgressLine line : player.lines)
			{
				playerProgress.add(Brand.valueRow(
					(line.label == null ? "" : line.label) + ": " + line.gainText(),
					Text.compact(line.points) + " pts",
					Brand.TEXT_DIM,
					Brand.TEXT_MAIN,
					Brand.BG_TILE));
			}

			playerProgress.add(Box.createVerticalStrut(6));
		}
	}

	private void buildChecklist(BoardModels.BoardTile tile, BoardModels.Board board, boolean show)
	{
		checklist.removeAll();
		checklistHeading.setVisible(show);
		checklistWell.setVisible(show);

		if (!show)
		{
			return;
		}

		for (BoardModels.TileItem item : tile.items)
		{
			final boolean done = item.approved >= item.need;
			checklist.add(row(
				item.label + (item.need > 1 ? (" (" + item.approved + "/" + item.need + ")") : ""),
				done,
				!done && item.pending > 0,
				item.player));
		}

		for (BoardModels.TileGroup group : tile.groups)
		{
			if (group.name != null && !group.name.isEmpty())
			{
				final JTextArea header = Brand.wrapping(FontManager.getRunescapeSmallFont(), Brand.ACCENT);
				Brand.setWrapped(header, group.name + logicSuffix(group.logic));
				header.setBorder(BorderFactory.createEmptyBorder(8, 0, 2, 0));
				checklist.add(header);
			}
			for (BoardModels.TileGroupItem gi : group.items)
			{
				checklist.add(row(gi.label, gi.approved, gi.pending, gi.player));
			}
		}

		if (tile.items.isEmpty() && tile.groups.isEmpty())
		{
			final JTextArea none = Brand.wrapping(FontManager.getRunescapeSmallFont(), Brand.TEXT_DIM);
			Brand.setWrapped(none, board.hiddenTiles
				? "Tiles are hidden on this event."
				: "No item checklist on this tile.");
			checklist.add(none);
		}
	}

	private static String logicSuffix(String logic)
	{
		if (logic == null)
		{
			return "";
		}
		switch (logic)
		{
			case "any1":
				return " (any 1)";
			case "any2":
				return " (any 2)";
			case "any3":
				return " (any 3)";
			case "any4":
				return " (any 4)";
			default:
				return "";
		}
	}

	/**
	 * A checklist line in one of three states, as the site draws them: approved,
	 * awaiting review, or untouched. Without the middle one an item someone has
	 * already submitted proof for looks identical to one nobody has attempted.
	 */
	/** A fixed-height transparent strut that BoxLayout will not stretch. */
	private static JPanel spacer(int height)
	{
		final JPanel p = new JPanel();
		p.setOpaque(false);
		p.setPreferredSize(new Dimension(1, height));
		p.setMinimumSize(new Dimension(1, height));
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
		return p;
	}

	private static JTextArea row(String label, boolean approved, boolean pending, String player)
	{
		final Color colour;
		final String mark;
		if (approved)
		{
			colour = Brand.COMPLETED;
			mark = "✓  ";
		}
		else if (pending)
		{
			colour = Brand.ACCENT;
			mark = "?  ";
		}
		else
		{
			colour = Brand.TEXT_DIM;
			mark = "○  ";
		}

		// The site names the submitter next to the item, which is how a team sees at a
		// glance who already covered something.
		final String who = (player == null || player.trim().isEmpty()) ? "" : (" (" + player.trim() + ")");

		final JTextArea ta = Brand.wrapping(FontManager.getRunescapeSmallFont(), colour);
		Brand.setWrapped(ta, mark + label + who);
		ta.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
		return ta;
	}

	/**
	 * Centre-aligned wrapping text. A JTextArea can't centre its lines, and an HTML
	 * JLabel brings back the width-hint clipping, so the tile title uses a text pane
	 * with a centred paragraph style.
	 */
	private static javax.swing.JTextPane centeredPane(Font font, Color foreground)
	{
		final javax.swing.JTextPane pane = new javax.swing.JTextPane();
		pane.setEditable(false);
		pane.setFocusable(false);
		pane.setOpaque(false);
		pane.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
		pane.setFont(font);
		pane.setForeground(foreground);
		pane.setAlignmentX(Component.LEFT_ALIGNMENT);

		if (pane.getCaret() instanceof DefaultCaret)
		{
			((DefaultCaret) pane.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
		}
		return pane;
	}

	/** Sets a centred pane's text; alignment has to be re-applied after each change. */
	private static void setCentered(javax.swing.JTextPane pane, String text)
	{
		pane.setText(text);

		final javax.swing.text.SimpleAttributeSet centre = new javax.swing.text.SimpleAttributeSet();
		javax.swing.text.StyleConstants.setAlignment(centre, javax.swing.text.StyleConstants.ALIGN_CENTER);
		pane.getStyledDocument().setParagraphAttributes(0, pane.getDocument().getLength(), centre, false);

		pane.setSize(Brand.CONTENT_WIDTH, Short.MAX_VALUE);
	}

	private static BufferedImage fit(BufferedImage src, int maxW, int maxH)
	{
		final double scale = Math.min(maxW / (double) src.getWidth(), maxH / (double) src.getHeight());
		if (scale >= 1)
		{
			return src;
		}
		final int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
		final int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
		final BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = out.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(src, 0, 0, w, h, null);
		}
		finally
		{
			g.dispose();
		}
		return out;
	}
}
