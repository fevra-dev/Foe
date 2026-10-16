package com.foe;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class FoePluginTest
{
	@SuppressWarnings("unchecked") // loadBuiltin's generic varargs
	public static void main(String[] args) throws Exception
	{
		// One call, both plugins: loadBuiltin stores its argument (client 1.13.1 ExternalPluginManager.java:469,
		// builtinExternals = plugins), so a second call would replace Foe instead of adding to it. The gallery is a
		// developer plugin (::foegallery), loaded only because this launcher passes --developer-mode.
		ExternalPluginManager.loadBuiltin(FoePlugin.class, PortraitGalleryPlugin.class);
		RuneLite.main(args);
	}
}
