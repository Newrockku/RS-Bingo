package com.rsbingo;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.JTextArea;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Side panel: event code, team dropdown, the board grid, and — swapped in over it —
 * a tile's detail view.
 */
class RsBingoPanel extends PluginPanel
{
	private static final String CARD_BOARD = "board";
	private static final String CARD_TILE = "tile";

	private static final int CELL_GAP = 3;
	/** Panel width less this class's insets and the scrollbar. */
	private static final int GRID_WIDTH = PANEL_WIDTH - (Brand.PAD * 2) - Brand.SCROLLBAR;

	private final RsBingoApi api;
	private final RsBingoConfig config;

	/** Hardcoded in the client; see the constructor. */
	private final String siteUrl;
	private final Runnable onEventCodeChanged;
	private final Consumer<String> onTeamSelected;
	private final Consumer<BoardModels.Theme> onThemeSelected;
	private final Consumer<String> onEventSelected;
	private final ScreenshotSource screenshots;

	/** Grabs the next rendered game frame. Supplied by the plugin, which has DrawManager. */
	interface ScreenshotSource
	{
		void capture(Consumer<java.awt.Image> onFrame);
	}
	private final TileImageCache images;

	private final JComboBox<BoardModels.TeamSummary> teamBox = new JComboBox<>();
	private final JLabel statusLabel = new JLabel();
	/** Wraps rather than clips: event names are organiser-supplied and can be long. */
	private final JTextArea headerLabel = new JTextArea();
	private final JPanel grid = new JPanel();
	private final JLabel countdown = new JLabel();
	/**
	 * Shown next to the event name, but only when the site itself would show it —
	 * plugin_board.php applies event.html's own hide/release rules before sending it,
	 * so an event still withholding its codeword sends nothing and this stays hidden.
	 */
	private final JLabel codewordLabel = new JLabel();

	/**
	 * Everything below is rebuilt by {@link #buildUi()} on a theme change: these
	 * components bake the palette in when they are created, so they are recreated
	 * rather than recoloured. The panel object itself never changes, which is what
	 * keeps the sidebar open.
	 */
	private JLabel standingsHeading;
	private JPanel standings;
	private JLabel rosterHeading;
	private JPanel rosterWell;
	private JPanel roster;
	private JLabel themeHeading;

	/** Populated from the site, so a theme added there needs no plugin change. */
	private final JComboBox<BoardModels.Theme> themeBox = new JComboBox<>();

	/**
	 * The events a linked account belongs to. Hidden entirely until an account token
	 * is set, because without one the plugin has no idea who is playing and the event
	 * code in settings is the only way in.
	 */
	private JLabel eventHeading;
	private final JComboBox<BoardModels.EventSummary> eventBox = new JComboBox<>();

	/**
	 * The logged-in character, once the client reports one. Used to pick the team you
	 * are actually on and to mark you in the roster — the one thing the panel knows
	 * that the website cannot.
	 */
	private String localPlayer;
	private final CardLayout cards = new CardLayout();
	private JPanel deck;
	private TileDetailPanel detail;
	/** Held so opening a tile can rewind it to the top. */
	private JScrollPane detailScroll;

	private BoardModels.Board current;
	/** Guards the team dropdown's listener while we repopulate it. */
	private boolean populating;
	/**
	 * Which tile the detail view is showing, or null when the grid is up. The
	 * refresh timer uses this to redraw an open tile in place instead of throwing
	 * the reader back to the board every minute.
	 */
	private Integer openTilePos;

	/**
	 * @param siteUrl the address every request goes to. Always
	 *                {@link RsBingoConfig#SITE_URL} in the client; a parameter only so
	 *                the preview harness can render against a local copy of the site.
	 */
	RsBingoPanel(RsBingoApi api, TileImageCache images, RsBingoConfig config, String siteUrl,
				 Runnable onEventCodeChanged, Consumer<String> onTeamSelected,
				 Consumer<BoardModels.Theme> onThemeSelected, Consumer<String> onEventSelected,
				 ScreenshotSource screenshots)
	{
		super(false);
		this.api = api;
		this.images = images;
		this.config = config;
		this.siteUrl = siteUrl;
		this.onEventCodeChanged = onEventCodeChanged;
		this.onTeamSelected = onTeamSelected;
		this.onThemeSelected = onThemeSelected;
		this.onEventSelected = onEventSelected;
		this.screenshots = screenshots;

		// Listeners are attached once, here. buildUi() runs again on every theme
		// change and must not add a second copy of any of them.
		teamBox.addActionListener(e ->
		{
			if (populating)
			{
				return;
			}
			final BoardModels.TeamSummary sel = (BoardModels.TeamSummary) teamBox.getSelectedItem();
			if (sel != null && sel.name != null)
			{
				// A deliberate team switch starts at the board, not on whichever tile
				// happened to be open for the previous team.
				openTilePos = null;
				onTeamSelected.accept(sel.name);
				loadTeam(sel.name);
			}
		});

		eventBox.addActionListener(e ->
		{
			if (populating)
			{
				return;
			}
			final BoardModels.EventSummary sel = (BoardModels.EventSummary) eventBox.getSelectedItem();
			if (sel != null && sel.eventId != null && !sel.eventId.equalsIgnoreCase(eventCode()))
			{
				// Switching events invalidates the team saved for the previous one;
				// preferredTeam() falls back to the player's own team or the leader.
				openTilePos = null;
				onEventSelected.accept(sel.eventId);
			}
		});

		themeBox.addActionListener(e ->
		{
			if (populating)
			{
				return;
			}
			final BoardModels.Theme sel = (BoardModels.Theme) themeBox.getSelectedItem();
			if (sel != null && sel.key != null && !sel.key.equals(config.theme()))
			{
				onThemeSelected.accept(sel);
			}
		});

		buildUi();
	}

	/**
	 * Builds the panel's contents from the current palette.
	 *
	 * Called again whenever the theme changes. Swapping the whole panel out from
	 * under the navigation button worked, but collapsed the sidebar every time —
	 * rebuilding in place keeps the button, and the panel, exactly where they are.
	 */
	private void buildUi()
	{
		removeAll();

		setLayout(new BorderLayout());
		setBackground(Brand.BG_TILE);
		setBorder(BorderFactory.createEmptyBorder(6, Brand.PAD, 6, Brand.PAD));

		// ── controls ────────────────────────────────────────────────────────
		final JPanel controls = new JPanel();
		controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
		controls.setBackground(Brand.BG_TILE);

		// Your own events first: this is the fastest way in when an account is linked.
		eventHeading = Brand.sectionLabel("Event");
		styleCombo(eventBox);
		final boolean haveEvents = eventBox.getItemCount() > 0;
		eventHeading.setVisible(haveEvents);
		eventBox.setVisible(haveEvents);

		// Theme second: it is the one control that changes everything below it.
		themeHeading = Brand.sectionLabel("Theme");
		styleCombo(themeBox);
		final boolean haveThemes = themeBox.getItemCount() > 0;
		themeHeading.setVisible(haveThemes);
		themeBox.setVisible(haveThemes);

		final JLabel teamLabel = Brand.sectionLabel("Team");
		styleCombo(teamBox);

		headerLabel.setFont(Brand.bold(14f));
		headerLabel.setForeground(Brand.TEXT_BRIGHT);
		headerLabel.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
		headerLabel.setLineWrap(true);
		headerLabel.setWrapStyleWord(true);
		headerLabel.setEditable(false);
		headerLabel.setFocusable(false);
		headerLabel.setOpaque(false);

		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setForeground(Brand.TEXT_DIM);
		statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));

		countdown.setFont(FontManager.getRunescapeSmallFont());
		countdown.setForeground(Brand.TEXT_MAIN);
		countdown.setBorder(BorderFactory.createEmptyBorder(1, 0, 0, 0));

		codewordLabel.setFont(FontManager.getRunescapeSmallFont());
		codewordLabel.setForeground(Brand.ACCENT);
		codewordLabel.setBorder(BorderFactory.createEmptyBorder(2, 0, 8, 0));

		// BoxLayout lays children out around a shared alignment axis, so a mix of
		// LEFT and the JComponent default (CENTRE) makes it reserve space on both
		// sides and squeeze components. They all have to agree.
		for (JComponent c : new JComponent[]{eventHeading, eventBox, themeHeading, themeBox,
			teamLabel, teamBox, headerLabel, statusLabel, countdown, codewordLabel})
		{
			c.setAlignmentX(LEFT_ALIGNMENT);
			controls.add(c);
		}

		// ── board grid, then what the site shows around it ──────────────────
		grid.setBackground(Brand.BG_TILE);

		standingsHeading = Brand.sectionLabel("Standings");
		standings = Brand.section();
		rosterHeading = Brand.sectionLabel("Team");
		rosterWell = Brand.well();
		roster = Brand.section();
		rosterWell.add(roster);

		final JPanel boardCard = new JPanel();
		boardCard.setLayout(new BoxLayout(boardCard, BoxLayout.Y_AXIS));
		boardCard.setBackground(Brand.BG_TILE);

		for (JComponent c : new JComponent[]{grid, standingsHeading, standings,
			rosterHeading, rosterWell})
		{
			c.setAlignmentX(LEFT_ALIGNMENT);
			boardCard.add(c);
		}

		detail = new TileDetailPanel(images, config, siteUrl, submitter, this::closeTile);
		detailScroll = scrolling(detail);

		deck = new JPanel(cards);
		deck.setBackground(Brand.BG_TILE);
		// Both cards scroll: a 7x7 board and a long item checklist each outrun the
		// panel's height, and PluginPanel(false) provides no scrolling of its own.
		deck.add(scrolling(boardCard), CARD_BOARD);
		deck.add(detailScroll, CARD_TILE);

		add(controls, BorderLayout.NORTH);
		add(deck, BorderLayout.CENTER);

		cards.show(deck, CARD_BOARD);
	}

	/**
	 * Files submissions on behalf of the tile view.
	 *
	 * Gating lives here because this is what knows the roster: the logged-in
	 * character must be on the team currently being viewed. Viewing another team's
	 * board offers no submit controls at all, since a submission is recorded against
	 * a team and filing one for a team you are not on would simply be wrong.
	 */
	private final TileDetailPanel.Submitter submitter = new TileDetailPanel.Submitter()
	{
		@Override
		public String submittingAs()
		{
			if (localPlayer == null || current == null || current.team == null)
			{
				return null;
			}
			final BoardModels.TeamSummary team = teamNamed(current, current.team);
			if (team == null)
			{
				return null;
			}
			for (String player : team.players)
			{
				if (localPlayer.equalsIgnoreCase(player))
				{
					// Return the roster's spelling: submit_item.php matches it exactly.
					return player;
				}
			}
			return null;
		}

		@Override
		public boolean canSubmit()
		{
			// Being on the team is the whole authorisation. The codeword is only
			// stamped onto the screenshot, so a withheld one must not block anyone.
			return submittingAs() != null;
		}

		@Override
		public void submit(BoardModels.BoardTile tile, BoardModels.SubmitOption option,
						   Consumer<String> onStatus)
		{
			final String as = submittingAs();
			if (as == null || current == null)
			{
				onStatus.accept("You are not on this team.");
				return;
			}

			// The frame arrives on the client thread; everything after it is network
			// work, and the status callback has to land back on the EDT.
			screenshots.capture(frame ->
			{
				final byte[] png = ProofShot.stamp(frame, current.name, codeword(), as, option.label);
				if (png == null)
				{
					SwingUtilities.invokeLater(() -> onStatus.accept("Could not capture the screen."));
					return;
				}

				SwingUtilities.invokeLater(() -> onStatus.accept("Uploading…"));
				api.submitItem(siteUrl, current.eventId,
					current.team, as, tile.pos, tile.id, option, png,
					() -> SwingUtilities.invokeLater(() ->
					{
						onStatus.accept("Submitted - awaiting review.");
						// Pull the board back so the item shows its pending "?" at once.
						refreshCurrentTeam();
					}),
					error -> SwingUtilities.invokeLater(() -> onStatus.accept(error)));
			});
		}
	};

	/** The event's codeword, once the site has released it. */
	private String codeword()
	{
		if (current == null || current.codeword == null || current.codeword.trim().isEmpty())
		{
			return null;
		}
		return current.codeword.trim();
	}

	private static void styleCombo(JComboBox<?> box)
	{
		box.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		box.setBackground(Brand.BG_WELL);
		box.setForeground(Brand.TEXT_BRIGHT);
		box.setFont(FontManager.getRunescapeSmallFont());
	}

	/**
	 * Repaints the panel in the newly applied palette and redraws whatever it was
	 * showing, so a theme change costs no network round trip.
	 */
	void reskin()
	{
		SwingUtilities.invokeLater(() ->
		{
			final BoardModels.Board showing = current;
			buildUi();
			revalidate();
			repaint();

			if (showing != null)
			{
				showBoard(showing);
			}
		});
	}

	private static JScrollPane scrolling(JComponent content)
	{
		final JScrollPane sp = new JScrollPane(new VerticalContent(content),
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		sp.setBorder(BorderFactory.createEmptyBorder());
		sp.getViewport().setBackground(Brand.BG_TILE);
		sp.setBackground(Brand.BG_TILE);
		sp.getVerticalScrollBar().setUnitIncrement(16);
		sp.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
		return sp;
	}

	/**
	 * Scrolled content that takes the viewport's width instead of its own preferred
	 * width.
	 *
	 * Without this a plain panel keeps whatever width its widest child asked for,
	 * and since there is no horizontal scrollbar the overflow is simply cut off —
	 * tag lists and descriptions lost their right-hand edge mid-word.
	 */
	private static class VerticalContent extends JPanel implements Scrollable
	{
		VerticalContent(JComponent view)
		{
			setLayout(new BorderLayout());
			setBackground(Brand.BG_TILE);
			// NORTH, so content keeps its natural height and scrolls rather than
			// being stretched down the panel.
			add(view, BorderLayout.NORTH);
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction)
		{
			return visible.height;
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}

	/**
	 * The event being viewed. Read from the config rather than a field in the panel:
	 * the code is a setting, not board data, and having it in both places meant the
	 * panel's copy could overwrite one typed into RuneLite's settings.
	 */
	private String eventCode()
	{
		final String code = config.eventCode();
		return code == null ? "" : code.trim().toUpperCase();
	}

	void setStatus(String text)
	{
		SwingUtilities.invokeLater(() -> statusLabel.setText(text == null ? "" : text));
	}

	/** Event summary + team list; selects a team and loads its board. */
	void showEvent(BoardModels.Board board)
	{
		SwingUtilities.invokeLater(() ->
		{
			current = board;
			openTilePos = null;
			headerLabel.setText(board.name == null ? "" : board.name);
			statusLabel.setText(board.teams.size() + (board.teams.size() == 1 ? " team" : " teams"));

			populating = true;
			teamBox.removeAllItems();
			for (BoardModels.TeamSummary t : board.teams)
			{
				teamBox.addItem(t);
			}

			// Prefer the team last viewed, so reopening the client lands where you
			// left off rather than on whoever is top of the table today.
			final BoardModels.TeamSummary preferred = preferredTeam(board);
			if (preferred != null)
			{
				teamBox.setSelectedItem(preferred);
			}
			populating = false;

			buildStandings(board, preferred == null ? null : preferred.name);
			buildRoster(preferred);
			applyCountdown(board);

			grid.removeAll();
			grid.revalidate();
			grid.repaint();
			cards.show(deck, CARD_BOARD);

			if (preferred != null && preferred.name != null)
			{
				loadTeam(preferred.name);
			}
		});
	}

	/** Every team, ranked, with the one being viewed picked out. */
	private void buildStandings(BoardModels.Board board, String viewing)
	{
		standings.removeAll();
		final boolean any = !board.teams.isEmpty();
		standingsHeading.setVisible(any);
		standings.setVisible(any);

		int rank = 0;
		for (BoardModels.TeamSummary team : board.teams)
		{
			rank++;
			final boolean mine = team.name != null && team.name.equalsIgnoreCase(viewing);
			standings.add(Brand.valueRow(
				rank + ". " + (team.name == null ? "" : team.name),
				String.valueOf(team.points),
				mine ? Brand.TEXT_BRIGHT : Brand.TEXT_DIM,
				mine ? Brand.ACCENT : Brand.TEXT_MAIN,
				mine ? Brand.BG_COMPLETED_WELL : Brand.BG_WELL));
			standings.add(Box.createVerticalStrut(2));
		}
	}

	/** Who is on the selected team, with the logged-in character marked. */
	private void buildRoster(BoardModels.TeamSummary team)
	{
		roster.removeAll();
		final boolean any = team != null && !team.players.isEmpty();
		rosterHeading.setVisible(any);
		rosterWell.setVisible(any);

		if (!any)
		{
			return;
		}

		rosterHeading.setText(("Team (" + team.players.size() + ")").toUpperCase());

		for (String player : team.players)
		{
			final boolean isMe = localPlayer != null && localPlayer.equalsIgnoreCase(player);
			final JTextArea row = Brand.wrapping(FontManager.getRunescapeSmallFont(),
				isMe ? Brand.ACCENT : Brand.TEXT_MAIN);
			Brand.setWrapped(row, isMe ? (player + "  (you)") : player);
			row.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
			roster.add(row);
		}
	}

	private void applyCountdown(BoardModels.Board board)
	{
		final String text = Text.eventCountdown(board.startDate, board.endDate, java.time.Instant.now());
		countdown.setVisible(text != null);
		countdown.setText(text == null ? "" : text);

		final boolean hasCodeword = board.codeword != null && !board.codeword.trim().isEmpty();
		codewordLabel.setVisible(hasCodeword);
		codewordLabel.setText(hasCodeword ? ("Codeword: " + board.codeword.trim()) : "");
	}

	private static BoardModels.TeamSummary teamNamed(BoardModels.Board board, String name)
	{
		for (BoardModels.TeamSummary t : board.teams)
		{
			if (t.name != null && t.name.equalsIgnoreCase(name))
			{
				return t;
			}
		}
		return null;
	}

	/**
	 * Offers the linked account's events in the panel's dropdown, selecting whichever
	 * one is currently loaded. Hidden when the account has no events, rather than
	 * showing an empty control.
	 */
	void setMyEvents(BoardModels.EventList list)
	{
		SwingUtilities.invokeLater(() ->
		{
			populating = true;
			eventBox.removeAllItems();

			final String loaded = eventCode();
			for (BoardModels.EventSummary event : list.events)
			{
				eventBox.addItem(event);
				if (event.eventId != null && event.eventId.equalsIgnoreCase(loaded))
				{
					eventBox.setSelectedItem(event);
				}
			}
			populating = false;

			final boolean any = eventBox.getItemCount() > 0;
			eventHeading.setVisible(any);
			eventBox.setVisible(any);
			revalidate();
			repaint();
		});
	}

	/**
	 * Offers the site's themes in the panel's dropdown, selecting the saved one.
	 * Called once the list arrives; until then the switcher stays hidden rather than
	 * showing an empty control.
	 */
	void setThemes(BoardModels.ThemeList list)
	{
		SwingUtilities.invokeLater(() ->
		{
			populating = true;
			themeBox.removeAllItems();

			final String saved = config.theme() == null || config.theme().isEmpty()
				? list.defaultKey
				: config.theme();

			for (BoardModels.Theme theme : list.themes)
			{
				themeBox.addItem(theme);
				if (theme.key != null && theme.key.equals(saved))
				{
					themeBox.setSelectedItem(theme);
				}
			}
			populating = false;

			final boolean any = themeBox.getItemCount() > 0;
			themeHeading.setVisible(any);
			themeBox.setVisible(any);
		});
	}

	/**
	 * Tells the panel which character is logged in, so it can pick that player's team
	 * and mark them in the roster. Safe to call before a board has loaded.
	 */
	void setLocalPlayer(String name)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (name == null || name.equals(localPlayer))
			{
				return;
			}
			localPlayer = name;

			// Redraw everything this feeds, not just the roster: the name decides
			// whether the open tile offers submit controls, and it usually arrives a
			// tick or two after a board is already on screen. No refetch — this
			// redraws the board already held.
			if (current != null)
			{
				showBoard(current);
			}
		});
	}

	private BoardModels.TeamSummary preferredTeam(BoardModels.Board board)
	{
		if (board.teams.isEmpty())
		{
			return null;
		}

		// An explicit choice wins; it is the one thing the user actually asked for.
		final String saved = config.selectedTeam() == null ? "" : config.selectedTeam().trim();
		if (!saved.isEmpty())
		{
			final BoardModels.TeamSummary chosen = teamNamed(board, saved);
			if (chosen != null)
			{
				return chosen;
			}
		}

		// Otherwise the team the logged-in character is actually on, which is almost
		// always the one they want and saves them hunting through the dropdown.
		if (localPlayer != null)
		{
			for (BoardModels.TeamSummary t : board.teams)
			{
				for (String player : t.players)
				{
					if (localPlayer.equalsIgnoreCase(player))
					{
						return t;
					}
				}
			}
		}

		return board.teams.get(0);
	}

	/** A team's board. */
	void showBoard(BoardModels.Board board)
	{
		SwingUtilities.invokeLater(() ->
		{
			current = board;

			int pts = 0;
			for (BoardModels.TeamSummary t : board.teams)
			{
				if (t.name != null && t.name.equals(board.team))
				{
					pts = t.points;
					break;
				}
			}

			int done = 0;
			int placed = 0;
			for (BoardModels.BoardTile t : board.board)
			{
				if (t.empty)
				{
					continue;
				}
				placed++;
				if (t.done)
				{
					done++;
				}
			}
			statusLabel.setText(pts + " pts  ·  " + done + "/" + placed + " tiles");
			applyCountdown(board);
			buildStandings(board, board.team);
			buildRoster(teamNamed(board, board.team));

			final int cols = Math.max(1, board.cols);
			final int cell = Math.max(24, (GRID_WIDTH - (cols - 1) * CELL_GAP) / cols);

			grid.removeAll();
			grid.setLayout(new GridLayout(0, cols, CELL_GAP, CELL_GAP));
			for (BoardModels.BoardTile tile : board.board)
			{
				grid.add(new TileCell(tile, board, images, config, siteUrl, cell,
					() -> openTile(tile, board)));
			}
			grid.revalidate();
			grid.repaint();

			// A refresh landing while a tile is open should update that tile, not
			// yank the reader back to the grid.
			final BoardModels.BoardTile open = findTile(board, openTilePos);
			if (open != null)
			{
				detail.show(open, board);
			}
			else
			{
				openTilePos = null;
				cards.show(deck, CARD_BOARD);
			}
		});
	}

	private static BoardModels.BoardTile findTile(BoardModels.Board board, Integer pos)
	{
		if (pos == null)
		{
			return null;
		}
		for (BoardModels.BoardTile t : board.board)
		{
			if (t.pos == pos && !t.empty)
			{
				return t;
			}
		}
		return null;
	}

	private void openTile(BoardModels.BoardTile tile, BoardModels.Board board)
	{
		openTilePos = tile.pos;
		detail.show(tile, board);
		cards.show(deck, CARD_TILE);

		// A newly opened tile starts at its title, not wherever the last one was left.
		// Deferred so it runs after the swapped-in card has been laid out.
		SwingUtilities.invokeLater(() -> detailScroll.getVerticalScrollBar().setValue(0));

		// The board fetch carries no per-player breakdown, so ask for this tile's now
		// that it is open. showBoard() redraws the open tile in place when it lands,
		// which is the same path the refresh timer uses.
		if (board.team != null && !tile.rates.isEmpty() && tile.players.isEmpty())
		{
			loadTeam(board.team);
		}
	}

	private void closeTile()
	{
		openTilePos = null;
		cards.show(deck, CARD_BOARD);
	}

	private void loadTeam(String team)
	{
		final String code = eventCode();
		if (code.isEmpty())
		{
			return;
		}
		setStatus("Loading " + team + "…");
		// Ask for the open tile's player breakdown in the same request. The server
		// only sends it for this one tile, which is what keeps the response small.
		api.fetchBoard(siteUrl, code, team, openTilePos == null ? 0 : openTilePos,
			this::showBoard, this::setStatus);
	}

	/** Re-fetch the team currently selected, if any. Used by the refresh timer. */
	void refreshCurrentTeam()
	{
		if (current == null || current.team == null)
		{
			return;
		}
		loadTeam(current.team);
	}

	void clearBoard()
	{
		SwingUtilities.invokeLater(() ->
		{
			current = null;
			openTilePos = null;
			headerLabel.setText("");
			populating = true;
			teamBox.removeAllItems();
			populating = false;
			grid.removeAll();
			grid.revalidate();
			grid.repaint();
			cards.show(deck, CARD_BOARD);
		});
	}
}
