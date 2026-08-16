package com.rsbingo;

import com.google.gson.Gson;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import okhttp3.OkHttpClient;

/**
 * Renders the side panel to PNG files without launching a RuneLite client.
 *
 * `./gradlew run` needs a full client, a login and a few minutes; this draws the
 * real panel — same components, same fonts, same live data — in a couple of
 * seconds, which is what you want when checking a layout change.
 *
 * <pre>
 *   ./gradlew preview -Pevent=NSM930
 *   ./gradlew preview -Pevent=NSM930 -Purl=http://localhost/bingotest -Ptile=5
 *   ./gradlew preview -Pevent=NSM930 -Pimages=false
 * </pre>
 *
 * Writes {@code board.png} and {@code tile.png} to {@code build/preview}.
 */
public class PanelPreview
{
	/** Enough for the board plus a long checklist; trimmed off in the PNG. */
	private static final int HEIGHT = 1000;

	public static void main(String[] args) throws Exception
	{
		final String url = property("rs.url", "https://rs-bingo.com");
		final String event = property("rs.event", "");
		final String team = property("rs.team", "");
		final boolean showImages = !"false".equalsIgnoreCase(property("rs.images", "true"));
		final int tileIndex = Integer.parseInt(property("rs.tile", "0"));
		final File out = new File(property("rs.out", "build/preview"));

		if (event.isEmpty())
		{
			System.err.println("No event code. Pass one: ./gradlew preview -Pevent=NSM930");
			System.exit(2);
		}

		if (!out.isDirectory() && !out.mkdirs())
		{
			System.err.println("Could not create " + out);
			System.exit(1);
		}

		final OkHttpClient http = new OkHttpClient();
		final RsBingoApi api = new RsBingoApi(http, new Gson());
		final TileImageCache images = new TileImageCache(http);

		final RsBingoConfig config = new RsBingoConfig()
		{
			@Override
			public String eventCode()
			{
				return event;
			}

			@Override
			public String baseUrl()
			{
				return url;
			}

			@Override
			public int refreshSeconds()
			{
				return 0;
			}

			@Override
			public boolean showTileImages()
			{
				return showImages;
			}

			@Override
			public String accountToken()
			{
				return property("rs.token", "");
			}

			@Override
			public String selectedTeam()
			{
				return team;
			}

		};

		// Palette has to be in place before the panel is built: components read Brand
		// at construction time, which is exactly why a live switch rebuilds the panel.
		final String themeKey = property("rs.theme", "");
		if (!themeKey.isEmpty())
		{
			final java.util.concurrent.CountDownLatch got = new java.util.concurrent.CountDownLatch(1);
			api.fetchThemes(url, list ->
			{
				for (BoardModels.Theme t : list.themes)
				{
					if (themeKey.equalsIgnoreCase(t.key))
					{
						Brand.applyPalette(t.vars);
						System.out.println("  theme: " + t.label + " (" + t.vars.get("--accent") + ")");
					}
				}
				got.countDown();
			});
			got.await(10, java.util.concurrent.TimeUnit.SECONDS);
		}

		final RsBingoPanel[] holder = new RsBingoPanel[1];
		SwingUtilities.invokeAndWait(() -> holder[0] = new RsBingoPanel(
			api, images, config, () -> { }, t -> { }, t -> { }, e -> { }, onFrame -> { }));
		final RsBingoPanel panel = holder[0];

		// There is no game client here, so the logged-in character has to be supplied
		// for the submit controls to appear at all.
		// Linked-account event list, if a token was supplied.
		final String token = property("rs.token", "");
		if (!token.isEmpty())
		{
			api.fetchMyEvents(url, token,
				list -> {
					panel.setMyEvents(list);
					System.out.println("  linked as " + list.username + ", " + list.events.size() + " events");
				},
				err -> System.out.println("  event list: " + err));
			Thread.sleep(1500);
		}

		final String asPlayer = property("rs.player", "");
		if (!asPlayer.isEmpty())
		{
			panel.setLocalPlayer(asPlayer);
		}

		// The panel needs a realised hierarchy for layout and font metrics to match
		// what the client shows. The window is never made visible.
		final JFrame frame = new JFrame("rs-bingo preview");
		SwingUtilities.invokeAndWait(() ->
		{
			// Undecorated, so the content pane is exactly the frame's width and the
			// panel renders at the 225px the client actually gives it.
			frame.setUndecorated(true);
			frame.getContentPane().add(panel);
			frame.setSize(RsBingoPanel.PANEL_WIDTH, HEIGHT);
			frame.addNotify();
			frame.validate();
		});

		// The panel reports fetch failures through its status line; here they should
		// end the run rather than leave it polling for a board that isn't coming.
		final AtomicReference<String> failure = new AtomicReference<>();
		api.fetchBoard(url, event, null, panel::showEvent, failure::set);

		if (!awaitTiles(panel, failure))
		{
			System.err.println(failure.get() != null
				? ("Could not load " + event + " from " + url + ": " + failure.get())
				: ("No board arrived from " + url + " for event " + event + "."));
			System.exit(1);
		}
		// Artwork lands asynchronously and there is no completion signal; give it a
		// moment to settle before drawing.
		Thread.sleep(showImages ? 2500 : 250);

		write(frame, panel, new File(out, "board.png"));

		final List<TileCell> cells = tileCells(panel);
		if (tileIndex >= 0 && tileIndex < cells.size())
		{
			final TileCell cell = cells.get(tileIndex);
			SwingUtilities.invokeAndWait(() -> cell.dispatchEvent(new MouseEvent(
				cell, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 5, 5, 1, false)));
			// Opening a tile fetches its player breakdown; wait for that round trip
			// rather than screenshotting a half-filled card.
			Thread.sleep(2000);
			write(frame, panel, new File(out, "tile.png"));
		}

		SwingUtilities.invokeAndWait(frame::dispose);
		System.out.println("Wrote " + out.getAbsolutePath());
		System.exit(0);
	}

	/** Depth-first layout: validate() alone stops at containers already marked valid. */
	private static void layout(Component c)
	{
		if (c instanceof Container)
		{
			final Container container = (Container) c;
			container.doLayout();
			for (Component child : container.getComponents())
			{
				layout(child);
			}
		}
	}

	/** Polls for the board to render, rather than guessing at a fixed delay. */
	private static boolean awaitTiles(RsBingoPanel panel, AtomicReference<String> failure) throws Exception
	{
		for (int i = 0; i < 100; i++)
		{
			if (!tileCells(panel).isEmpty())
			{
				return true;
			}
			if (failure.get() != null)
			{
				return false;
			}
			Thread.sleep(100);
		}
		return false;
	}

	private static List<TileCell> tileCells(RsBingoPanel panel)
	{
		final List<TileCell> found = new ArrayList<>();
		collect(panel, found);
		return found;
	}

	private static void collect(Component c, List<TileCell> found)
	{
		if (c instanceof TileCell)
		{
			found.add((TileCell) c);
			return;
		}
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				collect(child, found);
			}
		}
	}

	private static void write(JFrame frame, RsBingoPanel panel, File file) throws Exception
	{
		// The panel calls revalidate() as content arrives, but that only queues a
		// layout pass — and nothing pumps that queue for a frame that is never shown,
		// so the board's cells would keep zero bounds and paint nothing. Lay it out
		// synchronously instead.
		SwingUtilities.invokeAndWait(() -> layout(frame));

		final BufferedImage img = new BufferedImage(
			Math.max(1, panel.getWidth()), Math.max(1, panel.getHeight()), BufferedImage.TYPE_INT_RGB);

		SwingUtilities.invokeAndWait(() ->
		{
			final Graphics2D g = img.createGraphics();
			try
			{
				panel.printAll(g);
			}
			finally
			{
				g.dispose();
			}
		});

		ImageIO.write(img, "png", file);
		System.out.println("  " + file.getName() + "  " + img.getWidth() + "x" + img.getHeight());
	}

	private static String property(String key, String fallback)
	{
		final String v = System.getProperty(key);
		return (v == null || v.isEmpty()) ? fallback : v;
	}
}
