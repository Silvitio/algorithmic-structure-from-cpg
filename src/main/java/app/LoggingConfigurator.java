package app;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class LoggingConfigurator {
    private static boolean configured;

    private LoggingConfigurator() {
    }

    public static synchronized void configure() {
        if (configured) {
            return;
        }

        setLoggerLevel("org.neo4j.driver", Level.WARNING);
        setLoggerLevel("org.neo4j.driver.internal", Level.WARNING);

        Logger rootLogger = Logger.getLogger("");
        for (Handler handler : rootLogger.getHandlers()) {
            if (handler.getLevel().intValue() < Level.WARNING.intValue()) {
                handler.setLevel(Level.WARNING);
            }
        }

        configured = true;
    }

    private static void setLoggerLevel(String loggerName, Level level) {
        Logger logger = Logger.getLogger(loggerName);
        logger.setLevel(level);
        logger.setUseParentHandlers(true);
    }
}
