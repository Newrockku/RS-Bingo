package com.rsbingo;

import com.google.inject.Provides;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import java.awt.image.BufferedImage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import javax.swing.SwingUtilities;

@Slf4j
@PluginDescriptor(
	name = "RS Bingo",
	description = "View a rs-bingo.com event board in the side panel",
	tags = {"bingo", "clan", "event", "board"}
)
public class RsBingoPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private RsBingoConfig config;

	@Inject
	private RsBingoApi api;

	@Inject
	private TileImageCache images;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private net.runelite.client.ui.DrawManager drawManager;

	private RsBingoPanel panel;
	private NavigationButton navButton;
	/** Kept so a rebuilt panel can be handed these again without re-fetching. */
	private BoardModels.ThemeList themes;
	private BoardModels.EventList myEvents;
	/** The character already reported to the panel, so ticks don't re-report it. */
	private String knownPlayer;
	private ScheduledFuture<?> refreshTask;

	@Provides
	RsBingoConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(RsBingoConfig.class);
	}

	@Override
	protected void startUp()
	{
		buildPanel();

		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/panel_icon.png");
		navButton = NavigationButton.builder()
			.tooltip("RS Bingo")
			.icon(icon)
			// Sidebar order: NavigationButton.COMPARATOR sorts on priority ascending
			// (then tooltip), so a lower number sits higher up.
			.priority(1)
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);

		loadThemes();
		loadMyEvents();

		if (!config.eventCode().trim().isEmpty())
		{
			loadEvent();
		}

		scheduleRefresh();
	}

	private void buildPanel()
	{
		panel = new RsBingoPanel(api, images, config, this::loadEvent, this::rememberTeam,
			this::applyTheme, this::switchEvent, drawManager::requestNextFrameListener);
	}

	/**
	 * List the linked account's events in the panel. Silent when no token is set:
	 * linking is optional, and an event code alone still works.
	 */
	private void loadMyEvents()
	{
		final String token = config.accountToken();
		if (token == null || token.trim().isEmpty())
		{
			return;
		}

		api.fetchMyEvents(config.baseUrl(), token,
			list ->
			{
				myEvents = list;
				if (panel != null)
				{
					panel.setMyEvents(list);
				}
			},
			error -> log.debug("rs-bingo event list: {}", error));
	}

	/** Load an event picked from the panel's dropdown, remembering it as the current one. */
	private void switchEvent(String eventId)
	{
		if (eventId == null || eventId.trim().isEmpty())
		{
			return;
		}
		// Writing the config is what makes the choice stick; the change handler then
		// loads it, so there is one path into "show me this event".
		configManager.setConfiguration(RsBingoConfig.GROUP, "eventCode", eventId.trim().toUpperCase());
	}

	/** Offer the site's themes in the panel, and re-apply the one already chosen. */
	private void loadThemes()
	{
		api.fetchThemes(config.baseUrl(), list ->
		{
			themes = list;

			final String saved = config.theme();
			if (saved != null && !saved.isEmpty())
			{
				for (BoardModels.Theme theme : list.themes)
				{
					if (saved.equals(theme.key))
					{
						SwingUtilities.invokeLater(() -> repaintWithTheme(theme));
						break;
					}
				}
			}

			if (panel != null)
			{
				panel.setThemes(list);
			}
		});
	}

	/**
	 * Switch the panel to a theme and remember it.
	 *
	 * Components read the palette when they are created, so the panel rebuilds its
	 * contents rather than trying to recolour them in place: miss one and it keeps
	 * the old theme's colours, which looks like a bug rather than a stale pixel.
	 */
	private void applyTheme(BoardModels.Theme theme)
	{
		if (theme == null || theme.key == null)
		{
			return;
		}
		configManager.setConfiguration(RsBingoConfig.GROUP, "theme", theme.key);
		repaintWithTheme(theme);
	}

	private void repaintWithTheme(BoardModels.Theme theme)
	{
		Brand.applyPalette(theme.vars);

		// Rebuild the panel's contents, not the panel. Replacing it meant removing and
		// re-adding the navigation button, which collapsed the sidebar every time —
		// and re-opening it programmatically did not reliably put it back.
		if (panel != null)
		{
			panel.reskin();
		}
	}

	@Override
	protected void shutDown()
	{
		cancelRefresh();
		clientToolbar.removeNavigation(navButton);
		panel = null;
		navButton = null;
	}

	/**
	 * Forget the character on the way out, so switching accounts is picked up rather
	 * than leaving the previous one's name in place.
	 */
	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() != GameState.LOGGED_IN)
		{
			knownPlayer = null;
		}
	}

	/**
	 * Pick up the logged-in character, which decides whether the submit controls
	 * appear at all.
	 *
	 * Read on a tick rather than on GameState.LOGGED_IN: at the moment that event
	 * fires getLocalPlayer() is usually still null, so reading it there silently
	 * missed the name and left the panel thinking nobody was logged in. Guarded on
	 * knownPlayer so this costs one reference comparison per tick once settled.
	 */
	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (panel == null)
		{
			return;
		}

		final Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}

		final String name = local.getName();
		if (name == null || name.equals(knownPlayer))
		{
			return;
		}

		knownPlayer = name;
		panel.setLocalPlayer(name);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!RsBingoConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}

		if ("refreshSeconds".equals(event.getKey()))
		{
			scheduleRefresh();
			return;
		}

		if ("eventCode".equals(event.getKey()))
		{
			loadEvent();
			return;
		}

		if ("baseUrl".equals(event.getKey()))
		{
			loadThemes();
			loadMyEvents();
			loadEvent();
			return;
		}

		// Pasting a token has to take effect immediately: the event list is the whole
		// point of linking, and making someone restart the client to see it reads as
		// the link having silently failed.
		if ("accountToken".equals(event.getKey()))
		{
			loadMyEvents();
			return;
		}

		// Toggling artwork only changes how the board draws, so redraw it rather than
		// starting the whole event over.
		if ("showTileImages".equals(event.getKey()) && panel != null)
		{
			panel.refreshCurrentTeam();
		}
	}

	/**
	 * Fetch the event summary and team list. The panel picks a team and asks for its
	 * board, so this is the single entry point for "start over".
	 *
	 * The code comes from the config, which is now the only place it lives — the
	 * panel used to carry its own copy and write it back, which fought with edits
	 * made in the settings pane.
	 */
	private void loadEvent()
	{
		if (panel == null)
		{
			return;
		}

		final String code = config.eventCode() == null ? "" : config.eventCode().trim();
		if (code.isEmpty())
		{
			panel.clearBoard();
			panel.setStatus("Set an event code in the plugin settings.");
			return;
		}

		panel.setStatus("Loading…");
		api.fetchBoard(config.baseUrl(), code.toUpperCase(), null, panel::showEvent, panel::setStatus);
	}

	/**
	 * Persist the panel's team choice. A code pointing at a different event simply
	 * won't match this name, and the panel falls back to the leading team.
	 */
	private void rememberTeam(String team)
	{
		if (team != null && !team.equals(config.selectedTeam()))
		{
			configManager.setConfiguration(RsBingoConfig.GROUP, "selectedTeam", team);
		}
	}

	private void scheduleRefresh()
	{
		cancelRefresh();

		final int seconds = config.refreshSeconds();
		if (seconds <= 0)
		{
			return;
		}

		// Floor at 15s so a mistyped config can't hammer the site.
		final long period = Math.max(15, seconds);
		refreshTask = executor.scheduleWithFixedDelay(
			() ->
			{
				if (panel != null)
				{
					panel.refreshCurrentTeam();
				}
			},
			period, period, TimeUnit.SECONDS);
	}

	private void cancelRefresh()
	{
		if (refreshTask != null)
		{
			refreshTask.cancel(false);
			refreshTask = null;
		}
	}
}
