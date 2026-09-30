package com.rsbingo;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Every theme's region colours must be the colours the website actually paints.
 *
 * The fixture is measured, not calculated: region_colors_web.tsv holds what a browser
 * resolved for {@code hsl(calc(var(--accent-h) + index * 137.508) 70% 60%)} under each
 * of the site's themes, read straight off the page. Recomputing the expectation in
 * Java would only prove this file agrees with itself — the point is that the panel
 * agrees with the board a player has open beside it.
 *
 * Two things that a three-theme spot check missed and this caught:
 *   - --accent-h is published rounded, so the hue has to be rounded before rotating.
 *   - Two themes ship a greyscale accent (#ffffff, #cccccc), where the site falls back
 *     to the default gold while Color.RGBtoHSB reports 0, which is red.
 */
public class RegionColourParityTest
{
	private static class Row
	{
		String theme;
		String accent;
		Color[] regions = new Color[3];
	}

	private static List<Row> fixture() throws Exception
	{
		final InputStream in = RegionColourParityTest.class
			.getResourceAsStream("/region_colors_web.tsv");
		assertNotNull("region_colors_web.tsv missing from test resources", in);

		final List<Row> rows = new ArrayList<>();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
		{
			String line;
			while ((line = r.readLine()) != null)
			{
				if (line.trim().isEmpty())
				{
					continue;
				}
				final String[] p = line.split("\t");
				final Row row = new Row();
				row.theme = p[0];
				row.accent = p[1];
				for (int i = 0; i < 3; i++)
				{
					final String[] c = p[2 + i].split(",");
					row.regions[i] = new Color(
						Integer.parseInt(c[0].trim()),
						Integer.parseInt(c[1].trim()),
						Integer.parseInt(c[2].trim()));
				}
				rows.add(row);
			}
		}
		return rows;
	}

	@Test
	public void everyThemesRegionColoursMatchTheWebsite() throws Exception
	{
		final List<Row> rows = fixture();
		assertTrue("expected the site's full theme list", rows.size() >= 30);

		final List<String> wrong = new ArrayList<>();
		for (Row row : rows)
		{
			Brand.applyPalette(Collections.singletonMap("--accent", row.accent));
			for (int i = 0; i < 3; i++)
			{
				final Color got = Brand.regionColor(i);
				if (!got.equals(row.regions[i]))
				{
					wrong.add(String.format("%s (accent %s) region %d: website %s, panel %s",
						row.theme, row.accent, i, rgb(row.regions[i]), rgb(got)));
				}
			}
		}

		// Restore the default so test ordering cannot leak a palette into another test.
		Brand.applyPalette(Collections.singletonMap("--accent", "#d8a830"));

		assertEquals("region colours differ from the website:\n  "
			+ String.join("\n  ", wrong) + "\n", 0, wrong.size());
	}

	@Test
	public void aGreyscaleAccentFallsBackTheWayTheSiteDoes()
	{
		// The case that was wrong: an accent with no hue to rotate away from. The site
		// substitutes hue 42; Color.RGBtoHSB reports 0, which is red.
		//
		// Note 42 is the site's stated fallback, NOT Obsidian's own hue, which rounds
		// to 43 - so these land a shade apart and must not be asserted equal. Both
		// values below are what a browser resolved for those accents.
		Brand.applyPalette(Collections.singletonMap("--accent", "#ffffff"));
		assertEquals(new Color(224, 182, 82), Brand.regionColor(0));

		Brand.applyPalette(Collections.singletonMap("--accent", "#cccccc"));
		assertEquals(new Color(224, 182, 82), Brand.regionColor(0));

		Brand.applyPalette(Collections.singletonMap("--accent", "#d8a830"));
		assertEquals(new Color(224, 184, 82), Brand.regionColor(0));
	}

	private static String rgb(Color c)
	{
		return c.getRed() + "," + c.getGreen() + "," + c.getBlue();
	}
}
