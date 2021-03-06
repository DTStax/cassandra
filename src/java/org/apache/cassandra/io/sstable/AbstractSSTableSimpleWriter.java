/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.io.sstable;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.compaction.OperationType;
import org.apache.cassandra.db.lifecycle.LifecycleTransaction;
import org.apache.cassandra.db.rows.EncodingStats;
import org.apache.cassandra.db.partitions.PartitionUpdate;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.io.sstable.format.RangeAwareSSTableWriter;
import org.apache.cassandra.io.sstable.format.SSTableFormat;
import org.apache.cassandra.service.ActiveRepairService;
import org.apache.cassandra.utils.Pair;

/**
 * Base class for the sstable writers used by CQLSSTableWriter.
 */
abstract class AbstractSSTableSimpleWriter implements Closeable
{
    protected final ColumnFamilyStore cfs;
    protected final IPartitioner partitioner;
    protected final PartitionColumns columns;
    protected SSTableFormat.Type formatType = DatabaseDescriptor.getSSTableFormat();
    protected static AtomicInteger generation = new AtomicInteger(0);
    protected boolean makeRangeAware = false;

    protected AbstractSSTableSimpleWriter(ColumnFamilyStore cfs, IPartitioner partitioner,  PartitionColumns columns)
    {
        this.cfs = cfs;
        this.partitioner = partitioner;
        this.columns = columns;
    }

    protected void setSSTableFormatType(SSTableFormat.Type type)
    {
        this.formatType = type;
    }

    protected void setRangeAwareWriting(boolean makeRangeAware)
    {
        this.makeRangeAware = makeRangeAware;
    }


    protected SSTableTxnWriter createWriter()
    {
        SerializationHeader header = new SerializationHeader(true, cfs.metadata, columns, EncodingStats.NO_STATS);

        if (makeRangeAware)
            return SSTableTxnWriter.createRangeAware(cfs, 0,  ActiveRepairService.UNREPAIRED_SSTABLE, formatType, 0, header);

        return SSTableTxnWriter.create(cfs,
                                       createDescriptor(cfs.getDirectories().getDirectoryForNewSSTables(), cfs.metadata.ksName, cfs.metadata.cfName, formatType),
                                       0,
                                       ActiveRepairService.UNREPAIRED_SSTABLE,
                                       0,
                                       header);
    }

    private static Descriptor createDescriptor(File directory, final String keyspace, final String columnFamily, final SSTableFormat.Type fmt)
    {
        int maxGen = getNextGeneration(directory, columnFamily);
        return new Descriptor(directory, keyspace, columnFamily, maxGen + 1, fmt);
    }

    private static int getNextGeneration(File directory, final String columnFamily)
    {
        final Set<Descriptor> existing = new HashSet<>();
        directory.list(new FilenameFilter()
        {
            public boolean accept(File dir, String name)
            {
                Pair<Descriptor, Component> p = SSTable.tryComponentFromFilename(dir, name);
                Descriptor desc = p == null ? null : p.left;
                if (desc == null)
                    return false;

                if (desc.cfname.equals(columnFamily))
                    existing.add(desc);

                return false;
            }
        });
        int maxGen = generation.getAndIncrement();
        for (Descriptor desc : existing)
        {
            while (desc.generation > maxGen)
            {
                maxGen = generation.getAndIncrement();
            }
        }
        return maxGen;
    }

    PartitionUpdate getUpdateFor(ByteBuffer key) throws IOException
    {
        return getUpdateFor(partitioner.decorateKey(key));
    }

    /**
     * Returns a PartitionUpdate suitable to write on this writer for the provided key.
     *
     * @param key they partition key for which the returned update will be.
     * @return an update on partition {@code key} that is tied to this writer.
     */
    abstract PartitionUpdate getUpdateFor(DecoratedKey key) throws IOException;
}

