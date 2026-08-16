package com.rsbingo;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(RsBingoConfig.GROUP)
public interface RsBingoConfig extends Config
{
	String GROUP = "rsbingo";

	@ConfigItem(
		keyName = "eventCode",
		name = "Event code",
		description = "The event ID from the organiser, e.g. NSM930. Leave blank to hide the board.",
		position = 1
	)
	default String eventCode()
	{
		return "";
	}

	@ConfigItem(
		keyName = "baseUrl",
		name = "Site URL",
		description = "Where the event lives. Only change this if you self-host.",
		position = 2
	)
	default String baseUrl()
	{
		return "https://rs-bingo.com";
	}

	@ConfigItem(
		keyName = "refreshSeconds",
		name = "Refresh (seconds)",
		description = "How often to re-fetch the board. 0 disables automatic refresh.",
		position = 3
	)
	default int refreshSeconds()
	{
		return 60;
	}

	/**
	 * Links this client to a site account so the panel can list the events you are
	 * in. Minted at /plugin_link.php while signed in with Discord.
	 *
	 * Read-only and narrowly scoped by design: it lists your events and nothing
	 * else. Optional — the plugin works without it, given an event code.
	 */
	@ConfigItem(
		keyName = "accountToken",
		name = "Account token",
		description = "Paste from the site's Link RuneLite plugin page to list your events. "
			+ "Optional; leave blank and enter event codes by hand.",
		secret = true,
		position = 1
	)
	default String accountToken()
	{
		return "";
	}

	@ConfigItem(
		keyName = "showTileImages",
		name = "Show tile images",
		description = "Draw each tile's artwork. Turn off to save bandwidth - a board's "
			+ "art can run to several MB the first time it loads.",
		position = 4
	)
	default boolean showTileImages()
	{
		return true;
	}

	/**
	 * Written by the panel's theme dropdown. The list itself comes from the site, so
	 * this is a key rather than an enum — adding a theme to js/themes.js is enough to
	 * make it selectable here, with no plugin change.
	 */
	@ConfigItem(
		keyName = "theme",
		name = "Theme",
		description = "Remembers the colour theme picked in the panel.",
		hidden = true,
		position = 6
	)
	default String theme()
	{
		return "";
	}

	/**
	 * Written by the panel's dropdown, not typed by hand — it exists so reopening
	 * the client returns to the team you were watching rather than whichever one is
	 * top of the table that day.
	 */
	@ConfigItem(
		keyName = "selectedTeam",
		name = "Selected team",
		description = "Remembers the team picked in the panel.",
		hidden = true,
		position = 5
	)
	default String selectedTeam()
	{
		return "";
	}
}
