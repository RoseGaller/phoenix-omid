/**
 * Copyright (c) 2011 Yahoo! Inc. All rights reserved.
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
 * limitations under the License. See accompanying LICENSE file.
 */

package com.yahoo.omid.tso.messages;

import java.io.DataOutputStream;
import java.io.IOException;

import org.jboss.netty.buffer.ChannelBuffer;

import com.yahoo.omid.tso.TSOMessage;

/**
 * The message object that notifies clients of an aborted transaction
 * 
 */
public class AbortedTransactionReport implements TSOMessage {
    /**
     * Starting timestamp
     */
    public long startTimestamp;

    public AbortedTransactionReport() {
    }

    public AbortedTransactionReport(long startTimestamp) {
        this.startTimestamp = startTimestamp;
    }

    @Override
    public String toString() {
        return "Aborted Transaction Report: T_s:" + startTimestamp;
    }

    @Override
    public void readObject(ChannelBuffer aInputStream) {

        startTimestamp = aInputStream.readLong();
    }

    @Override
    public void writeObject(DataOutputStream aOutputStream) throws IOException {
        aOutputStream.writeLong(startTimestamp);
    }

    @Override
    public void writeObject(ChannelBuffer buffer) {
        buffer.writeLong(startTimestamp);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (int) (startTimestamp ^ (startTimestamp >>> 32));
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        AbortedTransactionReport other = (AbortedTransactionReport) obj;
        if (startTimestamp != other.startTimestamp)
            return false;
        return true;
    }

}
