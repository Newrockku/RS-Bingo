package com.rsbingo;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
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
	/**
	 * The board. Paints each region's perimeter itself, after its cells have drawn.
	 *
	 * It has to be done here rather than in TileCell because the outline goes *around*
	 * the tiles, in the gutter between them — space that belongs to this panel and
	 * that no cell can paint into. Drawing it inside the cells instead put the line on
	 * top of the artwork and made two adjacent regions share one doubled edge.
	 */
	private final JPanel grid = new JPanel()
	{
		@Override
		protected void paintChildren(Graphics g)
		{
			super.paintChildren(g);
			paintRegionOutlines(g);
		}

		/**
		 * Height derived from the width this actually got, not from a constant.
		 *
		 * GRID_WIDTH assumes the panel's full 225px less padding and a scrollbar, but
		 * the width handed out depends on whether that scrollbar is showing. Pinning
		 * the grid to the assumption left it narrower than the wells beneath it, with
		 * the shortfall showing as dead space down the right-hand side. GridLayout
		 * already divides whatever width it is given; only the height has to follow,
		 * or the cells stop being square.
		 */
		@Override
		public Dimension getPreferredSize()
		{
			final int w = getWidth() > 0 ? getWidth() : GRID_WIDTH;
			return new Dimension(w, gridHeightFor(w));
		}

		@Override
		public Dimension getMaximumSize()
		{
			// Unbounded width so BoxLayout stretches it to the viewport instead of
			// leaving it at its preferred size and aligning it left.
			return gridCols <= 0
				? super.getMaximumSize()
				: new Dimension(Integer.MAX_VALUE, gridHeightFor(getWidth() > 0 ? getWidth() : GRID_WIDTH));
		}
	};

	/** Board shape currently laid out, for the sizing above. 0 when empty. */
	private int gridCols;
	private int gridRows;

	/** The square-cell height implied by a given grid width. */
	private int gridHeightFor(int width)
	{
		if (gridCols <= 0 || gridRows <= 0)
		{
			return 0;
		}
		final int inner = width - (REGION_PAD * 2);
		final int cell = Math.max(8, (inner - (gridCols - 1) * CELL_GAP) / gridCols);
		return cell * gridRows + (gridRows - 1) * CELL_GAP + (REGION_PAD * 2);
	}

	/** The board currently drawn in {@link #grid}, for its region painting. */
	private BoardModels.Board gridBoard;

	/**
	 * Empty the board and let it collapse again.
	 *
	 * buildBoard() pins the grid's size to keep its cells square, so clearing it
	 * without releasing that would leave an empty grid still holding a board's worth
	 * of space. Dropping the board reference with it also stops the region painter
	 * drawing against a layout that is no longer there.
	 */
	private void clearGrid()
	{
		gridBoard = null;
		gridCols = 0;
		gridRows = 0;
		grid.removeAll();
		grid.setPreferredSize(null);
		grid.setMaximumSize(null);
		grid.setBorder(null);
	}

	/**
	 * Region perimeter stroke. One pixel, because two of them have to fit side by
	 * side in a {@link #CELL_GAP}-wide gutter without touching.
	 */
	private static final int REGION_EDGE = 1;

	/**
	 * How far outside its own cells a region draws its perimeter.
	 *
	 * Each region hugs its own tiles rather than centring the line in the shared
	 * gutter. Centred, two neighbouring regions computed the same coordinates and
	 * painted over each other: only the second colour survived, and at 2px the
	 * stroke bled onto both cells so the boundary looked like one thick line of
	 * indeterminate owner. Hugging leaves a clear pixel between them, so a shared
	 * border reads as two lines — one per region, each in its own colour.
	 */
	private static final int REGION_INSET = 1;

	/** Margin around the grid so an edge tile's outline has somewhere to sit. */
	private static final int REGION_PAD = 3;

	/**
	 * Each region's perimeter, drawn in the gutters around its tiles.
	 *
	 * A side is drawn only where the neighbouring square holds a different region, so
	 * a group of tiles reads as one enclosed area rather than a set of boxed ones. The
	 * line sits in the gap just outside its own cells, which is why it surrounds the
	 * tiles instead of sitting on their artwork — and why adjacent cells of the same
	 * region produce no line at all between them.
	 */
	private void paintRegionOutlines(Graphics graphics)
	{
		final BoardModels.Board b = gridBoard;
		if (b == null || !b.hasRegions())
		{
			return;
		}

		final Graphics2D g = (Graphics2D) graphics.create();
		try
		{
			// Off: these are axis-aligned hairlines, and antialiasing only blurs them.
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
			g.setStroke(new java.awt.BasicStroke(REGION_EDGE));

			final int cols = Math.max(1, b.cols);
			final int rows = Math.max(1, b.rows);
			final int o = REGION_INSET;

			for (int i = 0; i < grid.getComponentCount(); i++)
			{
				final java.awt.Component comp = grid.getComponent(i);
				if (!(comp instanceof TileCell))
				{
					continue;
				}
				final BoardModels.BoardTile t = ((TileCell) comp).tile();
				final int idx = b.regionIndex(t.region);
				if (idx < 0)
				{
					continue;
				}

				final java.awt.Rectangle r = comp.getBounds();
				final int row = (t.pos - 1) / cols;
				final int col = (t.pos - 1) % cols;
				final int x1 = r.x - o;
				final int y1 = r.y - o;
				final int x2 = r.x + r.width + o - 1;
				final int y2 = r.y + r.height + o - 1;

				final boolean up = sameRegion(b, t.region, row - 1, col, rows, cols);
				final boolean down = sameRegion(b, t.region, row + 1, col, rows, cols);
				final boolean left = sameRegion(b, t.region, row, col - 1, rows, cols);
				final boolean right = sameRegion(b, t.region, row, col + 1, rows, cols);

				// Reach across the gutter towards neighbours in the same region, and
				// stop short of ones in another. Hugging its own cells leaves each edge
				// ending REGION_INSET short of the gutter's far side, so two tiles in
				// one region left an unpainted pixel between their edges and the
				// perimeter came out dashed. Extending only towards its own kind keeps
				// that pixel from being claimed where two regions meet.
				final int reach = Math.max(0, CELL_GAP - (REGION_INSET * 2));
				final int ex1 = x1 - (left ? reach : 0);
				final int ex2 = x2 + (right ? reach : 0);
				final int ey1 = y1 - (up ? reach : 0);
				final int ey2 = y2 + (down ? reach : 0);

				g.setColor(Brand.regionColor(idx));
				if (!up) g.drawLine(ex1, y1, ex2, y1);
				if (!down) g.drawLine(ex1, y2, ex2, y2);
				if (!left) g.drawLine(x1, ey1, x1, ey2);
				if (!right) g.drawLine(x2, ey1, x2, ey2);
			}
		}
		finally
		{
			g.dispose();
		}
	}

	/** Whether the square at this row/col carries the region named. */
	private static boolean sameRegion(BoardModels.Board b, String region, int row, int col,
		int rows, int cols)
	{
		if (row < 0 || row >= rows || col < 0 || col >= cols)
		{
			return false;
		}
		return region != null && !region.isEmpty()
			&& region.equals(b.regionAt(row * cols + col + 1));
	}
	private final JLabel countdown = new JLabel();
	/**
	 * Shown next to the event name, but only when the site itself would show it —
	 * plugin_board.php applies event.html's own hide/release rules before sending it,
	 * so an event still withholding its codeword sends nothing and this stays hidden.
	 */
	private final JLabel codewordLabel = new JLabel();

	/** Says why a board has no tiles on it, when that is deliberate. */
	private final JLabel hiddenNotice = new JLabel();

	/**
	 * Everything below is rebuilt by {@link #buildUi()} on a theme change: these
	 * components bake the palette in when they are created, so they are recreated
	 * rather than recoloured. The panel object itself never changes, which is what
	 * keeps the sidebar open.
	 */
	private JLabel standingsHeading;
	private JPanel standings;
	private JLabel rosterHeading;
	private JLabel pointsHeading;
	private JPanel pointsWell;
	private JPanel pointsBreakdown;
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

		hiddenNotice.setFont(FontManager.getRunescapeSmallFont());
		hiddenNotice.setForeground(Brand.TEXT_DIM);
		hiddenNotice.setBorder(BorderFactory.createEmptyBorder(2, 0, 6, 0));

		// BoxLayout lays children out around a shared alignment axis, so a mix of
		// LEFT and the JComponent default (CENTRE) makes it reserve space on both
		// sides and squeeze components. They all have to agree.
		for (JComponent c : new JComponent[]{eventHeading, eventBox, themeHeading, themeBox,
			teamLabel, teamBox, headerLabel, statusLabel, countdown, codewordLabel,
			hiddenNotice})
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

		pointsHeading = Brand.sectionLabel("Points");
		pointsWell = Brand.well();
		pointsBreakdown = Brand.section();
		pointsWell.add(pointsBreakdown);

		final JPanel boardCard = new JPanel();
		boardCard.setLayout(new BoxLayout(boardCard, BoxLayout.Y_AXIS));
		boardCard.setBackground(Brand.BG_TILE);

		for (JComponent c : new JComponent[]{grid, standingsHeading, standings,
			rosterHeading, rosterWell, pointsHeading, pointsWell})
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

			if (showing == null)
			{
				return;
			}

			// A board with no team is the event summary: teams and standings, no
			// tiles. Redrawing that through showBoard paints an empty grid and
			// "0/0 tiles" over whatever was there. It happens on first open, where
			// the themes arrive between the summary and the team's board, and it
			// used to stick, because the refresh timer had no team to re-fetch.
			// Going back through showEvent picks a team and asks for its board.
			if (showing.team == null)
			{
				showEvent(showing);
				return;
			}

			showBoard(showing);
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
			buildPoints(null);
			applyCountdown(board);

			clearGrid();
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

	/**
	 * Where the team's total came from, itemised as the website's points tooltip
	 * does it: tile points, each line bonus that applies, the collection-log bonus,
	 * a total, then the per-tile contributions.
	 *
	 * The server sends this already summed and sorted, so nothing here re-derives a
	 * number that appears elsewhere in the panel.
	 */
	private void buildPoints(BoardModels.Points points)
	{
		pointsBreakdown.removeAll();

		final boolean any = points != null;
		pointsHeading.setVisible(any);
		pointsWell.setVisible(any);
		if (!any)
		{
			return;
		}

		pointsBreakdown.add(pointsRow("Tile pts", Text.thousands(points.tilePts), false));

		// Off Showdown the site names the count of completed lines rather than a flat
		// bonus, because two completed rows pay twice.
		if (points.rowPts != 0)
		{
			pointsBreakdown.add(pointsRow(points.isShowdown ? "Row bonus" : "Rows (x" + points.rows + ")",
				"+" + Text.thousands(points.rowPts), false));
		}
		if (points.colPts != 0)
		{
			pointsBreakdown.add(pointsRow(points.isShowdown ? "Col bonus" : "Cols (x" + points.cols + ")",
				"+" + Text.thousands(points.colPts), false));
		}
		if (points.diagPts != 0)
		{
			pointsBreakdown.add(pointsRow(points.isShowdown ? "Diag bonus" : "Diags (x" + points.diags + ")",
				"+" + Text.thousands(points.diagPts), false));
		}
		if (points.clogPts != 0)
		{
			pointsBreakdown.add(pointsRow("Coll. Log", "+" + Text.thousands(points.clogPts), false));
		}

		// The site prints a total only when something was added to the tile points;
		// otherwise the total is the line above it and says nothing new.
		if (points.hasBonuses())
		{
			pointsBreakdown.add(Box.createVerticalStrut(2));
			pointsBreakdown.add(pointsRow("Total", Text.thousands(points.total), true));
		}

		if (!points.tiles.isEmpty())
		{
			pointsBreakdown.add(Box.createVerticalStrut(6));
			final JLabel caption = new JLabel("Tile breakdown");
			caption.setFont(FontManager.getRunescapeSmallFont());
			caption.setForeground(Brand.TEXT_DIM);
			caption.setAlignmentX(LEFT_ALIGNMENT);
			caption.setBorder(BorderFactory.createEmptyBorder(0, 0, 2, 0));
			pointsBreakdown.add(caption);

			for (BoardModels.PointsTile tile : points.tiles)
			{
				pointsBreakdown.add(Brand.valueRow(
					tile.label == null ? "" : tile.label,
					Text.thousands(tile.pts),
					Brand.TEXT_DIM, Brand.TEXT_MAIN, null));
			}
		}
	}

	private JPanel pointsRow(String label, String value, boolean total)
	{
		return Brand.valueRow(label, value,
			total ? Brand.TEXT_BRIGHT : Brand.TEXT_MAIN,
			total ? Brand.TEXT_BRIGHT : Brand.ACCENT,
			null);
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

			// Marked with a filled row rather than by colour alone. The accent is not
			// reliably brighter than the body text: on Slate the text is near-white
			// (#dcddde) and the accent is a mid blue (#7289da), so the highlighted
			// name came out *dimmer* than everyone else's. A background reads as
			// "this one" whatever hue a theme picks.
			final JTextArea row = Brand.wrapping(FontManager.getRunescapeSmallFont(),
				isMe ? Brand.TEXT_BRIGHT : Brand.TEXT_MAIN);
			Brand.setWrapped(row, isMe ? (player + "  (you)") : player);

			// Same padding on every row, so the highlighted one does not shift.
			row.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
			if (isMe)
			{
				row.setOpaque(true);
				row.setBackground(Brand.blend(Brand.BG_WELL, Brand.ACCENT, 0.28f));
			}
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

		applyHiddenNotice(board);
	}

	/**
	 * Says a blank board is blank on purpose.
	 *
	 * An event can withhold tile names, artwork and checklists until shortly before
	 * it starts. Left unexplained that looks exactly like a board that failed to
	 * load, which is how it was first reported.
	 */
	private void applyHiddenNotice(BoardModels.Board board)
	{
		if (!board.hiddenTiles)
		{
			hiddenNotice.setVisible(false);
			hiddenNotice.setText("");
			return;
		}

		final String when = Text.untilRelease(board.tilesReleaseAt, java.time.Instant.now());
		hiddenNotice.setVisible(true);
		hiddenNotice.setText(when == null
			? "Tiles hidden by the organiser"
			: ("Tiles hidden - revealed in " + when));
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
			// Nothing loaded yet: open the first event rather than asking for a code
			// the dropdown is already holding. Linking an account is meant to replace
			// typing codes, and an empty board next to a full list of your own events
			// is the plugin withholding something it plainly has.
			BoardModels.EventSummary autoSelect = null;
			if ((loaded == null || loaded.trim().isEmpty()) && !list.events.isEmpty())
			{
				autoSelect = list.events.get(0);
				eventBox.setSelectedItem(autoSelect);
			}
			populating = false;

			final boolean any = eventBox.getItemCount() > 0;
			eventHeading.setVisible(any);
			eventBox.setVisible(any);
			revalidate();
			repaint();

			// Outside the populating guard: this writes the code to the config, which
			// is what actually loads the board.
			if (autoSelect != null && autoSelect.eventId != null)
			{
				onEventSelected.accept(autoSelect.eventId);
			}
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
			buildPoints(board.points);

			final int cols = Math.max(1, board.cols);
			final int rows = Math.max(1, board.rows);

			// Square cells at any board size.
			//
			// GridLayout ignores a child's preferred size and simply divides the space
			// it is given, so a cell is only square if the panel's height matches what
			// its width implies. That used to be left to chance: the cell size carried a
			// 24px floor, and on a 9x9 board the floor made the grid want 240px of
			// height inside 209px of width, which GridLayout resolved as 20x24 cells.
			// Deriving the height from the width that will actually be used keeps every
			// board square, at the cost of small cells on the largest ones — which is
			// unavoidable in a 225px panel.
			gridBoard = board;
			gridCols = cols;
			gridRows = rows;
			grid.removeAll();
			grid.setLayout(new GridLayout(0, cols, CELL_GAP, CELL_GAP));
			// Room for a region outline on an edge tile, which sits outside the cell.
			grid.setBorder(BorderFactory.createEmptyBorder(REGION_PAD, REGION_PAD, REGION_PAD, REGION_PAD));
			// Sizes come from the overrides on the field; setting them here would pin
			// the grid to a width it may not be given.
			grid.setPreferredSize(null);
			grid.setMaximumSize(null);

			// Only a hint for the cells' preferred size — GridLayout divides the real
			// width among them regardless, which is what lets the grid stretch.
			final int cell = Math.max(8,
				((GRID_WIDTH - (REGION_PAD * 2)) - (cols - 1) * CELL_GAP) / cols);
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

				// Show the card as well as filling it. On a refresh the tile card is
				// already up so this changes nothing, but a theme change rebuilds the
				// deck from scratch and it comes back showing the board — the detail
				// was being updated behind a grid the reader had not asked for.
				cards.show(deck, CARD_TILE);
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
		if (current == null)
		{
			return;
		}

		// Falls back to the dropdown when what is loaded is the event summary, which
		// carries no team. Without this the timer gave up on exactly the state it
		// most needed to repair, and the panel sat empty until a team was picked by
		// hand.
		String team = current.team;
		if (team == null)
		{
			final BoardModels.TeamSummary selected = (BoardModels.TeamSummary) teamBox.getSelectedItem();
			team = selected == null ? null : selected.name;
		}

		if (team != null && !team.isEmpty())
		{
			loadTeam(team);
		}
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
			clearGrid();
			grid.revalidate();
			grid.repaint();
			cards.show(deck, CARD_BOARD);
		});
	}
}
