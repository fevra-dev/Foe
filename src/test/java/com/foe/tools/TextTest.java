package com.foe.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Test;

/**
 * ADR-0006: untrusted text reaches a sink only after passing here. What is hostile is not guesswork: a terminal obeys
 * ESC sequences and the 8-bit CSI (U+009B), a log reader obeys newlines, and a human reads text in the order a
 * right-to-left override says. All of it is made visible as an escape instead.
 */
public class TextTest
{
	/** True when printing this would do something other than show text. */
	static boolean isHostileToPrint(String s)
	{
		return s.codePoints().anyMatch(cp ->
		{
			if (cp == ' ')
			{
				return false;
			}
			int type = Character.getType(cp);
			return Character.isISOControl(cp) || type == Character.FORMAT || type == Character.LINE_SEPARATOR
				|| type == Character.PARAGRAPH_SEPARATOR || type == Character.SPACE_SEPARATOR
				|| type == Character.SURROGATE || type == Character.PRIVATE_USE || type == Character.UNASSIGNED;
		});
	}

	@Test
	public void ordinaryWikiTextIsUntouched()
	{
		for (String s : new String[] {"Fire giant", "K'ril Tsutsaroth (Deadman)", "Elidinis' Warden", "Tz-Kih",
			"Level 86", "Armoured zombie (Defender of Varrock)", "José", "Ωmega", "日本語",
			"smile 😀"})
		{
			assertEquals(s, Text.safe(s));
		}
	}

	@Test
	public void controlCharactersBecomeVisibleEscapes()
	{
		assertEquals("a\\u000ab", Text.safe("a\nb"));
		assertEquals("a\\u000db", Text.safe("a\rb"));
		assertEquals("\\u001b[2Jhi", Text.safe("\u001b[2Jhi"));
		assertEquals("x\\u007fy", Text.safe("x\u007fy"));
		assertEquals("x\\u0000y", Text.safe("x\u0000y"));
		assertEquals("the 8-bit CSI, which a terminal in UTF-8 mode still obeys", "x\\u009by", Text.safe("x\u009by"));
		assertEquals("a\\u0009b", Text.safe("a\tb"));
	}

	@Test
	public void invisibleAndReorderingCharactersBecomeVisibleEscapes()
	{
		assertEquals("a\\u202eb", Text.safe("a‮b"));
		assertEquals("a\\u2066b", Text.safe("a⁦b"));
		assertEquals("a\\u200bb", Text.safe("a​b"));
		assertEquals("a\\u2028b", Text.safe("a b"));
		assertEquals("a\\u2029b", Text.safe("a b"));
		assertEquals("a\\ufeffb", Text.safe("a﻿b"));
		assertEquals("a non-breaking space is not a space", "Fire\\u00a0", Text.safe("Fire "));
		assertEquals("a tag character from the supplementary planes", "a\\u{e0041}b", Text.safe("a󠁁b"));
	}

	@Test
	public void aLoneSurrogateIsEscapedToo()
	{
		assertEquals("a\\ud800b", Text.safe("a\uD800b"));
	}

	@Test
	public void aBackslashIsEscapedSoAnEscapeCannotBeForgedFromPlainText()
	{
		assertEquals("\\\\u001b", Text.safe("\\u001b"));
		assertEquals("a\\\\b", Text.safe("a\\b"));
	}

	@Test
	public void longTextIsCutAndSaysSo()
	{
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 1000; i++)
		{
			sb.append('x');
		}
		String out = Text.safe(sb.toString());
		assertTrue(out, out.length() < 200);
		assertTrue(out, out.startsWith("xxxx"));
		assertTrue(out, out.contains("880 more"));
		assertEquals("exactly at the limit is not cut", 120, Text.safe(sb.substring(0, 120)).length());
	}

	@Test
	public void nullIsShownAsNull()
	{
		assertEquals("null", Text.safe(null));
	}

	@Test
	public void noSingleCodePointGetsThroughHostile()
	{
		// every code point there is, one at a time: the sweep is the claim, not a sample
		int hostile = 0;
		for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++)
		{
			if (isHostileToPrint(Text.safe(new String(Character.toChars(cp)))))
			{
				hostile++;
			}
		}
		assertEquals(0, hostile);
		// and a lone surrogate, which Character.toChars cannot make into a pair
		assertFalse(isHostileToPrint(Text.safe("\uD800")));
		assertFalse(isHostileToPrint(Text.safe("\uDC00")));
	}

	// ---- the raw file ----

	@Test
	public void jsonKeepsItsMeaningAndLosesItsHostileCharacters()
	{
		String original = "a\u009b‮b󠁁c d😀e";
		JsonObject o = new JsonObject();
		o.addProperty("page_name", original);
		String escaped = Text.escapeJson(o.toString());
		assertFalse(escaped, isHostileToPrint(escaped));
		assertTrue(escaped, escaped.contains("\\u009b"));
		assertEquals("lossless: it parses back to the same string", original,
			new Gson().fromJson(escaped, JsonObject.class).get("page_name").getAsString());
	}

	@Test
	public void plainJsonIsUnchanged()
	{
		String json = "{\"page_name\":\"Fire giant\",\"id\":[\"2075\"],\"elemental_weakness_percent\":100}";
		assertEquals(json, Text.escapeJson(json));
	}
}
