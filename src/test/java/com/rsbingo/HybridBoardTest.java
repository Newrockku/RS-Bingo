package com.rsbingo;

import com.google.gson.Gson;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins the contract for the Hybrid format, where one board carries both kinds of
 * tile.
 *
 * The fixture is a real plugin_board.php response for a 2x2 Hybrid event:
 *   sq1 Drops    blackout, 1 of 2 items collected
 *   sq2 Zulrah   showdown, 600 pts against a 500 threshold - complete
 *   sq3 Pet Hunt showdown built from item values alone, 60 of 100 - not complete
 *   sq4 Mining   blackout XP tile, 50k of 100k
 *
 * The thing worth guarding here is that a Hybrid board is NOT a Showdown board.
 * Its showdown tiles have no tier ladder, so isShowdown() is false and the tile/tier
 * fields are absent - but those tiles are still judged on a score, so their
 * collected/required are points and must never be labelled as items.
 */
public class HybridBoardTest
{
	private static BoardModels.Board sample()
	{
		final InputStream in = HybridBoardTest.class.getResourceAsStream("/board_hybrid_sample.json");
		assertNotNull("board_hybrid_sample.json missing from test resources", in);
		return new Gson().fromJson(
			new InputStreamReader(in, StandardCharsets.UTF_8), BoardModels.Board.class);
	}

	private static BoardModels.BoardTile at(BoardModels.Board b, int pos)
	{
		for (BoardModels.BoardTile t : b.board)
		{
			if (t.pos == pos)
			{
				return t;
			}
		}
		throw new AssertionError("no tile at position " + pos);
	}

	@Test
	public void hybridIsNotScoredAsShowdown()
	{
		final BoardModels.Board b = sample();

		assertEquals("Hybrid", b.format);
		assertTrue(b.isHybrid());
		// The whole point: a Hybrid board pays flat points per tile and a line bonus
		// once, exactly like Blackout. Treating it as Showdown would draw tier badges
		// on tiles that cannot tier.
		assertFalse("Hybrid must not report itself as a Showdown board", b.isShowdown());
		assertEquals("no tier ladder, so no tier count", 0, b.numTiers);
		assertFalse("points breakdown is the flat kind", b.points.isShowdown);
	}

	@Test
	public void everyTileSaysWhichRulesScoreIt()
	{
		final BoardModels.Board b = sample();

		assertEquals("blackout", at(b, 1).tileType);
		assertEquals("showdown", at(b, 2).tileType);
		assertEquals("showdown", at(b, 3).tileType);
		assertEquals("blackout", at(b, 4).tileType);

		assertFalse(at(b, 1).scoredByShowdownRules(b));
		assertTrue(at(b, 2).scoredByShowdownRules(b));
		assertTrue(at(b, 3).scoredByShowdownRules(b));
		assertFalse(at(b, 4).scoredByShowdownRules(b));
	}

	@Test
	public void showdownTilesOnHybridCarryNoTierFields()
	{
		final BoardModels.Board b = sample();
		final BoardModels.BoardTile zulrah = at(b, 2);

		// Sending a tier here would give the tile a ladder it can never climb.
		assertNull("Hybrid showdown tiles must not carry a tier", zulrah.tier);
		assertNull("Hybrid showdown tiles must not carry a maxTier", zulrah.maxTier);
		assertFalse("so nothing can read them as maxed", zulrah.tierMaxed());

		// The score and its threshold are still sent - that is what the tile is judged on.
		assertTrue(zulrah.hasTierProgress());
		assertEquals(600.0, zulrah.score, 0.001);
		assertEquals(Integer.valueOf(500), zulrah.tierThreshold);
	}

	@Test
	public void progressCountsAreLabelledInTheirOwnUnit()
	{
		final BoardModels.Board b = sample();

		// The defect this guards: a showdown tile's counts are points toward a
		// threshold. Labelled "items" they read as a tile needing six hundred drops.
		assertEquals("600/500 pts", at(b, 2).progressTextWithUnit(b));
		assertEquals("60/100 pts", at(b, 3).progressTextWithUnit(b));

		// Blackout tiles on the same board still count items.
		assertEquals("1/2 items", at(b, 1).progressTextWithUnit(b));
	}

	@Test
	public void completionComesFromTheServerNotFromTheChecklist()
	{
		final BoardModels.Board b = sample();

		// sq2 is complete on score alone and has no items at all; anything deriving
		// completion from a checklist would call it unfinished.
		assertTrue(at(b, 2).done);
		assertTrue(at(b, 2).items.isEmpty());
		assertEquals(100.0, at(b, 2).itemsPercent(), 0.001);

		// sq3 has one approved item but is only 60 of 100 points, so it is not done.
		assertFalse(at(b, 3).done);
		assertEquals(60.0, at(b, 3).itemsPercent(), 0.001);

		// A blackout tile is still judged on its items.
		assertFalse(at(b, 1).done);
		assertEquals(50.0, at(b, 1).itemsPercent(), 0.001);
	}

	@Test
	public void onlyCompleteTilesPayAndTheyPayTheirOwnFlatPoints()
	{
		final BoardModels.Board b = sample();

		// Zulrah is the only complete tile, and a Hybrid showdown tile pays its own
		// points value rather than a tier reward.
		assertEquals(120, at(b, 2).points);
		assertEquals(120, b.points.tilePts);
		assertEquals(120, b.points.total);
		assertEquals(1, b.points.tiles.size());
		assertEquals("Zulrah", b.points.tiles.get(0).label);
		assertEquals(120, b.points.tiles.get(0).pts);
	}

	@Test
	public void showdownTilesKeepTheirRatesAndRemainSubmittable()
	{
		final BoardModels.Board b = sample();

		// Rates drive the "Point Rates" card, and their presence is what hides the
		// plain checklist for these tiles.
		assertFalse(at(b, 2).rates.isEmpty());
		assertFalse(at(b, 3).rates.isEmpty());

		// An items-only showdown tile still offers its item for submission, and the
		// item stays submittable after approval because each copy is worth points.
		assertEquals(1, at(b, 3).submitItems.size());
		assertEquals("Pet Snakeling", at(b, 3).submitItems.get(0).label);
	}

	@Test
	public void blackoutTilesOnAHybridBoardAreUntouched()
	{
		final BoardModels.Board b = sample();
		final BoardModels.BoardTile drops = at(b, 1);
		final BoardModels.BoardTile mining = at(b, 4);

		assertEquals(2, drops.items.size());
		assertEquals(2, drops.submitItems.size());
		assertTrue("a blackout tile has no rates card", drops.rates.isEmpty());

		// XP tiles still read their goal off the snapshots, not off items.
		assertNotNull("the XP tile keeps its xp block", mining.xp);
		assertEquals("mining", mining.xp.skill);
		assertEquals(50000.0, mining.xp.collected, 0.001);
		assertEquals(50.0, mining.xp.percent(), 0.001);
	}

	@Test
	public void aShowdownBoardStillReadsAsTiered()
	{
		// Guards the other direction: the per-tile flag must not have quietly turned
		// the Showdown format into something else. Its board still tiers, and a tile
		// with no tileType at all (an older server) is still scored by showdown rules.
		final BoardModels.Board sd = new BoardModels.Board();
		sd.format = "Showdown";
		final BoardModels.BoardTile legacy = new BoardModels.BoardTile();
		legacy.tileType = null;

		assertTrue(sd.isShowdown());
		assertFalse(sd.isHybrid());
		assertTrue("format decides when the tile does not say", legacy.scoredByShowdownRules(sd));

		final BoardModels.Board blackout = new BoardModels.Board();
		blackout.format = "Blackout";
		assertFalse(legacy.scoredByShowdownRules(blackout));
	}
}
