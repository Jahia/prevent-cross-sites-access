package org.jahia.community.modules;

import org.slf4j.Logger;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Rate-limited logging of a failing lookup, shared by both layers: WARN on the transition to failing
 * and again at most every ten minutes while it lasts, INFO on recovery, DEBUG in between.
 */
final class FailureTracker {

    /** Reason logged when a lookup throws. */
    static final String LOOKUP_FAILED = "failed";

    private static final long REWARN_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(10);

    private final String what;
    private final Logger logger;
    private final LongSupplier clockMillis;
    // volatile: succeeded() runs on every checked request and the healthy case must take no lock.
    private volatile boolean failing;
    private long lastWarnMillis;

    FailureTracker(String what, Logger logger, LongSupplier clockMillis) {
        this.what = what;
        this.logger = logger;
        this.clockMillis = clockMillis;
    }

    synchronized void failed(String reason, Exception cause) {
        final String format = "Reading {} {}, letting requests through unchecked";
        final long now = clockMillis.getAsLong();
        if (!failing || now - lastWarnMillis >= REWARN_INTERVAL_MILLIS) {
            failing = true;
            lastWarnMillis = now;
            if (cause != null) {
                logger.warn(format, what, reason, cause);
            } else {
                logger.warn(format, what, reason);
            }
        } else if (cause != null) {
            logger.debug(format, what, reason, cause);
        } else {
            logger.debug(format, what, reason);
        }
    }

    void succeeded() {
        if (!failing) {
            return;
        }
        synchronized (this) {
            if (failing) {
                failing = false;
                logger.info("Reading {} recovered, cross-site checks are effective again", what);
            }
        }
    }

    boolean isFailing() {
        return failing;
    }
}
