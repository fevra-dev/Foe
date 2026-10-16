package com.foe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.TreeSet;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import org.junit.Test;

/**
 * What RuneLite checks about a plugin before it ever runs one line of it, run without a client. Each of these is a
 * way for the gallery to be silently absent: PluginManager.loadPlugins only logs and skips a class that fails the
 * first two, and EventBus.register throws for the third and the plugin does not start.
 */
public class PortraitGalleryPluginTest
{
	@Test
	public void itIsAPluginRuneLiteWillLoad()
	{
		// PluginManager.loadPlugins: "has plugin descriptor, but is not a plugin" unless the direct superclass is Plugin
		assertEquals(Plugin.class, PortraitGalleryPlugin.class.getSuperclass());
		PluginDescriptor d = PortraitGalleryPlugin.class.getAnnotation(PluginDescriptor.class);
		assertNotNull("without a descriptor it is skipped", d);
		assertTrue(Modifier.isPublic(PortraitGalleryPlugin.class.getModifiers()));
	}

	@Test
	public void itIsADeveloperPluginSoOnlyTheDevLaunchersDeveloperModeLoadsIt()
	{
		PluginDescriptor d = PortraitGalleryPlugin.class.getAnnotation(PluginDescriptor.class);
		assertTrue(d.developerPlugin());
	}

	@Test
	public void itListensForTheChatCommandAndEverySubscriberIsNamedOnAndTheEventName()
	{
		TreeSet<String> events = new TreeSet<>();
		for (Method m : PortraitGalleryPlugin.class.getDeclaredMethods())
		{
			if (m.getAnnotation(Subscribe.class) == null)
			{
				continue;
			}
			assertEquals(m.toString(), 1, m.getParameterCount());
			String event = m.getParameterTypes()[0].getSimpleName();
			assertEquals("on" + event, m.getName());
			events.add(event);
		}
		assertEquals(Collections.singleton("CommandExecuted"), events);
	}
}
