package com.yahoo.omid.transaction;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.NavigableSet;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.io.TimeRange;
import org.apache.hadoop.hbase.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.yahoo.omid.transaction.AbstractTransactionManager.CommitTimestamp;
import com.yahoo.omid.transaction.HBaseTransactionManager.CommitTimestampLocatorImpl;

/**
 * Provides transactional methods for accessing and modifying a given snapshot
 * of data identified by an opaque {@link Transaction} object. It mimics the
 * behavior in {@link org.apache.hadoop.hbase.client.HTableInterface}
 */
public class TTable {

    private static Logger LOG = LoggerFactory.getLogger(TTable.class);

    public static byte[] DELETE_TOMBSTONE = Bytes.toBytes("__OMID_TOMBSTONE__");;

    /** We always ask for CACHE_VERSIONS_OVERHEAD extra versions */
    private static int CACHE_VERSIONS_OVERHEAD = 3;
    /** Average number of versions needed to reach the right snapshot */
    public double versionsAvg = 3;
    /** How fast do we adapt the average */
    private static final double ALPHA = 0.975;

    private final HTableInterface healerTable;

    private HTableInterface table;

    public TTable(Configuration conf, byte[] tableName) throws IOException {
        this(new HTable(conf, tableName));
    }

    public TTable(Configuration conf, String tableName) throws IOException {
        this(conf, Bytes.toBytes(tableName));
    }

    public TTable(HTableInterface hTable) throws IOException {
        table = hTable;
        healerTable = new HTable(table.getConfiguration(), table.getTableName());
    }

    public TTable(HTableInterface hTable, HTableInterface healerTable) throws IOException {
        table = hTable;
        this.healerTable = healerTable;
    }

    /**
     * Transactional version of {@link HTableInterface#get(Get get)}
     */
    public Result get(Transaction tx, final Get get) throws IOException {

        throwExceptionIfOpSetsTimerange(get);

        HBaseTransaction transaction = enforceHBaseTransactionAsParam(tx);

        final int requestedVersions = (int) (versionsAvg + CACHE_VERSIONS_OVERHEAD);
        final long readTimestamp = transaction.getStartTimestamp();
        final Get tsget = new Get(get.getRow());
        TimeRange timeRange = get.getTimeRange();
        long startTime = timeRange.getMin();
        long endTime = Math.min(timeRange.getMax(), readTimestamp + 1);
        tsget.setTimeRange(startTime, endTime).setMaxVersions(requestedVersions);
        Map<byte[], NavigableSet<byte[]>> kvs = get.getFamilyMap();
        for (Map.Entry<byte[], NavigableSet<byte[]>> entry : kvs.entrySet()) {
            byte[] family = entry.getKey();
            NavigableSet<byte[]> qualifiers = entry.getValue();
            if (qualifiers == null || qualifiers.isEmpty()) {
                tsget.addFamily(family);
            } else {
                for (byte[] qualifier : qualifiers) {
                    tsget.addColumn(family, qualifier);
                    tsget.addColumn(family, HBaseUtils.addShadowCellSuffix(qualifier));
                }
            }
        }
        LOG.trace("Initial Get = {}", tsget);

        // Return the KVs that belong to the transaction snapshot, ask for more
        // versions if needed
        Result result = table.get(tsget);
        List<Cell> filteredKeyValues = Collections.emptyList();
        if (!result.isEmpty()) {
            filteredKeyValues = filterCellsForSnapshot(result.listCells(), transaction, requestedVersions);
        }

        return Result.create(filteredKeyValues);
    }

    /**
     * Transactional version of {@link HTableInterface#delete(Delete delete)}
     */
    public void delete(Transaction tx, Delete delete) throws IOException {

        throwExceptionIfOpSetsTimerange(delete);

        HBaseTransaction transaction = enforceHBaseTransactionAsParam(tx);

        final long startTimestamp = transaction.getStartTimestamp();
        boolean issueGet = false;

        final Put deleteP = new Put(delete.getRow(), startTimestamp);
        final Get deleteG = new Get(delete.getRow());
        Map<byte[], List<Cell>> fmap = delete.getFamilyCellMap();
        if (fmap.isEmpty()) {
            issueGet = true;
        }
        for (List<Cell> cells : fmap.values()) {
            for (Cell cell : cells) {
                throwExceptionIfTimestampSet(cell);
                switch (KeyValue.Type.codeToType(cell.getTypeByte())) {
                case DeleteColumn:
                    deleteP.add(CellUtil.cloneFamily(cell),
                                CellUtil.cloneQualifier(cell),
                                startTimestamp,
                                DELETE_TOMBSTONE);
                    transaction.addWriteSetElement(
                                                   new HBaseCellId(table,
                                                                   delete.getRow(),
                                                                   CellUtil.cloneFamily(cell),
                                                                   CellUtil.cloneQualifier(cell),
                                                                   cell.getTimestamp()));
                    break;
                case DeleteFamily:
                    deleteG.addFamily(CellUtil.cloneFamily(cell));
                    issueGet = true;
                    break;
                case Delete:
                    if (cell.getTimestamp() == HConstants.LATEST_TIMESTAMP) {
                        deleteP.add(CellUtil.cloneFamily(cell),
                                    CellUtil.cloneQualifier(cell),
                                    startTimestamp,
                                    DELETE_TOMBSTONE);
                        transaction.addWriteSetElement(
                                                       new HBaseCellId(table,
                                                                       delete.getRow(),
                                                                       CellUtil.cloneFamily(cell),
                                                                       CellUtil.cloneQualifier(cell),
                                                                       cell.getTimestamp()));
                        break;
                    } else {
                        throw new UnsupportedOperationException(
                                "Cannot delete specific versions on Snapshot Isolation.");
                    }
                default:
                    break;
                }
            }
        }
        if (issueGet) {
            // It's better to perform a transactional get to avoid deleting more
            // than necessary
            Result result = this.get(transaction, deleteG);
            if (!result.isEmpty()) {
                for (Entry<byte[], NavigableMap<byte[], NavigableMap<Long, byte[]>>> entryF : result.getMap()
                        .entrySet()) {
                    byte[] family = entryF.getKey();
                    for (Entry<byte[], NavigableMap<Long, byte[]>> entryQ : entryF.getValue().entrySet()) {
                        byte[] qualifier = entryQ.getKey();
                        deleteP.add(family, qualifier, DELETE_TOMBSTONE);
                        transaction.addWriteSetElement(new HBaseCellId(table, delete.getRow(), family, qualifier, transaction.getStartTimestamp()));
                    }
                }
            }
        }

        table.put(deleteP);
    }

    /**
     * Transactional version of {@link HTableInterface#put(Put put)}
     */
    public void put(Transaction tx, Put put) throws IOException {

        throwExceptionIfOpSetsTimerange(put);

        HBaseTransaction transaction = enforceHBaseTransactionAsParam(tx);

        final long startTimestamp = transaction.getStartTimestamp();
        // create put with correct ts
        final Put tsput = new Put(put.getRow(), startTimestamp);
        Map<byte[], List<Cell>> kvs = put.getFamilyCellMap();
        for (List<Cell> kvl : kvs.values()) {
            for (Cell kv : kvl) {
                throwExceptionIfTimestampSet(kv);
                tsput.add(
                          new KeyValue(CellUtil.cloneRow(kv),
                                       CellUtil.cloneFamily(kv),
                                       CellUtil.cloneQualifier(kv),
                                       startTimestamp,
                                       CellUtil.cloneValue(kv)));
                transaction.addWriteSetElement(
                                               new HBaseCellId(table,
                                                               CellUtil.cloneRow(kv),
                                                               CellUtil.cloneFamily(kv),
                                                               CellUtil.cloneQualifier(kv),
                                                               kv.getTimestamp()));
            }
        }

        table.put(tsput);
    }

    /**
     * Transactional version of {@link HTableInterface#getScanner(Scan scan)}
     */
    public ResultScanner getScanner(Transaction tx, Scan scan) throws IOException {

        throwExceptionIfOpSetsTimerange(scan);

        HBaseTransaction transaction = enforceHBaseTransactionAsParam(tx);

        Scan tsscan = new Scan(scan);
        tsscan.setMaxVersions((int) (versionsAvg + CACHE_VERSIONS_OVERHEAD));
        tsscan.setTimeRange(0, transaction.getStartTimestamp() + 1);
        Map<byte[], NavigableSet<byte[]>> kvs = tsscan.getFamilyMap();
        for (Map.Entry<byte[], NavigableSet<byte[]>> entry : kvs.entrySet()) {
            byte[] family = entry.getKey();
            NavigableSet<byte[]> qualifiers = entry.getValue();
            if (qualifiers == null) {
                continue;
            }
            for (byte[] qualifier : qualifiers) {
                tsscan.addColumn(family, HBaseUtils.addShadowCellSuffix(qualifier));
            }
        }
        TransactionalClientScanner scanner = new TransactionalClientScanner(transaction,
                tsscan, (int) (versionsAvg + CACHE_VERSIONS_OVERHEAD));
        return scanner;
    }

    // ThreadSafe
    static class IterableColumn implements Iterable<List<Cell>> {

        // ThreadSafe
        class ColumnIterator implements Iterator<List<Cell>> {

            private final Iterator<ColumnWrapper> listIterator = columnList.listIterator();

            @Override
            public boolean hasNext() {
                return listIterator.hasNext();
            }

            @Override
            public List<Cell> next() {
                ColumnWrapper columnWrapper = listIterator.next();
                return columns.get(columnWrapper);
            }

            @Override
            public void remove() {
                // Not Implemented
            }

        }

        private final Map<ColumnWrapper, List<Cell>> columns = new HashMap<ColumnWrapper, List<Cell>>();
        private final List<ColumnWrapper> columnList = new ArrayList<ColumnWrapper>();

        public IterableColumn(List<Cell> cells) {
            for (Cell cell : cells) {
                if (HBaseUtils.isShadowCell(CellUtil.cloneQualifier(cell))) {
                    continue;
                }
                ColumnWrapper currentColumn = new ColumnWrapper(CellUtil.cloneFamily(cell), CellUtil.cloneQualifier(cell));
                if (!columns.containsKey(currentColumn)) {
                    columns.put(currentColumn, new ArrayList<Cell>(Arrays.asList(cell)));
                    columnList.add(currentColumn);
                } else {
                    List<Cell> columnKeyValues = columns.get(currentColumn);
                    columnKeyValues.add(cell);
                }
            }

        }

        @Override
        public Iterator<List<Cell>> iterator() {
            return new ColumnIterator();
        }

    }

    /**
     * Filters the raw results returned from HBase and returns only those
     * belonging to the current snapshot, as defined by the transaction
     * object. If the raw results don't contain enough information for a
     * particular qualifier, it will request more versions from HBase.
     *
     * @param rawCells
     *            Raw cells that we are going to filter
     * @param transaction
     *            Defines the current snapshot
     * @param versionsToRequest
     *            Number of versions requested from hbase
     * @return Filtered KVs belonging to the transaction snapshot
     * @throws IOException
     */
    List<Cell> filterCellsForSnapshot(List<Cell> rawCells, HBaseTransaction transaction,
            int versionsToRequest) throws IOException {

        assert (rawCells != null && transaction != null && versionsToRequest >= 1);

        List<Cell> keyValuesInSnapshot = new ArrayList<Cell>();
        List<Get> pendingGetsList = new ArrayList<Get>();

        int numberOfVersionsToFetch = versionsToRequest * 2 + CACHE_VERSIONS_OVERHEAD;

        Map<Long, Long> commitCache = buildCommitCache(rawCells);

        for (List<Cell> columnCells : new IterableColumn(rawCells)) {
            int versionsProcessed = 0;
            boolean snapshotValueFound = false;
            Cell oldestCell = null;
            for (Cell cell : columnCells) {
                if (isCellInSnapshot(cell, transaction, commitCache)) {
                    if (!Arrays.equals(CellUtil.cloneValue(cell), DELETE_TOMBSTONE)) {
                        keyValuesInSnapshot.add(cell);
                    }
                    snapshotValueFound = true;
                    break;
                }
                oldestCell = cell;
                versionsProcessed++;
            }
            if (!snapshotValueFound) {
                assert (oldestCell != null);
                Get pendingGet = createPendingGet(oldestCell, numberOfVersionsToFetch);
                pendingGetsList.add(pendingGet);
            }
            updateAvgNumberOfVersionsToFetchFromHBase(versionsProcessed);
        }

        if (!pendingGetsList.isEmpty()) {
            Result[] pendingGetsResults = table.get(pendingGetsList);
            for (Result pendingGetResult : pendingGetsResults) {
                if (!pendingGetResult.isEmpty()) {
                    keyValuesInSnapshot.addAll(
                            filterCellsForSnapshot(pendingGetResult.listCells(), transaction, numberOfVersionsToFetch));
                }
            }
        }

        Collections.sort(keyValuesInSnapshot, KeyValue.COMPARATOR);

        assert (keyValuesInSnapshot.size() <= rawCells.size());
        return keyValuesInSnapshot;
    }

    private Map<Long, Long> buildCommitCache(List<Cell> rawCells) {

        Map<Long, Long> commitCache = new HashMap<Long, Long>();

        for (Cell cell : rawCells) {
            if (HBaseUtils.isShadowCell(CellUtil.cloneQualifier(cell))) {
                commitCache.put(cell.getTimestamp(), Bytes.toLong(CellUtil.cloneValue(cell)));
            }
        }

        return commitCache;
    }

    private boolean isCellInSnapshot(Cell kv, HBaseTransaction transaction, Map<Long, Long> commitCache)
            throws IOException {

        long startTimestamp = transaction.getStartTimestamp();

        if (kv.getTimestamp() == startTimestamp) {
            return true;
        }

        Optional<Long> commitTimestamp = 
                tryToLocateCellCommitTimestamp(transaction.getTransactionManager(), kv, commitCache);

        return commitTimestamp.isPresent() && commitTimestamp.get() < startTimestamp;
    }

    private Get createPendingGet(Cell cell, int versionCount) throws IOException {

        Get pendingGet = new Get(CellUtil.cloneRow(cell));
        pendingGet.addColumn(CellUtil.cloneFamily(cell), CellUtil.cloneQualifier(cell));
        pendingGet.addColumn(CellUtil.cloneFamily(cell), HBaseUtils.addShadowCellSuffix(CellUtil.cloneQualifier(cell)));
        pendingGet.setMaxVersions(versionCount);
        pendingGet.setTimeRange(0, cell.getTimestamp());

        return pendingGet;
    }

    // TODO Try to avoid to use the versionsAvg global attribute in here
    private void updateAvgNumberOfVersionsToFetchFromHBase(int versionsProcessed) {

        if (versionsProcessed > versionsAvg) {
            versionsAvg = versionsProcessed;
        } else {
            versionsAvg = ALPHA * versionsAvg + (1 - ALPHA) * versionsProcessed;
        }

    }

    private Optional<Long> tryToLocateCellCommitTimestamp(AbstractTransactionManager transactionManager,
                                                        Cell cell,
                                                        Map<Long, Long> commitCache)
            throws IOException {
        
        CommitTimestamp tentativeCommitTimestamp =
                transactionManager.locateCellCommitTimestamp(
                        cell.getTimestamp(),
                        new CommitTimestampLocatorImpl(
                                                       new HBaseCellId(table,
                                                                       CellUtil.cloneRow(cell),
                                                                       CellUtil.cloneFamily(cell),
                                                                       CellUtil.cloneQualifier(cell),
                                                                       cell.getTimestamp()),
                                                                       commitCache));
        
        switch(tentativeCommitTimestamp.getLocation()) {
        case COMMIT_TABLE:
            // If the commit timestamp is found in the persisted commit table,
            // that means the writing process of the shadow cell in the post
            // commit phase of the client probably failed, so we heal the shadow
            // cell with the right commit timestamp for avoiding further reads to
            // hit the storage
            healShadowCell(cell, tentativeCommitTimestamp.getValue());
        case CACHE:
        case SHADOW_CELL:
            return Optional.of(tentativeCommitTimestamp.getValue());
        case NOT_PRESENT:
            return Optional.absent();
        default:
            assert (false);
            return Optional.absent();
        }
    }

    void healShadowCell(Cell cell, long commitTimestamp) {
        Put put = new Put(CellUtil.cloneRow(cell));
        byte[] family = CellUtil.cloneFamily(cell);
        byte[] shadowCellQualifier = HBaseUtils.addShadowCellSuffix(CellUtil.cloneQualifier(cell));
        put.add(family, shadowCellQualifier, cell.getTimestamp(), Bytes.toBytes(commitTimestamp));
        try {
            healerTable.put(put);
        } catch (IOException e) {
            LOG.warn("Failed healing shadow cell for kv {}", cell, e);
        }
    }

    protected class TransactionalClientScanner implements ResultScanner {
        private HBaseTransaction state;
        private ResultScanner innerScanner;
        private int maxVersions;

        TransactionalClientScanner(HBaseTransaction state, Scan scan, int maxVersions)
                throws IOException {
            this.state = state;
            this.innerScanner = table.getScanner(scan);
            this.maxVersions = maxVersions;
        }


        @Override
        public Result next() throws IOException {
            List<Cell> filteredResult = Collections.emptyList();
            while (filteredResult.isEmpty()) {
                Result result = innerScanner.next();
                if (result == null) {
                    return null;
                }
                if (!result.isEmpty()) {
                    filteredResult = filterCellsForSnapshot(result.listCells(), state, maxVersions);
                }
            }
            return Result.create(filteredResult);
        }

        // In principle no need to override, copied from super.next(int) to make
        // sure it works even if super.next(int)
        // changes its implementation
        @Override
        public Result[] next(int nbRows) throws IOException {
            // Collect values to be returned here
            ArrayList<Result> resultSets = new ArrayList<Result>(nbRows);
            for (int i = 0; i < nbRows; i++) {
                Result next = next();
                if (next != null) {
                    resultSets.add(next);
                } else {
                    break;
                }
            }
            return resultSets.toArray(new Result[resultSets.size()]);
        }

        @Override
        public void close() {
            innerScanner.close();
        }

        @Override
        public Iterator<Result> iterator() {
            return innerScanner.iterator();
        }
    }

    /**
     * Delegates to {@link HTable#getTableName()}
     */
    public byte[] getTableName() {
        return table.getTableName();
    }

    /**
     * Delegates to {@link HTable#getConfiguration()}
     */
    public Configuration getConfiguration() {
        return table.getConfiguration();
    }

    /**
     * Delegates to {@link HTable#getTableDescriptor()}
     */
    public HTableDescriptor getTableDescriptor() throws IOException {
        return table.getTableDescriptor();
    }

    /**
     * Transactional version of {@link HTableInterface#exists(Get get)}
     */
    public boolean exists(Transaction transaction, Get get) throws IOException {
        Result result = get(transaction, get);
        return !result.isEmpty();
    }

    /* TODO What should we do with this methods???
     * @Override public void batch(Transaction transaction, List<? extends Row>
     * actions, Object[] results) throws IOException, InterruptedException {}
     * 
     * @Override public Object[] batch(Transaction transaction, List<? extends
     * Row> actions) throws IOException, InterruptedException {}
     * 
     * @Override public <R> void batchCallback(Transaction transaction, List<?
     * extends Row> actions, Object[] results, Callback<R> callback) throws
     * IOException, InterruptedException {}
     * 
     * @Override public <R> Object[] batchCallback(List<? extends Row> actions,
     * Callback<R> callback) throws IOException, InterruptedException {}
     */

    /**
     * Transactional version of {@link HTableInterface#get(List<Get> gets)}
     */
    public Result[] get(Transaction transaction, List<Get> gets) throws IOException {
        Result[] results = new Result[gets.size()];
        int i = 0;
        for (Get get : gets) {
            results[i++] = get(transaction, get);
        }
        return results;
    }

    /**
     * Transactional version of {@link HTableInterface#getScanner(byte[] family)}
     */
    public ResultScanner getScanner(Transaction transaction, byte[] family) throws IOException {
        Scan scan = new Scan();
        scan.addFamily(family);
        return getScanner(transaction, scan);
    }

    /**
     * Transactional version of {@link HTableInterface#getScanner(byte[] family, byte[] qualifier)}
     */
    public ResultScanner getScanner(Transaction transaction, byte[] family, byte[] qualifier)
            throws IOException {
        Scan scan = new Scan();
        scan.addColumn(family, qualifier);
        return getScanner(transaction, scan);
    }

    /**
     * Transactional version of {@link HTableInterface#put(List<Put> puts)}
     */
    public void put(Transaction transaction, List<Put> puts) throws IOException {
        for (Put put : puts) {
            put(transaction, put);
        }
    }

    /**
     * Transactional version of {@link HTableInterface#delete(List<Delete> deletes)}
     */
    public void delete(Transaction transaction, List<Delete> deletes) throws IOException {
        for (Delete delete : deletes) {
            delete(transaction, delete);
        }
    }

    /**
     * Provides access to the underliying HTable in order to configure it or to
     * perform unsafe (non-transactional) operations. The latter would break the
     * transactional guarantees of the whole system.
     *
     * @return The underlying HTable object
     */
    public HTableInterface getHTable() {
        return table;
    }

    /**
     * Releases any resources held or pending changes in internal buffers.
     *
     * @throws IOException
     *             if a remote or network exception occurs.
     */
    public void close() throws IOException {
        table.close();
        healerTable.close();
    }

    /**
     * Delegates to {@link HTable#setAutoFlush(boolean autoFlush)}
     */
    public void setAutoFlush(boolean autoFlush) {
        table.setAutoFlush(autoFlush, true);
    }

    /**
     * Delegates to {@link HTable#isAutoFlush()}
     */
    public boolean isAutoFlush() {
        return table.isAutoFlush();
    }

    /**
     * Delegates to {@link HTable.getWriteBufferSize()}
     */
    public long getWriteBufferSize() {
        return table.getWriteBufferSize();
    }

    /**
     * Delegates to {@link HTable.setWriteBufferSize()}
     */
    public void setWriteBufferSize(long writeBufferSize) throws IOException {
        table.setWriteBufferSize(writeBufferSize);
    }

    /**
     * Delegates to {@link HTable.flushCommits()}
     */
    public void flushCommits() throws IOException{
        table.flushCommits();
    }

    // ****************************************************************************************************************
    // Helper methods
    // ****************************************************************************************************************

    private void throwExceptionIfOpSetsTimerange(Get getOperation) {
        TimeRange tr = getOperation.getTimeRange();
        checkTimerangeIsSetToDefaultValuesOrThrowException(tr);
    }

    private void throwExceptionIfOpSetsTimerange(Scan scanOperation) {
        TimeRange tr = scanOperation.getTimeRange();
        checkTimerangeIsSetToDefaultValuesOrThrowException(tr);
    }

    private void checkTimerangeIsSetToDefaultValuesOrThrowException(TimeRange tr) {
        if (tr.getMin() != 0L || tr.getMax() != Long.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Timestamp/timerange not allowed in transactional user operations");
        }
    }

    private void throwExceptionIfOpSetsTimerange(Mutation userOperation) {
        if (userOperation.getTimeStamp() != HConstants.LATEST_TIMESTAMP) {
            throw new IllegalArgumentException(
                    "Timestamp not allowed in transactional user operations");
        }
    }

    private void throwExceptionIfTimestampSet(Cell cell) {
        if (cell.getTimestamp() != HConstants.LATEST_TIMESTAMP) {
            throw new IllegalArgumentException(
                    "Timestamp not allowed in transactional user operations");
        }
    }

    private HBaseTransaction enforceHBaseTransactionAsParam(Transaction tx) {
        if (tx instanceof HBaseTransaction) {
            return (HBaseTransaction) tx;
        } else {
            throw new IllegalArgumentException(
                    String.format("The transaction object passed %s is not an instance of HBaseTransaction",
                                  tx.getClass().getName()));
        }
    }

}