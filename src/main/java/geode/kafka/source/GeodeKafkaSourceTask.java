/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package geode.kafka.source;

import geode.kafka.GeodeContext;
import org.apache.geode.cache.query.CqAttributes;
import org.apache.geode.cache.query.CqAttributesFactory;
import org.apache.geode.cache.query.CqEvent;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;

import static geode.kafka.source.GeodeSourceConnectorConfig.BATCH_SIZE;
import static geode.kafka.source.GeodeSourceConnectorConfig.QUEUE_SIZE;
import static geode.kafka.source.GeodeSourceConnectorConfig.REGION_PARTITION;

public class GeodeKafkaSourceTask extends SourceTask {

    private static final Logger logger = LoggerFactory.getLogger(GeodeKafkaSourceTask.class);

    private static final String TASK_PREFIX = "TASK";
    private static final String DOT = ".";

    //property string to pass in to identify task
    private static final Map<String, Long> OFFSET_DEFAULT = createOffset();

    private GeodeContext geodeContext;
    private GeodeSourceConnectorConfig geodeConnectorConfig;
    private EventBufferSupplier eventBufferSupplier;
    private Map<String, List<String>> regionToTopics;
    private Map<String, Map<String, String>> sourcePartitions;
    private int batchSize;


    private static Map<String, Long> createOffset() {
        Map<String, Long> offset = new HashMap<>();
        offset.put("OFFSET", 0L);
        return offset;
    }

    @Override
    public String version() {
        return null;
    }

    @Override
    public void start(Map<String, String> props) {
        try {
            geodeConnectorConfig = new GeodeSourceConnectorConfig(props);
            int taskId = geodeConnectorConfig.getTaskId();
            logger.debug("GeodeKafkaSourceTask id:" + geodeConnectorConfig.getTaskId() + " starting");
            geodeContext = new GeodeContext();
            geodeContext.connectClient(geodeConnectorConfig.getLocatorHostPorts(), geodeConnectorConfig.getDurableClientId(), geodeConnectorConfig.getDurableClientTimeout(), geodeConnectorConfig.getSecurityClientAuthInit());

            batchSize = Integer.parseInt(props.get(BATCH_SIZE));
            eventBufferSupplier = new SharedEventBufferSupplier(Integer.parseInt(props.get(QUEUE_SIZE)));

            regionToTopics = geodeConnectorConfig.getRegionToTopics();
            geodeConnectorConfig.getCqsToRegister();
            sourcePartitions = createSourcePartitionsMap(regionToTopics.keySet());

            String cqPrefix = geodeConnectorConfig.getCqPrefix();
            boolean loadEntireRegion = geodeConnectorConfig.getLoadEntireRegion();
            installOnGeode(geodeConnectorConfig, geodeContext, eventBufferSupplier, cqPrefix, loadEntireRegion);
        } catch (Exception e) {
            e.printStackTrace();
            logger.error("Unable to start source task", e);
            throw e;
        }
    }

    @Override
    public List<SourceRecord> poll() throws InterruptedException {
        ArrayList<SourceRecord> records = new ArrayList<>(batchSize);
        ArrayList<GeodeEvent> events = new ArrayList<>(batchSize);
        if (eventBufferSupplier.get().drainTo(events, batchSize) > 0) {
            for (GeodeEvent event : events) {
                String regionName = event.getRegionName();
                List<String> topics = regionToTopics.get(regionName);
                for (String topic : topics) {
                    records.add(new SourceRecord(sourcePartitions.get(regionName), OFFSET_DEFAULT, topic, null, event.getEvent().getKey(), null, event.getEvent().getNewValue()));
                }
            }
            return records;
        }

        return null;
    }

    @Override
    public void stop() {
        geodeContext.getClientCache().close(true);
    }

    void installOnGeode(GeodeSourceConnectorConfig geodeConnectorConfig, GeodeContext geodeContext, EventBufferSupplier eventBuffer, String cqPrefix, boolean loadEntireRegion) {
        boolean isDurable = geodeConnectorConfig.isDurable();
        int taskId = geodeConnectorConfig.getTaskId();
        for (String region : geodeConnectorConfig.getCqsToRegister()) {
            installListenersToRegion(geodeContext, taskId, eventBuffer, region, cqPrefix, loadEntireRegion, isDurable);
        }
        if (isDurable) {
            geodeContext.getClientCache().readyForEvents();
        }
    }

    GeodeKafkaSourceListener installListenersToRegion(GeodeContext geodeContext, int taskId, EventBufferSupplier eventBuffer, String regionName, String cqPrefix, boolean loadEntireRegion, boolean isDurable) {
        CqAttributesFactory cqAttributesFactory = new CqAttributesFactory();
        GeodeKafkaSourceListener listener = new GeodeKafkaSourceListener(eventBuffer, regionName);
        cqAttributesFactory.addCqListener(listener);
        CqAttributes cqAttributes = cqAttributesFactory.create();
        try {
            if (loadEntireRegion) {
                Collection<CqEvent> events = geodeContext.newCqWithInitialResults(generateCqName(taskId, cqPrefix, regionName), "select * from /" + regionName, cqAttributes,
                        isDurable);
                eventBuffer.get().addAll(events.stream().map(e -> new GeodeEvent(regionName, e)).collect(Collectors.toList()));
            } else {
                geodeContext.newCq(generateCqName(taskId, cqPrefix, regionName), "select * from /" + regionName, cqAttributes,
                        isDurable);
            }
        } finally {
            listener.signalInitialResultsLoaded();
        }
        return listener;
    }

    /**
     * converts a list of regions names into a map of source partitions
     *
     * @param regionNames list of regionNames
     * @return Map<String, Map < String, String>> a map of source partitions, keyed by region name
     */
    Map<String, Map<String, String>> createSourcePartitionsMap(Collection<String> regionNames) {
        return regionNames.stream().map(regionName -> {
            Map<String, String> sourcePartition = new HashMap<>();
            sourcePartition.put(REGION_PARTITION, regionName);
            return sourcePartition;
        }).collect(Collectors.toMap(s -> s.get(REGION_PARTITION), s -> s));
    }

    String generateCqName(int taskId, String cqPrefix, String regionName) {
        return cqPrefix + DOT + TASK_PREFIX + taskId + DOT + regionName;
    }
}
