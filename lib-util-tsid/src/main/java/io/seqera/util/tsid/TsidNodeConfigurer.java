/*
 * Copyright 2026, Seqera Labs
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
 *
 */
package io.seqera.util.tsid;

import io.micronaut.context.annotation.Context;
import io.seqera.nodeid.NodeId;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seeds the TSID node ID from the coordinated {@link NodeId} before any ID is generated.
 *
 * <p>{@code TsidCreator} reads its node ID from the {@code tsidcreator.node} system property
 * at first use, via a lazily-initialised factory holder. This bean is eager ({@link Context})
 * so the property is set during startup — well before the first {@code TsidCreator} call —
 * giving each replica a distinct, collision-free node ID.
 *
 * @author Paolo Di Tommaso
 */
@Context
public class TsidNodeConfigurer {

    private static final Logger log = LoggerFactory.getLogger(TsidNodeConfigurer.class);

    static final String NODE_PROPERTY = "tsidcreator.node";
    static final String NODE_COUNT_PROPERTY = "tsidcreator.node.count";

    /** Default TSID node space (10 node bits). When capacity matches this, node-count is left at the library default. */
    private static final int DEFAULT_NODE_COUNT = 1024;

    @Inject
    private NodeId nodeId;

    @PostConstruct
    void init() {
        System.setProperty(NODE_PROPERTY, String.valueOf(nodeId.value()));
        if (nodeId.capacity() != DEFAULT_NODE_COUNT) {
            System.setProperty(NODE_COUNT_PROPERTY, String.valueOf(nodeId.capacity()));
        }
        log.info("TSID node ID configured - node={}, capacity={}", nodeId.value(), nodeId.capacity());
    }
}
