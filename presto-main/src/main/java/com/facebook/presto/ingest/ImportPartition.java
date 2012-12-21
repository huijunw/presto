/*
 * Copyright 2004-present Facebook. All Rights Reserved.
 */
package com.facebook.presto.ingest;

import com.facebook.presto.operator.OperatorStats;
import com.facebook.presto.spi.ImportClient;
import com.facebook.presto.spi.PartitionChunk;
import com.google.common.base.Preconditions;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Closeables;

import java.util.List;
import java.util.concurrent.Callable;

import static com.facebook.presto.util.RetryDriver.runWithRetryUnchecked;

public class ImportPartition
        implements RecordIterable
{
    private final ImportClient importClient;
    private final PartitionChunk chunk;
    private final List<String> columnNames;

    public ImportPartition(ImportClient importClient, PartitionChunk chunk, Iterable<String> columnNames)
    {
        Preconditions.checkNotNull(importClient, "importClient is null");
        Preconditions.checkNotNull(chunk, "chunk is null");

        this.importClient = importClient;
        this.chunk = chunk;
        this.columnNames = ImmutableList.copyOf(columnNames);
    }

    public PartitionChunk getChunk()
    {
        return chunk;
    }

    @Override
    public RecordIterator iterator(OperatorStats operatorStats)
    {
        com.facebook.presto.spi.RecordIterator records = runWithRetryUnchecked(new Callable<com.facebook.presto.spi.RecordIterator>()
        {
            @Override
            public com.facebook.presto.spi.RecordIterator call()
                    throws Exception
            {
                return importClient.getRecords(chunk);
            }
        });
        operatorStats.addExpectedDataSize(chunk.getLength());
        return new ImportRecordIterator(records, columnNames);
    }

    private static class ImportRecordIterator
            extends AbstractIterator<Record>
            implements RecordIterator
    {
        private final com.facebook.presto.spi.RecordIterator importRecords;
        private final List<String> columnNames;

        private ImportRecordIterator(com.facebook.presto.spi.RecordIterator importRecords, List<String> columnNames)
        {
            this.importRecords = importRecords;
            this.columnNames = columnNames;
        }

        @Override
        protected Record computeNext()
        {
            if (importRecords.hasNext()) {
                return new ImportRecord(importRecords.next(), columnNames);
            }
            return endOfData();
        }

        @Override
        public void close()
        {
            Closeables.closeQuietly(importRecords);
        }
    }

    public static class ImportRecord
            implements Record
    {
        private final com.facebook.presto.spi.Record importRecord;
        private final List<String> columnNames;

        public ImportRecord(com.facebook.presto.spi.Record importRecord, List<String> columnNames)
        {
            this.importRecord = importRecord;
            this.columnNames = columnNames;
        }

        @Override
        public int getFieldCount()
        {
            return columnNames.size();
        }

        @Override
        public long getLong(int field)
        {
            return importRecord.getLong(columnNames.get(field));
        }

        @Override
        public double getDouble(int field)
        {
            return importRecord.getDouble(columnNames.get(field));
        }

        @Override
        public byte[] getString(int field)
        {
            return importRecord.getString(columnNames.get(field));
        }

        @Override
        public boolean isNull(int field)
        {
            return importRecord.isNull(columnNames.get(field));
        }
    }
}
