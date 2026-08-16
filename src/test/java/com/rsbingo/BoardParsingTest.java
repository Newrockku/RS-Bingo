package com.rsbingo;

import com.google.gson.Gson;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins the contract with plugin_board.php.
 *
 * The fixture is a real response from event NSM930, trimmed to three tiles. Gson
 * maps this by field name with no annotations, so a rename on the PHP side fails
 * silently at runtime — every field would simply be null and the panel would draw
 * an empty board. This test is what makes that break loudly instead.
 */
public class BoardParsingTest
{
	private static BoardModels.Board sample()
	{
		final InputStream in = BoardParsingTest.class.getResourceAsStream("/board_sample.json");
		assertNotNull("board_sample.json missing from test resources", in);
		return new Gson().fromJson(
			new InputStreamReader(in, StandardCharsets.UTF_8), BoardModels.Board.class);
	}

	@Test
	public void eventAndTeamHeaderParse()
	{
		final BoardModels.Board b = sample();

		assertEquals("NSM930", b.eventId);
		assertEquals("Hurt Summer Bingo 2026", b.name);
		assertEquals("Showdown", b.format);
		assertTrue(b.isShowdown());
		assertEquals(4, b.cols);
		assertEquals(4, b.rows);
		assertEquals("The Dark Knights", b.team);
		assertFalse(b.hiddenTiles);
	}

	@Test
	public void teamsCarryPointsAndPlayers()
	{
		final BoardModels.Board b = sample();

		final BoardModels.TeamSummary top = b.teams.get(0);
		assertEquals("The Dark Knights", top.name);
		assertEquals(3429, top.points);
		assertTrue(top.players.contains("Hurt Dark"));

		// The dropdown renders the summary directly, so toString is load-bearing.
		assertEquals("The Dark Knights", top.toString());
	}

	@Test
	public void tilesCarryEverythingThePanelDraws()
	{
		final BoardModels.Board b = sample();
		final BoardModels.BoardTile first = b.board.get(0);

		assertEquals(1, first.pos);
		assertEquals("Stop all the yelling!", first.title);
		assertEquals("events/NSM930/images/hidyboy.png", first.img);
		assertEquals(10, first.points);
		assertTrue(first.tags.contains("Combat"));

		// The Showdown fields the tier line depends on.
		assertEquals(Integer.valueOf(3), first.tier);
		assertEquals(Integer.valueOf(5), first.maxTier);
		assertTrue(first.hasTierProgress());
		assertEquals(4_000_000.0, first.nextTierAt(), 0.001);
	}

	@Test
	public void pointRatesParseForEveryEarningOnTheTile()
	{
		final BoardModels.BoardTile prisonBreak = sample().board.get(2);

		// Three bosses, one skill, two items — what the site's "Point Rates" lists.
		assertEquals(6, prisonBreak.rates.size());
		assertEquals("Boss: Corrupted Gauntlet", prisonBreak.rates.get(0).label);
		assertEquals("12000 pts/KC", prisonBreak.rates.get(0).rate);
		assertEquals("Skill: Herblore", prisonBreak.rates.get(3).label);
		assertEquals("0.3125 pts/XP", prisonBreak.rates.get(3).rate);
		assertEquals("Dragon Warhammer", prisonBreak.rates.get(4).label);
		assertEquals("450000 pts/item", prisonBreak.rates.get(4).rate);
	}

	@Test
	public void playerProgressParsesAndAddsUp()
	{
		final BoardModels.BoardTile prisonBreak = sample().board.get(2);

		final BoardModels.PlayerProgress top = prisonBreak.players.get(0);
		assertEquals("Tw1st3d1ron", top.name);
		assertEquals(999195.0, top.points, 0.001);
		assertEquals(3, top.lines.size());

		// Each line must sum to the player's total, or the card contradicts itself.
		double sum = 0;
		for (BoardModels.ProgressLine line : top.lines)
		{
			sum += line.points;
		}
		assertEquals(top.points, sum, 0.01);

		final BoardModels.ProgressLine first = top.lines.get(0);
		assertEquals("Corrupted Gauntlet", first.label);
		assertEquals("+70 KC", first.gainText());
		assertEquals(840000.0, first.points, 0.001);
	}

	@Test
	public void playerProgressIsOnlySentForTheRequestedTile()
	{
		final BoardModels.Board b = sample();

		// The fixture asked for tile 3, so only that tile carries a breakdown —
		// this is what keeps a board refresh small.
		assertTrue(b.board.get(2).players.size() > 0);
		assertTrue(b.board.get(0).players.isEmpty());
		assertTrue(b.board.get(1).players.isEmpty());

		// Rates, being small and static, ride along on every tile.
		assertFalse(b.board.get(0).rates.isEmpty());
		assertFalse(b.board.get(1).rates.isEmpty());
	}

	@Test
	public void itemChecklistParsesWithApprovedCounts()
	{
		final BoardModels.Board b = sample();

		// pos 3 is "Prison Break": two items, one of them approved.
		final BoardModels.BoardTile prisonBreak = b.board.get(2);
		assertEquals("Prison Break", prisonBreak.title);
		assertTrue(prisonBreak.done);
		assertEquals(2, prisonBreak.items.size());
		assertEquals("Dragon Warhammer", prisonBreak.items.get(0).label);
		assertEquals(1, prisonBreak.items.get(0).need);
		assertEquals(1, prisonBreak.items.get(0).approved);
		assertEquals(1, prisonBreak.approvedCount());
		assertEquals(2, prisonBreak.neededCount());
	}
}
