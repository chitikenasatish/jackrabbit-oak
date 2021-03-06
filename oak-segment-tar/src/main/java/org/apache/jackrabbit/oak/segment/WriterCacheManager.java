/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.jackrabbit.oak.segment;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Suppliers.memoize;
import static com.google.common.collect.Iterators.transform;
import static com.google.common.collect.Maps.newConcurrentMap;
import static java.lang.Integer.getInteger;
import static org.apache.jackrabbit.oak.segment.RecordCache.newRecordCache;

import java.util.Iterator;
import java.util.concurrent.ConcurrentMap;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.cache.CacheStats;
import org.apache.jackrabbit.oak.api.jmx.CacheStatsMBean;
import org.apache.jackrabbit.oak.segment.file.PriorityCache;
import org.apache.jackrabbit.oak.stats.CounterStats;
import org.apache.jackrabbit.oak.stats.StatisticsProvider;
import org.apache.jackrabbit.oak.stats.StatsOptions;

/**
 * Instances of this class manage the deduplication caches used
 * by the {@link SegmentWriter} to avoid writing multiple copies
 * of the same record. The caches are striped into generations
 * with one generation per gc cycle. This avoids records old
 * generations being reused.
 */
public abstract class WriterCacheManager {
    /**
     * The type of operation a cache acquired through this manager is used for.
     * This type is used to determine against which monitoring endpoint to report
     * access statistics.
     *
     * @see #getStringCache(int, Operation)
     * @see #getTemplateCache(int, Operation)
     * @see #getNodeCache(int, Operation)
     */
    public enum Operation {WRITE, COMPACT}

    /**
     * Default size of the string cache, used as default for OSGi config.
     */
    static final int DEFAULT_STRING_CACHE_SIZE_OSGi = 15000;

    /**
     * Default size of the string cache.
     * @see #getStringCache(int, Operation)
     */
    public static final int DEFAULT_STRING_CACHE_SIZE = getInteger(
            "oak.tar.stringsCacheSize", DEFAULT_STRING_CACHE_SIZE_OSGi);

    /**
     * Default size of the template cache, used as default for OSGi config.
     */
    static final int DEFAULT_TEMPLATE_CACHE_SIZE_OSGi = 3000;

    /**
     * Default size of the template cache.
     * @see #getTemplateCache(int, Operation)
     */
    public static final int DEFAULT_TEMPLATE_CACHE_SIZE = getInteger(
            "oak.tar.templatesCacheSize", DEFAULT_TEMPLATE_CACHE_SIZE_OSGi);

    /**
     * Default size of the node deduplication cache, used as default for OSGi
     * config.
     */
    static final int DEFAULT_NODE_CACHE_SIZE_OSGi = 1048576;

    /**
     * Default size of the node deduplication cache.
     * @see #getNodeCache(int, Operation)
     */
    public static final int DEFAULT_NODE_CACHE_SIZE = getInteger(
            "oak.tar.nodeCacheSize", DEFAULT_NODE_CACHE_SIZE_OSGi);

    /**
     * @return  cache for string records of the given {@code generation} and {@code operation}.
     */
    @Nonnull
    public abstract Cache<String, RecordId> getStringCache(int generation, Operation operation);

    /**
     * @param generation
     * @return  cache for template records of the given {@code generation} and {@code operation}.
     */
    @Nonnull
    public abstract Cache<Template, RecordId> getTemplateCache(int generation, Operation operation);

    /**
     * @return  cache for node records of the given {@code generation} and {@code operation}.
     */
    @Nonnull
    public abstract Cache<String, RecordId> getNodeCache(int generation, Operation operation);

    /**
     * @return  statistics for the string cache or {@code null} if not available.
     */
    @CheckForNull
    public CacheStatsMBean getStringCacheStats() {
        return null;
    }

    /**
     * @return  statistics for the template cache or {@code null} if not available.
     */
    @CheckForNull
    public CacheStatsMBean getTemplateCacheStats() {
        return null;
    }

    /**
     * @return  statistics for the node cache or {@code null} if not available.
     */
    @CheckForNull
    public CacheStatsMBean getNodeCacheStats() {
        return null;
    }

    /**
     * Get occupancy information for the node deduplication cache indicating occupancy and
     * evictions per priority.
     * @return  occupancy information for the node deduplication cache.
     */
    @CheckForNull
    public String getNodeCacheOccupancyInfo() { return null; }

    /**
     * This implementation of {@link WriterCacheManager} returns empty caches
     * of size 0.
     * @see #INSTANCE
     */
    public static class Empty extends WriterCacheManager {

        /**
         * Singleton instance of {@link Empty}
         */
        public static final WriterCacheManager INSTANCE = new Empty();

        private final RecordCache<String> stringCache = newRecordCache(0);
        private final RecordCache<Template> templateCache = newRecordCache(0);

        private Empty() {}

        /**
         * @return  empty cache of size 0
         */
        @Nonnull
        @Override
        public RecordCache<String> getStringCache(int generation, Operation operation) {
            return stringCache;
        }

        /**
         * @return  empty cache of size 0
         */
        @Nonnull
        @Override
        public RecordCache<Template> getTemplateCache(int generation, Operation operation) {
            return templateCache;
        }

        /**
         * @return  a {@code Cache} cache that is always empty
         */
        @Nonnull
        @Override
        public Cache<String, RecordId> getNodeCache(int generation, Operation operation) {
            return new Cache<String, RecordId>() {
                @Override
                public void put(@Nonnull String stableId, @Nonnull RecordId recordId, byte cost) { }

                @Override
                public void put(@Nonnull String key, @Nonnull RecordId value) {
                    throw new UnsupportedOperationException();
                }

                @CheckForNull
                @Override
                public RecordId get(@Nonnull String stableId) { return null; }
            };
        }
    }

    /**
     * This implementation of {@link WriterCacheManager} returns
     * {@link RecordCache} instances for the string and template cache
     * and {@link Cache} instance for the node cache.
     */
    public static class Default extends WriterCacheManager {
        /**
         * Cache of recently stored string records, used to avoid storing duplicates
         * of frequently occurring data.
         */
        private final Generations<RecordCache<String>> stringCaches;

        /**
         * Cache of recently stored template records, used to avoid storing
         * duplicates of frequently occurring data.
         */
        private final Generations<RecordCache<Template>> templateCaches;

        /**
         * Cache of recently stored nodes to avoid duplicating linked nodes (i.e. checkpoints)
         * during compaction.
         */
        private final Supplier<PriorityCache<String, RecordId>> nodeCache;

        /**
         * The {@code StatisticsProvider} instance used to expose statistics of
         * the caches managed by this instance.
         */
        @Nonnull
        private final StatisticsProvider statisticsProvider;

        /**
         * New instance using the passed factories for creating cache instances.
         * The factories will be invoked exactly once when a generation of a
         * cache is requested that has not been requested before.
         *
         * @param stringCacheFactory       factory for the string cache
         * @param templateCacheFactory     factory for the template cache
         * @param nodeCacheFactory         factory for the node cache
         * @param statisticsProvider       The {@code StatisticsProvider} instance to expose
         *                                 statistics of the caches managed by this instance.
         */
        public Default(
                @Nonnull Supplier<RecordCache<String>> stringCacheFactory,
                @Nonnull Supplier<RecordCache<Template>> templateCacheFactory,
                @Nonnull Supplier<PriorityCache<String, RecordId>> nodeCacheFactory,
                @Nonnull StatisticsProvider statisticsProvider) {
            this.stringCaches = new Generations<>(stringCacheFactory);
            this.templateCaches = new Generations<>(templateCacheFactory);
            this.nodeCache = memoize(nodeCacheFactory);
            this.statisticsProvider = checkNotNull(statisticsProvider);
        }

        /**
         * New instance using the default factories {@link RecordCache#factory(int)}
         * and {@link PriorityCache#factory(int)} with the sizes
         * {@link #DEFAULT_STRING_CACHE_SIZE}, {@link #DEFAULT_TEMPLATE_CACHE_SIZE}
         * and {@link #DEFAULT_NODE_CACHE_SIZE}.
         */
        public Default() {
            this(RecordCache.<String>factory(DEFAULT_STRING_CACHE_SIZE),
                 RecordCache.<Template>factory(DEFAULT_TEMPLATE_CACHE_SIZE),
                 PriorityCache.<String, RecordId>factory(DEFAULT_NODE_CACHE_SIZE),
                 StatisticsProvider.NOOP);
        }

        private static class Generations<T> implements Iterable<T> {
            private final ConcurrentMap<Integer, Supplier<T>> generations = newConcurrentMap();
            private final Supplier<T> cacheFactory;

            Generations(@Nonnull Supplier<T> cacheFactory) {
                this.cacheFactory = checkNotNull(cacheFactory);
            }

            T getGeneration(final int generation) {
                // Preemptive check to limit the number of wasted (Memoizing)Supplier instances
                if (!generations.containsKey(generation)) {
                    generations.putIfAbsent(generation, memoize(cacheFactory));
                }
                return generations.get(generation).get();
            }

            @Nonnull
            @Override
            public Iterator<T> iterator() {
                return transform(generations.values().iterator(), new Function<Supplier<T>, T>() {
                    @Nullable @Override
                    public T apply(Supplier<T> cacheFactory) {
                        return cacheFactory.get();
                    }
                });
            }

            void evictGenerations(@Nonnull Predicate<Integer> evict) {
                Iterator<Integer> it = generations.keySet().iterator();
                while (it.hasNext()) {
                    if (evict.apply(it.next())) {
                        it.remove();
                    }
                }
            }
        }

        @Nonnull
        @Override
        public Cache<String, RecordId> getStringCache(int generation, Operation operation) {
            return new AccessTrackingCache<>("oak.segment.string-deduplication-cache-" + operation,
                    stringCaches.getGeneration(generation));
        }

        @Nonnull
        @Override
        public Cache<Template, RecordId> getTemplateCache(int generation, Operation operation) {
            return new AccessTrackingCache<>("oak.segment.template-deduplication-cache-" + operation,
                    templateCaches.getGeneration(generation));
        }

        private PriorityCache<String, RecordId> nodeCache() {
            return nodeCache.get();
        }

        @Override
        @Nonnull
        public Cache<String, RecordId> getNodeCache(final int generation, Operation operation) {
            return new AccessTrackingCache<>("oak.segment.node-deduplication-cache-" + operation,
                    new Cache<String, RecordId>() {
                @Override
                public void put(@Nonnull String stableId, @Nonnull RecordId recordId, byte cost) {
                    nodeCache().put(stableId, recordId, generation, cost);
                }

                @Override
                public void put(@Nonnull String key, @Nonnull RecordId value) {
                    throw new UnsupportedOperationException();
                }

                @CheckForNull
                @Override
                public RecordId get(@Nonnull String stableId) {
                    return nodeCache().get(stableId, generation);
                }
            });
        }

        @CheckForNull
        @Override
        public CacheStatsMBean getStringCacheStats() {
            return new RecordCacheStats("String deduplication cache stats",
                    accumulateRecordCacheStats(stringCaches),
                    accumulateRecordCacheSizes(stringCaches),
                    accumulateRecordCacheWeights(stringCaches));
        }

        @CheckForNull
        @Override
        public CacheStatsMBean getTemplateCacheStats() {
            return new RecordCacheStats("Template deduplication cache stats",
                    accumulateRecordCacheStats(templateCaches),
                    accumulateRecordCacheSizes(templateCaches),
                    accumulateRecordCacheWeights(templateCaches));
        }

        @Nonnull
        private static <T> Supplier<CacheStats> accumulateRecordCacheStats(
                final Iterable<RecordCache<T>> caches) {
            return new Supplier<CacheStats>() {
                @Override
                public CacheStats get() {
                    CacheStats stats = new CacheStats(0, 0, 0, 0, 0, 0);
                    for (RecordCache<?> cache : caches) {
                        stats = stats.plus(cache.getStats());
                    }
                    return stats;
                }
            };
        }

        @Nonnull
        public static <T> Supplier<Long> accumulateRecordCacheSizes(
                final Iterable<RecordCache<T>> caches) {
            return new Supplier<Long>() {
                @Override
                public Long get() {
                    long size = 0;
                    for (RecordCache<?> cache : caches) {
                        size += cache.size();
                    }
                    return size;
                }
            };
        }

        @Nonnull
        public static <T> Supplier<Long> accumulateRecordCacheWeights(
                final Iterable<RecordCache<T>> caches) {
            return new Supplier<Long>() {
                @Override
                public Long get() {
                    long size = 0;
                    for (RecordCache<?> cache : caches) {
                        size += cache.estimateCurrentWeight();
                    }
                    return size;
                }
            };
        }

        @CheckForNull
        @Override
        public CacheStatsMBean getNodeCacheStats() {
            return new RecordCacheStats("Node deduplication cache stats",
                    new Supplier<CacheStats>() {
                        @Override
                        public CacheStats get() {
                            return nodeCache().getStats();
                        }
                    },
                    new Supplier<Long>() {
                        @Override
                        public Long get() {
                            return nodeCache().size();
                        }
                    },
                    new Supplier<Long>() {
                        @Override
                        public Long get() {
                            return nodeCache().estimateCurrentWeight();
                        }
                    });
        }

        /**
         * Remove all cache generations matching the passed {@code generations} predicate.
         * @param generations
         */
        protected final void evictCaches(Predicate<Integer> generations) {
            stringCaches.evictGenerations(generations);
            templateCaches.evictGenerations(generations);
            nodeCache().purgeGenerations(generations);
        }

        /**
         * {@code Cache} wrapper exposing the number of read accesses and the
         * number of misses ot the underlying cache via the {@link #statisticsProvider}.
         */
        private class AccessTrackingCache<K, V> implements Cache<K,V> {
            private final Cache<K, V> delegate;
            private final CounterStats accessCount;
            private final CounterStats missCount;

            private AccessTrackingCache(@Nonnull String name, @Nonnull Cache<K, V> delegate) {
                this.delegate = delegate;
                this.accessCount = statisticsProvider.getCounterStats(
                        name + ".access-count", StatsOptions.DEFAULT);
                this.missCount = statisticsProvider.getCounterStats(
                        name + ".miss-count", StatsOptions.DEFAULT);
            }

            @Override
            public void put(@Nonnull K key, @Nonnull V value) {
                delegate.put(key, value);
            }

            @Override
            public void put(@Nonnull K key, @Nonnull V value, byte cost) {
                delegate.put(key, value, cost);
            }

            @CheckForNull
            @Override
            public V get(@Nonnull K key) {
                V v = delegate.get(key);
                accessCount.inc();
                if (v == null) {
                    missCount.inc();
                }
                return v;
            }
        }

    }
}
