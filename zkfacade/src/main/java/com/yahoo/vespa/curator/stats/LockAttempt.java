// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.curator.stats;

import com.yahoo.jdisc.Metric;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Information about a lock.
 *
 * <p>Should be mutated by a single thread, except {@link #fillStackTrace()} which can be
 * invoked by any threads.  Other threads may see an inconsistent state of this instance.</p>
 *
 * @author hakon
 */
public class LockAttempt {

    private final ThreadLockStats threadLockStats;
    private final String lockPath;
    private final Instant callAcquireInstant;
    private final Duration timeout;
    private final Optional<Metric> metric;
    private final Optional<Metric.Context> metricContext;
    private final List<LockAttempt> nestedLockAttempts = new ArrayList<>();

    private volatile Optional<Instant> lockAcquiredInstant = Optional.empty();
    private volatile Optional<Instant> terminalStateInstant = Optional.empty();
    private volatile Optional<String> stackTrace = Optional.empty();

    public static LockAttempt invokingAcquire(ThreadLockStats threadLockStats, String lockPath, Duration timeout,
                                              Optional<Metric> metric, Optional<Metric.Context> metricContext) {
        return new LockAttempt(threadLockStats, lockPath, timeout, Instant.now(), metric, metricContext);
    }

    public enum LockState {
        ACQUIRING(false), ACQUIRE_FAILED(true), TIMED_OUT(true), ACQUIRED(false), RELEASED(true),
        RELEASED_WITH_ERROR(true);

        private final boolean terminal;

        LockState(boolean terminal) { this.terminal = terminal; }

        public boolean isTerminal() { return terminal; }
    }

    private volatile LockState lockState = LockState.ACQUIRING;

    private LockAttempt(ThreadLockStats threadLockStats, String lockPath, Duration timeout, Instant callAcquireInstant,
                        Optional<Metric> metric, Optional<Metric.Context> metricContext) {
        this.threadLockStats = threadLockStats;
        this.lockPath = lockPath;
        this.callAcquireInstant = callAcquireInstant;
        this.timeout = timeout;
        this.metric = metric;
        this.metricContext = metricContext;

        addToMetric("lockAttempt.acquiring", 1);
    }

    public String getThreadName() { return threadLockStats.getThreadName(); }
    public String getLockPath() { return lockPath; }
    public Instant getTimeAcquiredWasInvoked() { return callAcquireInstant; }
    public Duration getAcquireTimeout() { return timeout; }
    public LockState getLockState() { return lockState; }
    public Optional<Instant> getTimeLockWasAcquired() { return lockAcquiredInstant; }
    public Instant getTimeAcquireEndedOrNow() {
        return lockAcquiredInstant.orElseGet(() -> getTimeTerminalStateWasReached().orElseGet(Instant::now));
    }
    public Optional<Instant> getTimeTerminalStateWasReached() { return terminalStateInstant; }
    public Optional<String> getStackTrace() { return stackTrace; }
    public List<LockAttempt> getNestedLockAttempts() { return List.copyOf(nestedLockAttempts); }
    public Optional<Metric> metric() { return metric; }
    public Optional<Metric.Context> metricContext() { return metricContext; }

    public Duration getDurationOfAcquire() { return Duration.between(callAcquireInstant, getTimeAcquireEndedOrNow()); }

    public Duration getDurationWithLock() {
        return lockAcquiredInstant
                .map(start -> Duration.between(start, terminalStateInstant.orElseGet(Instant::now)))
                .orElse(Duration.ZERO);
    }

    public Duration getDuration() { return Duration.between(callAcquireInstant, terminalStateInstant.orElseGet(Instant::now)); }

    /** Get time from just before trying to acquire lock to the time the terminal state was reached, or ZERO. */
    public Duration getStableTotalDuration() {
        return terminalStateInstant.map(instant -> Duration.between(callAcquireInstant, instant)).orElse(Duration.ZERO);
    }

    /** Fill in the stack trace starting at the caller's stack frame. */
    public void fillStackTrace() {
        // This method is public. If invoked concurrently, the this.stackTrace may be updated twice,
        // which is fine.

        this.stackTrace = Optional.of(threadLockStats.getStackTrace());
    }

    void addNestedLockAttempt(LockAttempt nestedLockAttempt) {
        nestedLockAttempts.add(nestedLockAttempt);
    }

    void acquireFailed() {
        setTerminalState(LockState.ACQUIRE_FAILED);
        addToMetric("lockAttempt.acquiring", -1);
        addToMetric("lockAttempt.acquireFailed", 1);
        setMetricTo("lockAttempt.acquiringLatency", getDurationOfAcquire().toMillis() / 1000.);
    }

    void timedOut() {
        setTerminalState(LockState.TIMED_OUT);
        addToMetric("lockAttempt.acquiring", -1);
        addToMetric("lockAttempt.acquireTimedOut", 1);
        setMetricTo("lockAttempt.acquiringLatency", getDurationOfAcquire().toMillis() / 1000.);
    }

    void lockAcquired() {
        lockState = LockState.ACQUIRED;
        lockAcquiredInstant = Optional.of(Instant.now());
        addToMetric("lockAttempt.acquiring", -1);
        addToMetric("lockAttempt.acquired", 1);
        setMetricTo("lockAttempt.locked", 1);
        setMetricTo("lockAttempt.acquiringLatency", getDurationOfAcquire().toMillis() / 1000.);
    }

    void released() {
        setTerminalState(LockState.RELEASED);
        setMetricTo("lockAttempt.locked", 0);
        addToMetric("lockAttempt.released", 1);
        setMetricTo("lockAttempt.lockedLatency", getDurationWithLock().toMillis() / 1000.);
    }

    void releasedWithError() {
        setTerminalState(LockState.RELEASED_WITH_ERROR);
        setMetricTo("lockAttempt.locked", 0);
        addToMetric("lockAttempt.releaseError", 1);
        setMetricTo("lockAttempt.lockedLatency", getDurationWithLock().toMillis() / 1000.);
    }

    void setTerminalState(LockState terminalState) { setTerminalState(terminalState, Instant.now()); }

    void setTerminalState(LockState terminalState, Instant instant) {
        lockState = terminalState;
        terminalStateInstant = Optional.of(instant);
    }

    private void addToMetric(String key, Number value) {
        if (metric.isPresent() && metricContext.isPresent()) {
            metric.get().add(key, value, metricContext.get());
        }
    }

    private void setMetricTo(String key, Number value) {
        if (metric.isPresent() && metricContext.isPresent()) {
            metric.get().set(key, value, metricContext.get());
        }
    }
}