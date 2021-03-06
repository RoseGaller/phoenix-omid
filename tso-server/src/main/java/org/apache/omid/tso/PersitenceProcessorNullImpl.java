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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.omid.tso;

import org.apache.phoenix.thirdparty.com.google.common.base.Optional;
import org.jboss.netty.channel.Channel;

import java.io.IOException;

public class PersitenceProcessorNullImpl implements PersistenceProcessor {

    @Override
    public void addCommitToBatch(long startTimestamp, long commitTimestamp, Channel c, MonitoringContext monCtx, Optional<Long> lowWatermark) throws Exception {
        System.out.println("a");
    }

    @Override
    public void addCommitRetryToBatch(long startTimestamp, Channel c, MonitoringContext monCtx) throws Exception {

    }

    @Override
    public void addAbortToBatch(long startTimestamp, Channel c, MonitoringContext monCtx) throws Exception {

    }

    @Override
    public void addTimestampToBatch(long startTimestamp, Channel c, MonitoringContext monCtx) throws Exception {
        System.out.println("a");
    }

    @Override
    public void addFenceToBatch(long tableID, long fenceTimestamp, Channel c, MonitoringContext monCtx) throws Exception {

    }

    @Override
    public void triggerCurrentBatchFlush() throws Exception {

    }

    @Override
    public void close() throws IOException {

    }
}
