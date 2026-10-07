package com.rsbingo;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * A team colour over the head of every event participant on screen.
 *
 * The chat marks say who is on which team when they speak; this says it while
 * they are standing in front of you, which is the other half of the same
 * question during an event.
 */
class TeamOverheadOverlay extends Overlay
{
	/**
	 * Clearance above the model, in the same units as {@link Player#getLogicalHeight()}.
	 * Enough to clear a player's name and the client's own overhead icons rather
	 * than landing on top of them.
	 */
	private static final int CLEARANCE = 40;

	/** Space between the swatch and the first letter of the name. */
	private static final int GAP = 2;

	/**
	 * Nudges the swatch off the baseline so it sits centred on the letters rather
	 * than hanging below them — text baselines sit above the glyph bottoms.
	 */
	private static final int BASELINE_LIFT = 2;

	private final Client client;
	private final TeamChatIcons teams;
	private final RsBingoConfig config;

	@Inject
	TeamOverheadOverlay(Client client, TeamChatIcons teams, RsBingoConfig config)
	{
		this.client = client;
		this.teams = teams;
		this.config = config;

		setPosition(OverlayPosition.DYNAMIC);
		// With the scene rather than over the interface, so the marks are hidden by
		// an open bank or inventory tab exactly as the players under them are.
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.overheadTeamIcons())
		{
			return null;
		}

		// Through the world view rather than Client.getPlayers(), which is deprecated
		// and which the hub's packager reports as such.
		for (Player player : client.getTopLevelWorldView().players())
		{
			if (player == null)
			{
				continue;
			}

			// Never your own character: you know which team you are on, and that is
			// the most crowded space on screen already.
			if (player == client.getLocalPlayer())
			{
				continue;
			}

			final BufferedImage swatch = teams.overheadSwatch(player.getName());
			if (swatch == null)
			{
				continue;
			}

			final String name = player.getName();
			if (name == null)
			{
				continue;
			}

			// Positioned against where the name is drawn, not against the model.
			// getCanvasImageLocation centres an image on the height it is given, while
			// a name is placed by its baseline, so asking both for the same height put
			// the swatch above the name rather than beside it.
			//
			// Null off-screen, and for a player the client has not placed yet.
			final Point at = player.getCanvasTextLocation(graphics, name,
				player.getLogicalHeight() + CLEARANCE);
			if (at == null)
			{
				continue;
			}

			// Left of the first letter, sitting on the same baseline.
			graphics.drawImage(swatch,
				at.getX() - swatch.getWidth() - GAP,
				at.getY() - swatch.getHeight() + BASELINE_LIFT,
				null);
		}

		return null;
	}
}
