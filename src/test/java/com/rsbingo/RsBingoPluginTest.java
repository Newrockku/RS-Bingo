package com.rsbingo;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Launches a real client with this plugin side-loaded. `./gradlew run` calls this.
 */
public class RsBingoPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(RsBingoPlugin.class);
		RuneLite.main(args);
	}
}
