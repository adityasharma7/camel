/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.support;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.camel.Component;
import org.apache.camel.ExtendedCamelContext;
import org.apache.camel.LoggingLevel;
import org.apache.camel.PollingConsumer;
import org.apache.camel.ResolveEndpointFailedException;
import org.apache.camel.spi.PollingConsumerPollStrategy;
import org.apache.camel.spi.ScheduledPollConsumerScheduler;
import org.apache.camel.spi.UriParam;
import org.apache.camel.util.PropertiesHelper;

/**
 * A base class for {@link org.apache.camel.Endpoint} which creates a {@link ScheduledPollConsumer}
 */
public abstract class ScheduledPollEndpoint extends DefaultEndpoint {

    private static final String SPRING_SCHEDULER = "org.apache.camel.spring.pollingconsumer.SpringScheduledPollConsumerScheduler";
    private static final String QUARTZ_SCHEDULER = "org.apache.camel.pollconsumer.quartz.QuartzScheduledPollConsumerScheduler";

    private transient ScheduledPollConsumerScheduler consumerScheduler;

    // if adding more options then align with org.apache.camel.support.ScheduledPollConsumer
    @UriParam(optionalPrefix = "consumer.", defaultValue = "true", label = "consumer,scheduler",
            description = "Whether the scheduler should be auto started.")
    private boolean startScheduler = true;
    @UriParam(optionalPrefix = "consumer.", defaultValue = "1000", label = "consumer,scheduler",
            description = "Milliseconds before the first poll starts."
                    + " You can also specify time values using units, such as 60s (60 seconds), 5m30s (5 minutes and 30 seconds), and 1h (1 hour).")
    private long initialDelay = 1000;
    @UriParam(optionalPrefix = "consumer.", defaultValue = "500", label = "consumer,scheduler",
            description = "Milliseconds before the next poll."
                    + " You can also specify time values using units, such as 60s (60 seconds), 5m30s (5 minutes and 30 seconds), and 1h (1 hour).")
    private long delay = 500;
    @UriParam(optionalPrefix = "consumer.", defaultValue = "MILLISECONDS", label = "consumer,scheduler",
            description = "Time unit for initialDelay and delay options.")
    private TimeUnit timeUnit = TimeUnit.MILLISECONDS;
    @UriParam(optionalPrefix = "consumer.", defaultValue = "true", label = "consumer,scheduler",
            description = "Controls if fixed delay or fixed rate is used. See ScheduledExecutorService in JDK for details.")
    private boolean useFixedDelay = true;
    @UriParam(optionalPrefix = "consumer.", label = "consumer,advanced",
            description = "A pluggable org.apache.camel.PollingConsumerPollingStrategy allowing you to provide your custom implementation"
                    + " to control error handling usually occurred during the poll operation before an Exchange have been created and being routed in Camel.")
    private PollingConsumerPollStrategy pollStrategy = new DefaultPollingConsumerPollStrategy();
    @UriParam(optionalPrefix = "consumer.", defaultValue = "TRACE", label = "consumer,scheduler",
            description = "The consumer logs a start/complete log line when it polls. This option allows you to configure the logging level for that.")
    private LoggingLevel runLoggingLevel = LoggingLevel.TRACE;
    @UriParam(optionalPrefix = "consumer.", label = "consumer",
            description = "If the polling consumer did not poll any files, you can enable this option to send an empty message (no body) instead.")
    private boolean sendEmptyMessageWhenIdle;
    @UriParam(optionalPrefix = "consumer.", label = "consumer,scheduler",
            description = "If greedy is enabled, then the ScheduledPollConsumer will run immediately again, if the previous run polled 1 or more messages.")
    private boolean greedy;
    @UriParam(optionalPrefix = "consumer.", enums = "none,spring,quartz",
            defaultValue = "none", label = "consumer,scheduler", description = "To use a cron scheduler from either camel-spring or camel-quartz component")
    private String scheduler = "none";
    @UriParam(prefix = "scheduler.", multiValue = true, label = "consumer,scheduler",
            description = "To configure additional properties when using a custom scheduler or any of the Quartz, Spring based scheduler.")
    private Map<String, Object> schedulerProperties;
    @UriParam(optionalPrefix = "consumer.", label = "consumer,scheduler",
            description = "Allows for configuring a custom/shared thread pool to use for the consumer. By default each consumer has its own single threaded thread pool.")
    private ScheduledExecutorService scheduledExecutorService;
    @UriParam(optionalPrefix = "consumer.", label = "consumer,scheduler",
            description = "To let the scheduled polling consumer backoff if there has been a number of subsequent idles/errors in a row."
                    + " The multiplier is then the number of polls that will be skipped before the next actual attempt is happening again."
                    + " When this option is in use then backoffIdleThreshold and/or backoffErrorThreshold must also be configured.")
    private int backoffMultiplier;
    @UriParam(optionalPrefix = "consumer.", label = "consumer,scheduler",
            description = "The number of subsequent idle polls that should happen before the backoffMultipler should kick-in.")
    private int backoffIdleThreshold;
    @UriParam(optionalPrefix = "consumer.", label = "consumer,scheduler",
            description = "The number of subsequent error polls (failed due some error) that should happen before the backoffMultipler should kick-in.")
    private int backoffErrorThreshold;

    protected ScheduledPollEndpoint(String endpointUri, Component component) {
        super(endpointUri, component);
    }

    protected ScheduledPollEndpoint() {
    }

    @Override
    public void configureProperties(Map<String, Object> options) {
        super.configureProperties(options);
        configureScheduledPollConsumerProperties(options, getConsumerProperties());
    }

    protected void configureScheduledPollConsumerProperties(Map<String, Object> options, Map<String, Object> consumerProperties) {
        // special for scheduled poll consumers as we want to allow end users to configure its options
        // from the URI parameters without the consumer. prefix
        Map<String, Object> schedulerProperties = PropertiesHelper.extractProperties(options, "scheduler.");
        if (schedulerProperties != null && !schedulerProperties.isEmpty()) {
            setSchedulerProperties(schedulerProperties);
        }

        // options take precedence
        String schedulerName = (String) options.getOrDefault("scheduler", scheduler);
        if (schedulerName != null) {
            if ("spring".equals(schedulerName)) {
                // special for scheduler if its "spring" or "quartz"
                try {
                    Class<? extends ScheduledPollConsumerScheduler> clazz = getCamelContext().getClassResolver().resolveMandatoryClass(SPRING_SCHEDULER, ScheduledPollConsumerScheduler.class);
                    consumerScheduler = getCamelContext().getInjector().newInstance(clazz);
                } catch (ClassNotFoundException e) {
                    throw new IllegalArgumentException("Cannot load " + SPRING_SCHEDULER + " from classpath. Make sure camel-spring.jar is on the classpath.", e);
                }
            } else if ("quartz".equals(schedulerName)) {
                // special for scheduler if its "spring" or "quartz"
                try {
                    Class<? extends ScheduledPollConsumerScheduler> clazz = getCamelContext().getClassResolver().resolveMandatoryClass(QUARTZ_SCHEDULER, ScheduledPollConsumerScheduler.class);
                    consumerScheduler = getCamelContext().getInjector().newInstance(clazz);
                } catch (ClassNotFoundException e) {
                    throw new IllegalArgumentException("Cannot load " + QUARTZ_SCHEDULER + " from classpath. Make sure camel-quartz.jar is on the classpath.", e);
                }
            } else if (!"none".equals(schedulerName)) {
                // must refer to a custom scheduler by the given name
                if (EndpointHelper.isReferenceParameter(schedulerName)) {
                    schedulerName = schedulerName.substring(1);
                }
                consumerScheduler = CamelContextHelper.mandatoryLookup(getCamelContext(), schedulerName, ScheduledPollConsumerScheduler.class);
            }
        }
    }

    @Override
    protected void configurePollingConsumer(PollingConsumer consumer) throws Exception {
        Map<String, Object> copy = new HashMap<>(getConsumerProperties());
        Map<String, Object> throwaway = new HashMap<>();

        // filter out unwanted options which is intended for the scheduled poll consumer
        // as these options are not supported on the polling consumer
        configureScheduledPollConsumerProperties(copy, throwaway);

        // configure consumer
        setProperties(consumer, copy);

        if (!isLenientProperties() && copy.size() > 0) {
            throw new ResolveEndpointFailedException(this.getEndpointUri(), "There are " + copy.size()
                    + " parameters that couldn't be set on the endpoint polling consumer."
                    + " Check the uri if the parameters are spelt correctly and that they are properties of the endpoint."
                    + " Unknown consumer parameters=[" + copy + "]");
        }
    }

    protected void initConsumerProperties() {
        // must setup consumer properties before we are ready to start
        Map<String, Object> options = getConsumerProperties();
        if (!options.containsKey("startScheduler")) {
            options.put("startScheduler", isStartScheduler());
        }
        if (!options.containsKey("initialDelay")) {
            options.put("initialDelay", getInitialDelay());
        }
        if (!options.containsKey("delay")) {
            options.put("delay", getDelay());
        }
        if (!options.containsKey("timeUnit")) {
            options.put("timeUnit", getTimeUnit());
        }
        if (!options.containsKey("useFixedDelay")) {
            options.put("useFixedDelay", isUseFixedDelay());
        }
        if (!options.containsKey("pollStrategy")) {
            options.put("pollStrategy", getPollStrategy());
        }
        if (!options.containsKey("runLoggingLevel")) {
            options.put("runLoggingLevel", getRunLoggingLevel());
        }
        if (!options.containsKey("sendEmptyMessageWhenIdle")) {
            options.put("sendEmptyMessageWhenIdle", isSendEmptyMessageWhenIdle());
        }
        if (!options.containsKey("greedy")) {
            options.put("greedy", isGreedy());
        }
        if (!options.containsKey("scheduler")) {
            // the scheduler implementation
            options.put("scheduler", consumerScheduler);
        }
        if (!options.containsKey("schedulerProperties")) {
            options.put("schedulerProperties", getSchedulerProperties());
        }
        if (!options.containsKey("scheduledExecutorService")) {
            options.put("scheduledExecutorService", getScheduledExecutorService());
        }
        if (!options.containsKey("backoffMultiplier")) {
            options.put("backoffMultiplier", getBackoffMultiplier());
        }
        if (!options.containsKey("backoffIdleThreshold")) {
            options.put("backoffIdleThreshold", getBackoffIdleThreshold());
        }
        if (!options.containsKey("backoffErrorThreshold")) {
            options.put("backoffErrorThreshold", getBackoffErrorThreshold());
        }
    }

    @Override
    protected void doStart() throws Exception {
        initConsumerProperties();
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception {
        super.doStop();
        // noop
    }

    public boolean isStartScheduler() {
        return startScheduler;
    }

    /**
     * Whether the scheduler should be auto started.
     */
    public void setStartScheduler(boolean startScheduler) {
        this.startScheduler = startScheduler;
    }

    public long getInitialDelay() {
        return initialDelay;
    }

    /**
     * Milliseconds before the first poll starts.
     * <p/>
     * The default value is 1000.
     * You can also specify time values using units, such as 60s (60 seconds), 5m30s (5 minutes and 30 seconds), and 1h (1 hour).
     * @see <a href="http://camel.apache.org/how-do-i-specify-time-period-in-a-human-friendly-syntax.html">human friendly syntax</a>
     */
    public void setInitialDelay(long initialDelay) {
        this.initialDelay = initialDelay;
    }

    public long getDelay() {
        return delay;
    }

    /**
     * Milliseconds before the next poll.
     * <p/>
     * The default value is 500.
     * You can also specify time values using units, such as 60s (60 seconds), 5m30s (5 minutes and 30 seconds), and 1h (1 hour).
     * @see <a href="http://camel.apache.org/how-do-i-specify-time-period-in-a-human-friendly-syntax.html">human friendly syntax</a>
     */
    public void setDelay(long delay) {
        this.delay = delay;
    }

    public TimeUnit getTimeUnit() {
        return timeUnit;
    }

    /**
     * Time unit for initialDelay and delay options.
     */
    public void setTimeUnit(TimeUnit timeUnit) {
        this.timeUnit = timeUnit;
    }

    public boolean isUseFixedDelay() {
        return useFixedDelay;
    }

    /**
     * Controls if fixed delay or fixed rate is used. See ScheduledExecutorService in JDK for details.
     */
    public void setUseFixedDelay(boolean useFixedDelay) {
        this.useFixedDelay = useFixedDelay;
    }

    public PollingConsumerPollStrategy getPollStrategy() {
        return pollStrategy;
    }

    /**
     * A pluggable org.apache.camel.PollingConsumerPollingStrategy allowing you to provide your custom implementation
     * to control error handling usually occurred during the poll operation before an Exchange have been created
     * and being routed in Camel. In other words the error occurred while the polling was gathering information,
     * for instance access to a file network failed so Camel cannot access it to scan for files.
     * The default implementation will log the caused exception at WARN level and ignore it.
     */
    public void setPollStrategy(PollingConsumerPollStrategy pollStrategy) {
        this.pollStrategy = pollStrategy;
        // we are allowed to change poll strategy
    }

    public LoggingLevel getRunLoggingLevel() {
        return runLoggingLevel;
    }

    /**
     * The consumer logs a start/complete log line when it polls. This option allows you to configure the logging level for that.
     */
    public void setRunLoggingLevel(LoggingLevel runLoggingLevel) {
        this.runLoggingLevel = runLoggingLevel;
    }

    public boolean isSendEmptyMessageWhenIdle() {
        return sendEmptyMessageWhenIdle;
    }

    /**
     * If the polling consumer did not poll any files, you can enable this option to send an empty message (no body) instead.
     */
    public void setSendEmptyMessageWhenIdle(boolean sendEmptyMessageWhenIdle) {
        this.sendEmptyMessageWhenIdle = sendEmptyMessageWhenIdle;
    }

    public boolean isGreedy() {
        return greedy;
    }

    /**
     * If greedy is enabled, then the ScheduledPollConsumer will run immediately again, if the previous run polled 1 or more messages.
     */
    public void setGreedy(boolean greedy) {
        this.greedy = greedy;
    }

    /**
     * Allow to plugin a custom org.apache.camel.spi.ScheduledPollConsumerScheduler to use as the scheduler for
     * firing when the polling consumer runs. This option is used for referring to one of the built-in schedulers
     * either <tt>spring</tt>, or <tt>quartz</tt>. Using <tt>none</tt> refers to no scheduler to be used.
     *
     * Notice: If using a custom scheduler then the options for initialDelay, useFixedDelay, timeUnit,
     * and scheduledExecutorService may not be in use. Use the text quartz to refer to use the Quartz scheduler;
     * and use the text spring to use the Spring based; and use the text #myScheduler to refer to a custom scheduler
     * by its id in the Registry. See Quartz page for an example.
     */
    public void setScheduler(String schedulerName) {
        this.scheduler = schedulerName;
    }

    public String getScheduler() {
        return scheduler;
    }

    public Map<String, Object> getSchedulerProperties() {
        return schedulerProperties;
    }

    /**
     * To configure additional properties when using a custom scheduler or any of the Quartz, Spring based scheduler.
     */
    public void setSchedulerProperties(Map<String, Object> schedulerProperties) {
        this.schedulerProperties = schedulerProperties;
    }

    public ScheduledExecutorService getScheduledExecutorService() {
        return scheduledExecutorService;
    }

    /**
     * Allows for configuring a custom/shared thread pool to use for the consumer.
     * By default each consumer has its own single threaded thread pool.
     * This option allows you to share a thread pool among multiple consumers.
     */
    public void setScheduledExecutorService(ScheduledExecutorService scheduledExecutorService) {
        this.scheduledExecutorService = scheduledExecutorService;
    }

    public int getBackoffMultiplier() {
        return backoffMultiplier;
    }

    /**
     * To let the scheduled polling consumer backoff if there has been a number of subsequent idles/errors in a row.
     * The multiplier is then the number of polls that will be skipped before the next actual attempt is happening again.
     * When this option is in use then backoffIdleThreshold and/or backoffErrorThreshold must also be configured.
     */
    public void setBackoffMultiplier(int backoffMultiplier) {
        this.backoffMultiplier = backoffMultiplier;
    }

    public int getBackoffIdleThreshold() {
        return backoffIdleThreshold;
    }

    /**
     * The number of subsequent idle polls that should happen before the backoffMultipler should kick-in.
     */
    public void setBackoffIdleThreshold(int backoffIdleThreshold) {
        this.backoffIdleThreshold = backoffIdleThreshold;
    }

    public int getBackoffErrorThreshold() {
        return backoffErrorThreshold;
    }

    /**
     * The number of subsequent error polls (failed due some error) that should happen before the backoffMultipler should kick-in.
     */
    public void setBackoffErrorThreshold(int backoffErrorThreshold) {
        this.backoffErrorThreshold = backoffErrorThreshold;
    }

}
