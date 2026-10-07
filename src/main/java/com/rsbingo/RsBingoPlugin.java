package com.rsbingo;

import com.google.inject.Provides;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.ChatMessageType;
import net.runelite.api.MessageNode;
import net.runelite.api.events.ChatMessage;
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
	name = "RS-Bingo",
	description = "Follow an rs-bingo.com bingo event: your team's board, tile progress and standings, with optional in-client submissions",
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
	private TeamChatIcons chatIcons;

	@Inject
	private net.runelite.client.callback.ClientThread clientThread;

	@Inject
	private TeamOverheadOverlay overheadOverlay;

	@Inject
	private net.runelite.client.ui.overlay.OverlayManager overlayManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private net.runelite.client.ui.DrawManager drawManager;

	/** Message types that carry another player's name. */
	/**
	 * Message types where a participant is named inside the text rather than in the
	 * name field — drop and loot broadcasts, and the clan's own announcements.
	 */
	private static final java.util.Set<ChatMessageType> BROADCASTS =
		java.util.Collections.unmodifiableSet(java.util.EnumSet.of(
			ChatMessageType.CLAN_MESSAGE,
			ChatMessageType.CLAN_GUEST_MESSAGE,
			ChatMessageType.CLAN_GIM_MESSAGE,
			ChatMessageType.FRIENDSCHATNOTIFICATION,
			ChatMessageType.BROADCAST));

	private static final java.util.Set<ChatMessageType> PLAYER_CHAT =
		java.util.Collections.unmodifiableSet(java.util.EnumSet.of(
			ChatMessageType.PUBLICCHAT,
			ChatMessageType.MODCHAT,
			ChatMessageType.AUTOTYPER,
			ChatMessageType.MODAUTOTYPER,
			ChatMessageType.FRIENDSCHAT,
			ChatMessageType.CLAN_CHAT,
			ChatMessageType.CLAN_GUEST_CHAT,
			ChatMessageType.CLAN_GIM_CHAT,
			ChatMessageType.PRIVATECHAT,
			ChatMessageType.MODPRIVATECHAT));

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
			.tooltip("RS-Bingo")
			.icon(icon)
			// Sidebar order: NavigationButton.COMPARATOR sorts on priority ascending
			// (then tooltip), so a lower number sits higher up.
			.priority(1)
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);
		overlayManager.add(overheadOverlay);

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
		panel = new RsBingoPanel(api, images, config, RsBingoConfig.SITE_URL,
			this::loadEvent, this::rememberTeam,
			this::applyTheme, this::switchEvent, this::rosterLoaded,
			drawManager::requestNextFrameListener);
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

		api.fetchMyEvents(RsBingoConfig.SITE_URL, token,
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
		api.fetchThemes(RsBingoConfig.SITE_URL, list ->
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
		overlayManager.remove(overheadOverlay);
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

	/**
	 * Take a board's rosters, and bring the chat already on screen into line.
	 *
	 * Only when the roster actually changed, which is almost never on a refresh.
	 */
	private void rosterLoaded(BoardModels.Board board)
	{
		if (chatIcons.setRoster(board))
		{
			restampChat();
		}
	}

	/**
	 * Re-mark every message the chat still holds.
	 *
	 * A mark is written into the message when it arrives, so changing event left
	 * the previous event's teams showing on every line already on screen — the
	 * overhead marks followed the switch because they are looked up as they are
	 * drawn, and these were not. Taking ours back out and re-applying puts the
	 * backlog on the event now being viewed.
	 */
	private void restampChat()
	{
		clientThread.invoke(() ->
		{
			boolean changed = false;
			for (MessageNode node : client.getMessages())
			{
				changed |= restamp(node);
			}
			if (changed)
			{
				client.refreshChat();
			}
		});
	}

	/** One message, both halves. Returns whether anything moved. */
	private boolean restamp(MessageNode node)
	{
		boolean changed = false;

		final String name = node.getName();
		if (name != null)
		{
			final String bare = chatIcons.stripOwnIcons(name);
			final String marked = config.chatTeamIcons() && PLAYER_CHAT.contains(node.getType())
				? chatIcons.decorate(bare) : null;
			final String wanted = marked != null ? marked : bare;
			if (!wanted.equals(name))
			{
				node.setName(wanted);
				changed = true;
			}
		}

		final String value = node.getValue();
		if (value != null)
		{
			final String bare = chatIcons.stripOwnIcons(value);
			final String marked = config.dropTeamIcons() && BROADCASTS.contains(node.getType())
				? chatIcons.decorateBroadcast(bare) : null;
			final String wanted = marked != null ? marked : bare;
			if (!wanted.equals(value))
			{
				node.setValue(wanted);
				changed = true;
			}
		}

		return changed;
	}

	/**
	 * Marks event participants in chat with their team's colour.
	 *
	 * Two cases, and they differ in where the name is. A line someone types carries
	 * it in the message's name field; a drop or loot broadcast carries it inside the
	 * text instead, so the icon has to be placed in the message body.
	 *
	 * Both live in one method because RuneLite's event bus derives the event type
	 * from the method name — two subscribers for ChatMessage would both have to be
	 * called onChatMessage, and the plugin refuses to start.
	 *
	 * The client's own icons are kept either way: an ironman badge stays where it
	 * was and ours goes beside it.
	 */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		final MessageNode node = event.getMessageNode();
		if (node == null)
		{
			return;
		}

		boolean changed = false;

		if (config.chatTeamIcons() && PLAYER_CHAT.contains(event.getType()))
		{
			final String name = chatIcons.decorate(node.getName());
			if (name != null)
			{
				node.setName(name);
				changed = true;
			}
		}

		if (config.dropTeamIcons() && BROADCASTS.contains(event.getType()))
		{
			final String text = chatIcons.decorateBroadcast(node.getValue());
			if (text != null)
			{
				node.setValue(text);
				changed = true;
			}
		}

		if (changed)
		{
			// The line has already been laid out by the time this runs, so it has to
			// be redrawn for the change to appear.
			client.refreshChat();
		}
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

		// Pasting a token has to take effect immediately: the event list is the whole
		// point of linking, and making someone restart the client to see it reads as
		// the link having silently failed.
		if ("accountToken".equals(event.getKey()))
		{
			loadMyEvents();
			return;
		}

		// Turning a chat setting off has to take its marks back out of the lines
		// already on screen, not merely stop adding new ones.
		if ("chatTeamIcons".equals(event.getKey()) || "dropTeamIcons".equals(event.getKey()))
		{
			restampChat();
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

			// With an account linked the code is optional: the event list picks one as
			// soon as it lands, so asking for a code here would be telling the player
			// to do something the plugin is about to do for them.
			final String token = config.accountToken();
			final boolean linked = token != null && !token.trim().isEmpty();
			panel.setStatus(linked
				? "Loading your events…"
				: "Set an event code in the plugin settings, or link your account.");
			return;
		}

		panel.setStatus("Loading…");
		api.fetchBoard(RsBingoConfig.SITE_URL, code.toUpperCase(), null, panel::showEvent, panel::setStatus);
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
