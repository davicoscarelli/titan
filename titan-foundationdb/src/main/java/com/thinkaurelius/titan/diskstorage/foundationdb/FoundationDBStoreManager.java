package com.thinkaurelius.titan.diskstorage.foundationdb;

import com.foundationdb.Database;
import com.foundationdb.FDB;
import com.foundationdb.FDBException;
import com.foundationdb.Transaction;
import com.foundationdb.directory.DirectoryLayer;
import com.foundationdb.directory.DirectorySubspace;
import com.thinkaurelius.titan.diskstorage.BaseTransactionConfig;
import com.thinkaurelius.titan.diskstorage.StorageException;
import com.thinkaurelius.titan.diskstorage.TemporaryStorageException;
import com.thinkaurelius.titan.diskstorage.common.AbstractStoreManager;
import com.thinkaurelius.titan.diskstorage.configuration.ConfigOption;
import com.thinkaurelius.titan.diskstorage.configuration.Configuration;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.KeyRange;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.StandardStoreFeatures;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.StoreFeatures;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.StoreTransaction;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.keyvalue.KVMutation;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.keyvalue.OrderedKeyValueStoreManager;
import com.thinkaurelius.titan.graphdb.configuration.GraphDatabaseConfiguration;
import static com.thinkaurelius.titan.graphdb.configuration.GraphDatabaseConfiguration.STORAGE_NS;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FoundationDBStoreManager extends AbstractStoreManager implements OrderedKeyValueStoreManager {

    public static final ConfigOption<String> DATABASE_NAME = new ConfigOption<String>(STORAGE_NS,"tablename",
            "Name of database",
            ConfigOption.Type.MASKABLE, "titan");
    public static final ConfigOption<String> CLUSTER_FILE = new ConfigOption<String>(STORAGE_NS,"clusterfile",
            "FDB cluster file override",
            ConfigOption.Type.MASKABLE, "NONE");

    private final Database db;
    private final ConcurrentHashMap<String, FoundationDBKeyValueStore> openStores;
    private final StoreFeatures features;

    public final String dbname;
    public final String clusterFile;

    private final DirectorySubspace directory;

    public FoundationDBStoreManager(Configuration config) {
        super(config);

        dbname = config.get(DATABASE_NAME);
        clusterFile = config.get(CLUSTER_FILE);

        FDB fdb = FDB.selectAPIVersion(200);
        if(clusterFile.equals("NONE")) {
            db = fdb.open();
        }
        else {
            db = fdb.open(clusterFile);
        }

        directory = new DirectoryLayer().createOrOpen(db, Arrays.asList(dbname)).get();

        openStores = new ConcurrentHashMap<String, FoundationDBKeyValueStore>();

        features = new StandardStoreFeatures.Builder()
            .unorderedScan(true)
            .orderedScan(true)
            .batchMutation(true)
         /* .multiQuery(true) */
            .transactional(true)
            .keyConsistent(GraphDatabaseConfiguration.buildConfiguration())
            .locking(false)
            .keyOrdered(true)
            .distributed(true)
            .build();
    }

    @Override
    public FoundationDBKeyValueStore openDatabase(String name) throws StorageException {
        FoundationDBKeyValueStore kv = openStores.get(name);

        if (kv == null) {
            DirectorySubspace subspace = directory.createOrOpen(db, Arrays.asList(name)).get();
            FoundationDBKeyValueStore newkv = new FoundationDBKeyValueStore(name, subspace, this);
            kv = openStores.putIfAbsent(name, newkv);

            if (kv == null) kv = newkv;
        }

        return kv;
    }

    @Override
    public void mutateMany(Map<String, KVMutation> mutations, StoreTransaction txh) throws StorageException {
        for (Map.Entry<String, KVMutation> entry: mutations.entrySet()) {
            FoundationDBKeyValueStore store = openDatabase(entry.getKey());
            store.mutate(entry.getValue(), txh);
        }
    }

    @Override
    public StoreTransaction beginTransaction(BaseTransactionConfig config) throws StorageException {
        Transaction tr = db.createTransaction();
        return new FoundationDBTransaction(tr, config);
    }

    public void removeStore(FoundationDBKeyValueStore kv) {
        openStores.remove(kv.getName());
    }

    @Override
    public void close() throws StorageException {
        openStores.clear();
        db.dispose();
    }

    @Override
    public void clearStorage() throws StorageException {
        try {
            directory.remove(db).get();
            close();
        }
        catch (FDBException e) {
            throw new TemporaryStorageException(e.getMessage(), e.getCause());
        }
    }

    @Override
    public String getName() {
        return getClass().getSimpleName() + ":" + dbname;
    }

    @Override
    public StoreFeatures getFeatures() {
        return features;
    }

    @Override
    public List<KeyRange> getLocalKeyPartition() throws StorageException {
        throw new UnsupportedOperationException();
    }

}