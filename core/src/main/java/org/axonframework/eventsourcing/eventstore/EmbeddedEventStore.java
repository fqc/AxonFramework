/*
 * Copyright (c) 2010-2016. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.eventsourcing.eventstore;

import org.axonframework.common.AxonThreadFactory;
import org.axonframework.common.io.IOUtils;
import org.axonframework.eventhandling.EventMessage;
import org.axonframework.eventhandling.TrackedEventMessage;
import org.axonframework.monitoring.MessageMonitor;
import org.axonframework.monitoring.NoOpMessageMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

/**
 * @author Rene de Waele
 */
public class EmbeddedEventStore extends AbstractEventStore {
    private static final Logger logger = LoggerFactory.getLogger(EmbeddedEventStore.class);
    private static final ThreadGroup THREAD_GROUP = new ThreadGroup(EmbeddedEventStore.class.getSimpleName());

    private final Lock consumerLock = new ReentrantLock();
    private final Condition consumableEventsCondition = consumerLock.newCondition();
    private final Set<EventConsumer> tailingConsumers = new CopyOnWriteArraySet<>();
    private final EventProducer producer;
    private final long cleanupDelayMillis;
    private final ThreadFactory threadFactory;
    private final ScheduledExecutorService cleanupService;

    private volatile Node oldest;
    private final AtomicBoolean producerStarted = new AtomicBoolean();

    public EmbeddedEventStore(EventStorageEngine storageEngine) {
        this(storageEngine, NoOpMessageMonitor.INSTANCE);
    }

    public EmbeddedEventStore(EventStorageEngine storageEngine, MessageMonitor<? super EventMessage<?>> monitor) {
        this(storageEngine, monitor, 10000, 1000L, 10000L, TimeUnit.MILLISECONDS);
    }

    public EmbeddedEventStore(EventStorageEngine storageEngine, MessageMonitor<? super EventMessage<?>> monitor,
                              int cachedEvents, long fetchDelay, long cleanupDelay, TimeUnit timeUnit) {
        super(storageEngine, monitor);
        threadFactory = new AxonThreadFactory(THREAD_GROUP);
        cleanupService = Executors.newScheduledThreadPool(1, threadFactory);
        producer = new EventProducer(timeUnit.toNanos(fetchDelay), cachedEvents);
        cleanupDelayMillis = timeUnit.toMillis(cleanupDelay);
    }

    @PreDestroy
    public void shutDown() {
        tailingConsumers.forEach(IOUtils::closeQuietly);
        IOUtils.closeQuietly(producer);
        cleanupService.shutdownNow();
    }

    private void ensureProducerStarted() {
        if (producerStarted.compareAndSet(false, true)) {
            threadFactory.newThread(() -> {
                try {
                    producer.run();
                } catch (InterruptedException e) {
                    logger.warn("Producer thread was interrupted. Shutting down event store.", e);
                    Thread.currentThread().interrupt();
                }
            }).start();
            cleanupService
                    .scheduleWithFixedDelay(new Cleaner(), cleanupDelayMillis, cleanupDelayMillis, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    protected void afterCommit(List<? extends EventMessage<?>> events) {
        producer.fetchIfWaiting();
    }

    @Override
    public TrackingEventStream streamEvents(TrackingToken trackingToken) {
        Node node = findNode(trackingToken);
        EventConsumer eventConsumer;
        if (node != null) {
            eventConsumer = new EventConsumer(node);
            tailingConsumers.add(eventConsumer);
        } else {
            eventConsumer = new EventConsumer(trackingToken);
        }
        return eventConsumer;
    }

    private Node findNode(TrackingToken trackingToken) {
        Node oldest = this.oldest;
        if (trackingToken == null || oldest == null || oldest.event.trackingToken().isAfter(trackingToken)) {
            return null;
        }
        Node node = oldest;
        while (node != null && !node.event.trackingToken().equals(trackingToken)) {
            node = node.next;
        }
        return node;
    }

    private static class Node {
        private final long index;
        private final TrackingToken previousToken;
        private final TrackedEventMessage<?> event;
        private volatile Node next;

        private Node(long index, TrackingToken previousToken, TrackedEventMessage<?> event) {
            this.index = index;
            this.previousToken = previousToken;
            this.event = event;
        }
    }

    private class EventProducer implements AutoCloseable {
        private final Lock lock = new ReentrantLock();
        private final Condition dataAvailableCondition = lock.newCondition();
        private final long fetchDelayNanos;
        private final int cachedEvents;
        private volatile boolean shouldFetch, closed;
        private Stream<? extends TrackedEventMessage<?>> eventStream;
        private Node newest;

        private EventProducer(long fetchDelayNanos, int cachedEvents) {
            this.fetchDelayNanos = fetchDelayNanos;
            this.cachedEvents = cachedEvents;
        }

        private void run() throws InterruptedException {
            boolean dataFound = false;

            while (!closed) {
                shouldFetch = true;
                while (shouldFetch) {
                    shouldFetch = false;
                    dataFound = fetchData();
                }
                if (!dataFound) {
                    waitForData();
                }
            }
        }

        private void waitForData() throws InterruptedException {
            lock.lock();
            try {
                if (!shouldFetch) {
                    dataAvailableCondition.awaitNanos(fetchDelayNanos);
                }
            } finally {
                lock.unlock();
            }
        }

        private void fetchIfWaiting() {
            shouldFetch = true;
            lock.lock();
            try {
                dataAvailableCondition.signalAll();
            } finally {
                lock.unlock();
            }
        }

        private boolean fetchData() {
            Node currentNewest = newest;
            if (!tailingConsumers.isEmpty()) {
                try {
                    eventStream = storageEngine().readEvents(lastToken(), true);
                    eventStream.forEach(event -> {
                        Node node = new Node(nextIndex(), lastToken(), event);
                        if (newest != null) {
                            newest.next = node;
                        }
                        newest = node;
                        if (oldest == null) {
                            oldest = node;
                        }
                        notifyConsumers();
                        trimCache();
                    });
                } catch (Exception e) {
                    logger.error("Failed to read events from the underlying event storage", e);
                }
            }
            return newest != currentNewest;
        }

        private TrackingToken lastToken() {
            if (newest == null) {
                List<TrackingToken> sortedTokens = tailingConsumers.stream().map(EventConsumer::lastToken)
                        .sorted(Comparator.nullsFirst(Comparator.naturalOrder())).collect(toList());
                return sortedTokens.isEmpty() ? null : sortedTokens.get(0);
            } else {
                return newest.event.trackingToken();
            }
        }

        private long nextIndex() {
            return newest == null ? 0 : newest.index + 1;
        }

        private void notifyConsumers() {
            consumerLock.lock();
            try {
                consumableEventsCondition.signalAll();
            } finally {
                consumerLock.unlock();
            }
        }

        private void trimCache() {
            Node last = oldest;
            while (newest != null && last != null && newest.index - last.index >= cachedEvents) {
                last = last.next;
            }
            oldest = last;
        }

        @Override
        public void close() {
            closed = true;
            if (eventStream != null) {
                eventStream.close();
            }
        }
    }

    private class EventConsumer implements TrackingEventStream {
        private Stream<? extends TrackedEventMessage<?>> privateStream;
        private Iterator<? extends TrackedEventMessage<?>> privateIterator;
        private volatile TrackingToken lastToken;
        private volatile Node lastNode;
        private TrackedEventMessage<?> peekedEvent;

        private EventConsumer(Node lastNode) {
            this(lastNode.event.trackingToken());
            this.lastNode = lastNode;
        }

        private EventConsumer(TrackingToken startToken) {
            this.lastToken = startToken;
        }

        @Override
        public Optional<TrackedEventMessage<?>> peek() {
            return Optional.ofNullable(peekedEvent == null && !hasNextAvailable() ? null : peekedEvent);
        }

        @Override
        public boolean hasNextAvailable(int timeout, TimeUnit unit) throws InterruptedException {
            return peekedEvent != null || (peekedEvent = peek(timeout, unit)) != null;
        }

        @Override
        public TrackedEventMessage<?> nextAvailable() throws InterruptedException {
            while (peekedEvent == null) {
                peekedEvent = peek(Integer.MAX_VALUE, TimeUnit.MILLISECONDS);
            }
            TrackedEventMessage<?> result = peekedEvent;
            peekedEvent = null;
            return result;
        }

        private TrackedEventMessage<?> peek(int timeout, TimeUnit timeUnit) throws InterruptedException {
            return isTailingConsumer() ? peekGlobalStream(timeout, timeUnit) :
                    peekPrivateStream(timeout, timeUnit);
        }

        private boolean isTailingConsumer() {
            return tailingConsumers.contains(this) && (this.lastToken == null || oldest == null || this.lastToken.isAfter(oldest.previousToken));
        }

        private TrackedEventMessage<?> peekGlobalStream(int timeout, TimeUnit timeUnit) throws InterruptedException {
            Node nextNode;
            if ((nextNode = nextNode()) == null && timeout > 0) {
                consumerLock.lock();
                try {
                    consumableEventsCondition.await(timeout, timeUnit);
                    nextNode = nextNode();
                } finally {
                    consumerLock.unlock();
                }
            }
            if (nextNode != null) {
                if (tailingConsumers.contains(this)) {
                    lastNode = nextNode;
                }
                lastToken = nextNode.event.trackingToken();
                return nextNode.event;
            } else {
                return null;
            }
        }

        private TrackedEventMessage<?> peekPrivateStream(int timeout, TimeUnit timeUnit) throws InterruptedException {
            if (privateIterator == null) {
                privateStream = storageEngine().readEvents(lastToken, false);
                privateIterator = privateStream.iterator();
            }
            if (privateIterator.hasNext()) {
                TrackedEventMessage<?> nextEvent = privateIterator.next();
                lastToken = nextEvent.trackingToken();
                return nextEvent;
            } else {
                closePrivateStream();
                lastNode = findNode(lastToken);
                tailingConsumers.add(this);
                ensureProducerStarted();
                return timeout > 0 ? peek(timeout, timeUnit) : null;
            }
        }

        private Node nextNode() {
            Node node = lastNode;
            if (node != null) {
                return node.next;
            }
            node = oldest;
            while (node != null && !Objects.equals(node.previousToken, lastToken)) {
                node = node.next;
            }
            return node;
        }

        private TrackingToken lastToken() {
            return lastToken;
        }

        @Override
        public void close() {
            closePrivateStream();
            tailingConsumers.remove(this);
        }

        private void closePrivateStream() {
            Optional.ofNullable(privateStream).ifPresent(stream -> {
                privateStream = null;
                privateIterator = null;
                stream.close();
            });
        }
    }

    private class Cleaner implements Runnable {
        @Override
        public void run() {
            Node oldestCachedNode = oldest;
            if (oldestCachedNode == null || oldestCachedNode.previousToken == null) {
                return;
            }
            tailingConsumers.stream().filter(consumer -> consumer.lastToken == null ||
                    oldestCachedNode.previousToken.isAfter(consumer.lastToken)).forEach(consumer -> {
                logger.warn("An event processor fell behind the tail end of the event store cache. " +
                                    "This usually indicates a badly performing event processor.");
                tailingConsumers.remove(consumer);
                consumer.lastNode = null; //make old nodes garbage collectible
            });
        }
    }
}
