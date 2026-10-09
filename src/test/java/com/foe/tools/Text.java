package com.foe.tools;

/**
 * ADR-0006: wiki text is untrusted bytes, and the generator writes it to a terminal, to a report and to a file. This is
 * the one place that makes it safe to show. Every sink of wiki-derived text goes through here, so there is nothing to
 * keep in step at each use site.
 *
 * What is hostile is not guesswork: a terminal obeys ESC sequences and the 8-bit CSI (U+009B), a log or report reader
 * obeys newlines, and a human reads text in the order a right-to-left override says. All of it is shown as a visible
 * escape instead.
 */
final class Text
{
	/** Longest wiki string shown whole. The longest real page name is under 60. */
	static final int MAX_SHOWN = 120;

	private Text()
	{
	}

	/** True when printing the code point would do something other than show a character. */
	private static boolean hostile(int cp)
	{
		if (cp == ' ')
		{
			return false;
		}
		switch (Character.getType(cp))
		{
			case Character.CONTROL:
			case Character.FORMAT:
			case Character.LINE_SEPARATOR:
			case Character.PARAGRAPH_SEPARATOR:
			case Character.SPACE_SEPARATOR:
			case Character.SURROGATE:
			case Character.PRIVATE_USE:
			case Character.UNASSIGNED:
				return true;
			default:
				return false;
		}
	}

	private static String escape(int cp)
	{
		return cp > 0xFFFF ? String.format("\\u{%x}", cp) : String.format("\\u%04x", cp);
	}

	/**
	 * Wiki text made safe to print: unprintable code points become visible escapes, a backslash is doubled so an escape
	 * cannot be forged from plain text, and text longer than {@link #MAX_SHOWN} code points is cut and says so.
	 */
	static String safe(String s)
	{
		return safe(s, MAX_SHOWN);
	}

	/** {@link #safe(String)} with a longer limit, for a reply body whose start is the diagnosis. */
	static String safe(String s, int limit)
	{
		if (s == null)
		{
			return "null";
		}
		StringBuilder sb = new StringBuilder();
		int shown = 0;
		int total = s.codePointCount(0, s.length());
		for (int i = 0; i < s.length(); )
		{
			int cp = s.codePointAt(i);
			i += Character.charCount(cp);
			if (shown == limit)
			{
				sb.append("...(").append(total - limit).append(" more)");
				break;
			}
			shown++;
			if (cp == '\\')
			{
				sb.append("\\\\");
			}
			else if (hostile(cp))
			{
				sb.append(escape(cp));
			}
			else
			{
				sb.appendCodePoint(cp);
			}
		}
		return sb.toString();
	}

	/**
	 * One line of JSON with every unprintable code point inside its strings written as a \\u escape. JSON reads it back
	 * as the same string, so the file stays a faithful copy of what the wiki said, but it can be shown with cat.
	 */
	static String escapeJson(String json)
	{
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < json.length(); )
		{
			int cp = json.codePointAt(i);
			i += Character.charCount(cp);
			if (hostile(cp))
			{
				for (char c : Character.toChars(cp))
				{
					sb.append(String.format("\\u%04x", (int) c));
				}
			}
			else
			{
				sb.appendCodePoint(cp);
			}
		}
		return sb.toString();
	}
}
