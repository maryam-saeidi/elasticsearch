/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.aggregations.metrics.tophits;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.*;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.common.lease.Releasables;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.common.lucene.ScorerAware;
import org.elasticsearch.common.util.LongObjectPagedHashMap;
import org.elasticsearch.search.aggregations.*;
import org.elasticsearch.search.aggregations.metrics.MetricsAggregator;
import org.elasticsearch.search.aggregations.support.AggregationContext;
import org.elasticsearch.search.fetch.FetchPhase;
import org.elasticsearch.search.fetch.FetchSearchResult;
import org.elasticsearch.search.internal.InternalSearchHit;
import org.elasticsearch.search.internal.InternalSearchHits;

import java.io.IOException;
import java.util.Map;

/**
 */
public class TopHitsAggregator extends MetricsAggregator implements ScorerAware {

    /** Simple wrapper around a top-level collector and the current leaf collector. */
    private static class TopDocsAndLeafCollector {
        final TopDocsCollector<?> topLevelCollector;
        LeafCollector leafCollector;

        TopDocsAndLeafCollector(TopDocsCollector<?> topLevelCollector) {
            this.topLevelCollector = topLevelCollector;
        }
    }

    private final FetchPhase fetchPhase;
    private final TopHitsContext topHitsContext;
    private final LongObjectPagedHashMap<TopDocsAndLeafCollector> topDocsCollectors;

    private Scorer currentScorer;
    private LeafReaderContext currentContext;

    public TopHitsAggregator(FetchPhase fetchPhase, TopHitsContext topHitsContext, String name, long estimatedBucketsCount, AggregationContext context, Aggregator parent, Map<String, Object> metaData) {
        super(name, estimatedBucketsCount, context, parent, metaData);
        this.fetchPhase = fetchPhase;
        topDocsCollectors = new LongObjectPagedHashMap<>(estimatedBucketsCount, context.bigArrays());
        this.topHitsContext = topHitsContext;
        context.registerScorerAware(this);
    }

    @Override
    public boolean shouldCollect() {
        return true;
    }

    @Override
    public InternalAggregation buildAggregation(long owningBucketOrdinal) {
        TopDocsAndLeafCollector topDocsCollector = topDocsCollectors.get(owningBucketOrdinal);
        if (topDocsCollector == null) {
            return buildEmptyAggregation();
        } else {
            TopDocs topDocs = topDocsCollector.topLevelCollector.topDocs();
            if (topDocs.totalHits == 0) {
                return buildEmptyAggregation();
            }

            topHitsContext.queryResult().topDocs(topDocs);
            int[] docIdsToLoad = new int[topDocs.scoreDocs.length];
            for (int i = 0; i < topDocs.scoreDocs.length; i++) {
                docIdsToLoad[i] = topDocs.scoreDocs[i].doc;
            }
            topHitsContext.docIdsToLoad(docIdsToLoad, 0, docIdsToLoad.length);
            fetchPhase.execute(topHitsContext);
            FetchSearchResult fetchResult = topHitsContext.fetchResult();
            InternalSearchHit[] internalHits = fetchResult.fetchResult().hits().internalHits();
            for (int i = 0; i < internalHits.length; i++) {
                ScoreDoc scoreDoc = topDocs.scoreDocs[i];
                InternalSearchHit searchHitFields = internalHits[i];
                searchHitFields.shard(topHitsContext.shardTarget());
                searchHitFields.score(scoreDoc.score);
                if (scoreDoc instanceof FieldDoc) {
                    FieldDoc fieldDoc = (FieldDoc) scoreDoc;
                    searchHitFields.sortValues(fieldDoc.fields);
                }
            }
            return new InternalTopHits(name, topHitsContext.from(), topHitsContext.size(), topDocs, fetchResult.hits());
        }
    }

    @Override
    public InternalAggregation buildEmptyAggregation() {
        return new InternalTopHits(name, topHitsContext.from(), topHitsContext.size(), Lucene.EMPTY_TOP_DOCS, InternalSearchHits.empty());
    }

    @Override
    public void collect(int docId, long bucketOrdinal) throws IOException {
        TopDocsAndLeafCollector collectors = topDocsCollectors.get(bucketOrdinal);
        if (collectors == null) {
            Sort sort = topHitsContext.sort();
            int topN = topHitsContext.from() + topHitsContext.size();
            TopDocsCollector<?> topLevelCollector = sort != null ? TopFieldCollector.create(sort, topN, true, topHitsContext.trackScores(), topHitsContext.trackScores(), false) : TopScoreDocCollector.create(topN, false);
            collectors = new TopDocsAndLeafCollector(topLevelCollector);
            collectors.leafCollector = collectors.topLevelCollector.getLeafCollector(currentContext);
            collectors.leafCollector.setScorer(currentScorer);
            topDocsCollectors.put(bucketOrdinal, collectors);
        }
        collectors.leafCollector.collect(docId);
    }

    @Override
    public void setNextReader(LeafReaderContext context) {
        this.currentContext = context;
        for (LongObjectPagedHashMap.Cursor<TopDocsAndLeafCollector> cursor : topDocsCollectors) {
            try {
                cursor.value.leafCollector = cursor.value.topLevelCollector.getLeafCollector(currentContext);
            } catch (IOException e) {
                throw ExceptionsHelper.convertToElastic(e);
            }
        }
    }

    @Override
    public void setScorer(Scorer scorer) {
        this.currentScorer = scorer;
        for (LongObjectPagedHashMap.Cursor<TopDocsAndLeafCollector> cursor : topDocsCollectors) {
            try {
                cursor.value.leafCollector.setScorer(scorer);
            } catch (IOException e) {
                throw ExceptionsHelper.convertToElastic(e);
            }
        }
    }

    @Override
    protected void doClose() {
        Releasables.close(topDocsCollectors);
    }

    public static class Factory extends AggregatorFactory {

        private final FetchPhase fetchPhase;
        private final TopHitsContext topHitsContext;

        public Factory(String name, FetchPhase fetchPhase, TopHitsContext topHitsContext) {
            super(name, InternalTopHits.TYPE.name());
            this.fetchPhase = fetchPhase;
            this.topHitsContext = topHitsContext;
        }

        @Override
        public Aggregator createInternal(AggregationContext aggregationContext, Aggregator parent, long expectedBucketsCount, Map<String, Object> metaData) {
            return new TopHitsAggregator(fetchPhase, topHitsContext, name, expectedBucketsCount, aggregationContext, parent, metaData);
        }

        @Override
        public AggregatorFactory subFactories(AggregatorFactories subFactories) {
            throw new AggregationInitializationException("Aggregator [" + name + "] of type [" + type + "] cannot accept sub-aggregations");
        }

    }
}
