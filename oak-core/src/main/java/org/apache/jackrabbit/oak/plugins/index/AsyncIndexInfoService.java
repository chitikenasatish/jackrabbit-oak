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

package org.apache.jackrabbit.oak.plugins.index;

import javax.annotation.CheckForNull;

import org.apache.jackrabbit.oak.spi.state.NodeState;

public interface AsyncIndexInfoService {

    /**
     * Returns all the async indexing lanes which are active
     * in the setup.
     */
    Iterable<String> getAsyncLanes();

    /**
     * Returns all the async indexing lanes which are active
     * in the setup based on given root NodeState
     *
     * @param root root NodeState from which async index state
     *             is read
     */
    Iterable<String> getAsyncLanes(NodeState root);

    /**
     * Returns the info for async indexer with given name
     */
    @CheckForNull
    AsyncIndexInfo getInfo(String name);

    /**
     * Returns the info for async indexer with given name
     * and based on given root NodeState
     */
    @CheckForNull
    AsyncIndexInfo getInfo(String name, NodeState root);
}
