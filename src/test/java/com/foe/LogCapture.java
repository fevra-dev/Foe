package com.foe;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * Collects what one class logs, so a test can assert the level and the text of a message. The plugin's log lines are
 * part of what spec addenda 8 (F6) and 11 promise ("logs ... at info level", "a warning if ... failed"), and a
 * promise about a log line can only be tested by reading the log. Logback is what RuneLite's client ships, which is
 * what {@code @Slf4j} binds to on the test classpath.
 */
final class LogCapture implements AutoCloseable
{
	private final Logger logger;
	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
	private final Level saved;

	LogCapture(Class<?> source)
	{
		org.slf4j.Logger found = LoggerFactory.getLogger(source);
		if (!(found instanceof Logger))
		{
			throw new IllegalStateException("slf4j is not bound to logback here: " + found.getClass());
		}
		logger = (Logger) found;
		saved = logger.getLevel();
		logger.setLevel(Level.INFO); // what the plugin logs is at INFO and above; an unset level would inherit root's
		appender.start();
		logger.addAppender(appender);
	}

	/** The formatted messages logged at exactly this level, in order. */
	List<String> at(Level level)
	{
		List<String> out = new ArrayList<>();
		for (ILoggingEvent e : appender.list)
		{
			if (e.getLevel() == level)
			{
				out.add(e.getFormattedMessage());
			}
		}
		return out;
	}

	/** Every formatted message, in order, whatever the level. */
	List<String> all()
	{
		List<String> out = new ArrayList<>();
		for (ILoggingEvent e : appender.list)
		{
			out.add(e.getFormattedMessage());
		}
		return out;
	}

	void clear()
	{
		appender.list.clear();
	}

	@Override
	public void close()
	{
		logger.detachAppender(appender);
		appender.stop();
		logger.setLevel(saved);
	}
}
