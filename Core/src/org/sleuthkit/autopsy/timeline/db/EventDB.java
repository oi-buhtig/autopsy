/*
 * Autopsy Forensic Browser
 *
 * Copyright 2013-15 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
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
 */
package org.sleuthkit.autopsy.timeline.db;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.joda.time.DateTimeZone;
import org.joda.time.Interval;
import org.joda.time.Period;
import org.sleuthkit.autopsy.casemodule.Case;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.autopsy.timeline.TimeLineController;
import org.sleuthkit.autopsy.timeline.datamodel.EventCluster;
import org.sleuthkit.autopsy.timeline.datamodel.TimeLineEvent;
import org.sleuthkit.autopsy.timeline.datamodel.eventtype.BaseTypes;
import org.sleuthkit.autopsy.timeline.datamodel.eventtype.EventType;
import org.sleuthkit.autopsy.timeline.datamodel.eventtype.RootEventType;
import static org.sleuthkit.autopsy.timeline.db.SQLHelper.useHashHitTablesHelper;
import static org.sleuthkit.autopsy.timeline.db.SQLHelper.useTagTablesHelper;
import org.sleuthkit.autopsy.timeline.filters.RootFilter;
import org.sleuthkit.autopsy.timeline.filters.TagsFilter;
import org.sleuthkit.autopsy.timeline.utils.RangeDivisionInfo;
import org.sleuthkit.autopsy.timeline.zooming.DescriptionLoD;
import org.sleuthkit.autopsy.timeline.zooming.EventTypeZoomLevel;
import org.sleuthkit.autopsy.timeline.zooming.ZoomParams;
import org.sleuthkit.datamodel.SleuthkitCase;
import org.sleuthkit.datamodel.Tag;
import org.sleuthkit.datamodel.TskData;
import org.sqlite.SQLiteJDBCLoader;

/**
 * Provides access to the Timeline SQLite database.
 *
 * This class borrows a lot of ideas and techniques from {@link  SleuthkitCase}.
 * Creating an abstract base class for SQLite databases, or using a higherlevel
 * persistence api may make sense in the future.
 */
public class EventDB {

    /**
     *
     * enum to represent keys stored in db_info table
     */
    private enum DBInfoKey {

        LAST_ARTIFACT_ID("last_artifact_id"), // NON-NLS
        LAST_OBJECT_ID("last_object_id"), // NON-NLS
        WAS_INGEST_RUNNING("was_ingest_running"); // NON-NLS

        private final String keyName;

        private DBInfoKey(String keyName) {
            this.keyName = keyName;
        }

        @Override
        public String toString() {
            return keyName;
        }
    }

    private static final org.sleuthkit.autopsy.coreutils.Logger LOGGER = Logger.getLogger(EventDB.class.getName());

    static {
        //make sure sqlite driver is loaded, possibly redundant
        try {
            Class.forName("org.sqlite.JDBC"); // NON-NLS
        } catch (ClassNotFoundException ex) {
            LOGGER.log(Level.SEVERE, "Failed to load sqlite JDBC driver", ex); // NON-NLS
        }
    }

    /**
     * public factory method. Creates and opens a connection to a database at
     * the given path. If a database does not already exist at that path, one is
     * created.
     *
     * @param autoCase the Autopsy {@link Case} the is events database is for.
     *
     * @return a new EventDB or null if there was an error.
     */
    public static EventDB getEventDB(Case autoCase) {
        try {
            return new EventDB(autoCase);
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE, "sql error creating database connection", ex); // NON-NLS
            return null;
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "error creating database connection", ex); // NON-NLS
            return null;
        }
    }

    private volatile Connection con;

    private final String dbPath;

    private PreparedStatement getDBInfoStmt;
    private PreparedStatement getEventByIDStmt;
    private PreparedStatement getMaxTimeStmt;
    private PreparedStatement getMinTimeStmt;
    private PreparedStatement getDataSourceIDsStmt;
    private PreparedStatement getHashSetNamesStmt;
    private PreparedStatement insertRowStmt;
    private PreparedStatement recordDBInfoStmt;
    private PreparedStatement insertHashSetStmt;
    private PreparedStatement insertHashHitStmt;
    private PreparedStatement insertTagStmt;
    private PreparedStatement deleteTagStmt;
    private PreparedStatement selectHashSetStmt;
    private PreparedStatement countAllEventsStmt;
    private PreparedStatement dropEventsTableStmt;
    private PreparedStatement dropHashSetHitsTableStmt;
    private PreparedStatement dropHashSetsTableStmt;
    private PreparedStatement dropTagsTableStmt;
    private PreparedStatement dropDBInfoTableStmt;
    private PreparedStatement selectNonArtifactEventIDsByObjectIDStmt;
    private PreparedStatement selectEventIDsBYObjectAndArtifactIDStmt;

    private final Set<PreparedStatement> preparedStatements = new HashSet<>();

    private final Lock DBLock = new ReentrantReadWriteLock(true).writeLock(); //using exclusive lock for all db ops for now

    private EventDB(Case autoCase) throws SQLException, Exception {
        //should this go into module output (or even cache, we should be able to rebuild it)?
        this.dbPath = Paths.get(autoCase.getCaseDirectory(), "events.db").toString(); //NON-NLS
        initializeDB();
    }

    @Override
    public void finalize() throws Throwable {
        try {
            closeDBCon();
        } finally {
            super.finalize();
        }
    }

    void closeDBCon() {
        if (con != null) {
            try {
                closeStatements();
                con.close();
            } catch (SQLException ex) {
                LOGGER.log(Level.WARNING, "Failed to close connection to evetns.db", ex); // NON-NLS
            }
        }
        con = null;
    }

    public Interval getSpanningInterval(Collection<Long> eventIDs) {
        DBLock.lock();
        try (Statement stmt = con.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT Min(time), Max(time) FROM events WHERE event_id IN (" + StringUtils.join(eventIDs, ", ") + ")");) { // NON-NLS
            while (rs.next()) {
                return new Interval(rs.getLong("Min(time)") * 1000, (rs.getLong("Max(time)") + 1) * 1000, DateTimeZone.UTC); // NON-NLS
            }
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE, "Error executing get spanning interval query.", ex); // NON-NLS
        } finally {
            DBLock.unlock();
        }
        return null;
    }

    EventTransaction beginTransaction() {
        return new EventTransaction();
    }

    void commitTransaction(EventTransaction tr) {
        if (tr.isClosed()) {
            throw new IllegalArgumentException("can't close already closed transaction"); // NON-NLS
        }
        tr.commit();
    }

    /**
     * @return the total number of events in the database or, -1 if there is an
     *         error.
     */
    int countAllEvents() {
        DBLock.lock();
        try (ResultSet rs = countAllEventsStmt.executeQuery()) { // NON-NLS
            while (rs.next()) {
                return rs.getInt("count"); // NON-NLS
            }
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE, "Error counting all events", ex);
        } finally {
            DBLock.unlock();
        }
        return -1;
    }

    /**
     * get the count of all events that fit the given zoom params organized by
     * the EvenType of the level spcified in the ZoomParams
     *
     * @param params the params that control what events to count and how to
     *               organize the returned map
     *
     * @return a map from event type( of the requested level) to event counts
     */
    Map<EventType, Long> countEventsByType(ZoomParams params) {
        if (params.getTimeRange() != null) {
            return countEventsByType(params.getTimeRange().getStartMillis() / 1000,
                    params.getTimeRange().getEndMillis() / 1000,
                    params.getFilter(), params.getTypeZoomLevel());
        } else {
            return Collections.emptyMap();
        }
    }

    /**
     * get a count of tagnames applied to the given event ids as a map from
     * tagname displayname to count of tag applications
     *
     * @param eventIDsWithTags the event ids to get the tag counts map for
     *
     * @return a map from tagname displayname to count of applications
     */
    Map<String, Long> getTagCountsByTagName(Set<Long> eventIDsWithTags) {
        HashMap<String, Long> counts = new HashMap<>();
        DBLock.lock();
        try (Statement createStatement = con.createStatement();
                ResultSet rs = createStatement.executeQuery("SELECT tag_name_display_name, COUNT(DISTINCT tag_id) AS count FROM tags"
                        + " WHERE event_id IN (" + StringUtils.join(eventIDsWithTags, ", ") + ")"
                        + " GROUP BY tag_name_id"
                        + " ORDER BY tag_name_display_name");) {
            while (rs.next()) {
                counts.put(rs.getString("tag_name_display_name"), rs.getLong("count"));
            }
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE, "Failed to get tag counts by tag name.", ex);
        } finally {
            DBLock.unlock();
        }
        return counts;
    }

    /**
     * drop the tables from this database and recreate them in order to start
     * over.
     */
    void reInitializeDB() {
        DBLock.lock();
        try {
            dropEventsTableStmt.executeUpdate();
            dropHashSetHitsTableStmt.executeUpdate();
            dropHashSetsTableStmt.executeUpdate();
            dropTagsTableStmt.executeUpdate();
            dropDBInfoTableStmt.executeUpdate();
            initializeDB();
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE, "could not drop old tables", ex); // NON-NLS
        } finally {
            DBLock.unlock();
        }
    }

    /**
     * drop only the tags table and rebuild it incase the tags have changed
     * while TL was not listening,
     */
    void reInitializeTags() {
        DBLock.lock();
        try {
            dropTagsTableStmt.executeUpdate();
            initializeTagsTable();
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE, "could not drop old tags table", ex); // NON-NLS
        } finally {
            DBLock.unlock();
        }
    }

    Interval getBoundingEventsInterval(Interval timeRange, RootFilter filter) {
        long start = timeRange.getStartMillis() / 1000;
        long end = timeRange.getEndMillis() / 1000;
        final String sqlWhere = SQLHelper.getSQLWhere(filter);
        DBLock.lock();
        try (Statement stmt = con.createStatement(); //can't use prepared statement because of complex where clause
                ResultSet rs = stmt.executeQuery(" SELECT (SELECT Max(time) FROM events " + useHashHitTablesHelper(filter) + useTagTablesHelper(filter) + " WHERE time <=" + start + " AND " + sqlWhere + ") AS start,"
                        + "(SELECT Min(time)  FROM events" + useHashHitTablesHelper(filter) + useTagTablesHelper(filter) + " WHERE time >= " + end + " AND " + sqlWhere + ") AS end")) { // NON-NLS
            while (rs.next()) {

                long start2 = rs.getLong("start"); // NON-NLS
                long end2 = rs.getLong("end"); // NON-NLS

                if (end2 == 0) {
                    end2 = getMaxTime();
                }
                return new Interval(start2 * 1000, (end2 + 1) * 1000, TimeLineController.getJodaTimeZone());
            }
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE, "Failed to get MIN time.", ex); // NON-NLS
        } finally {
            DBLock.unlock();
        }
        return null;
    }

    TimeLineEvent getEventById(Long eventID) {
        TimeLineEvent result = null;
        DBLock.lock();
        try {
            getEventByIDStmt.clearParameters();
            getEventByIDStmt.setLong(1, eventID);
            try (ResultSet rs = getEventByIDStmt.executeQuery()) {
                while (rs.next()) {
                    result = constructTimeLineEvent(rs);
                    break;
                }
            }
        } catch (SQLException sqlEx) {
            LOGGER.log(Level.SEVERE, "exception while querying for event with id = " + eventID, sqlEx); // NON-NLS
        } finally {
            DBLock.unlock();
        }
        return result;
    }

    Set<Long> getEventIDs(Interval timeRange, RootFilter filter) {
        return getEventIDs(timeRange.getStartMillis() / 1000, timeRange.getEndMillis() / 1000, filter);
    }

    Set<Long> getEventIDs(Long startTime, Long endTime, RootFilter filter) {
        if (Objects.equals(startTime, endTime)) {
            endTime++;
        }
        Set<Long> resultIDs = new HashSet<>();

        DBLock.lock();
        final String query = "SELECT events.event_id AS event_id FROM events" + useHashHitTablesHelper(filter) + useTagTablesHelper(filter) + " WHERE time >=  " + startTime + " AND time <" + endTime + " AND " + SQLHelper.getSQLWhere(filter); // NON-NLS
        try (Statement stmt = con.createStatement();
                ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                resultIDs.add(rs.getLong("event_id"));
            }

        } catch (SQLException sqlEx) {
            LOGGER.log(Level.SEVERE, "failed to execute query for event ids in range", sqlEx); // NON-NLS
        } finally {
            DBLock.unlock();
        }

        return resultIDs;
    }

    long getLastArtfactID() {
        return getDBInfo(DBInfoKey.LAST_ARTIFACT_ID, -1);
    }

    long getLastObjID() {
        return getDBInfo(DBInfoKey.LAST_OBJECT_ID, -1);
    }

    /**
     * this relies on the fact that no tskObj has ID 0 but 0 is the default
     * value for the datasource_id column in the events table.
     */
    boolean hasNewColumns() {
        return hasHashHitColumn() && hasDataSourceIDColumn() && hasTaggedColumn()
                && (getDataSourceIDs().isEmpty() == false);
    }

    Set<Long> getDataSourceIDs() {
        HashSet<Long> hashSet = new HashSet<>();
        DBLock.lock();
        try (ResultSet rs = getDataSourceIDsStmt.executeQuery()) {
            while (rs.next()) {
                long datasourceID = rs.getLong("datasource_id");
                hashSet.add(datasourceID);
            }
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE, "Failed to get MAX time.", ex); // NON-NLS
        } finally {
            DBLock.unlock();
        }
        return hashSet;
    }

    Map<Long, String> getHashSetNames() {
        Map<Long, String> hashSets = new HashMap<>();
        DBLock.lock();
        try (ResultSet rs = getHashSetNamesStmt.executeQuery();) {
            while (rs.next()) {
                long hashSetID = rs.getLong("hash_set_id");
                String hashSetName = rs.getString("hash_set_name");
                hashSets.put(hashSetID, hashSetName);
            }
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE, "Failed to get hash sets.", ex); // NON-NLS
        } finally {
            DBLock.unlock();
        }
        return Collections.unmodifiableMap(hashSets);
    }

    /**
     * @return maximum time in seconds from unix epoch
     */
    Long getMaxTime() {
        DBLock.lock();
        try (ResultSet rs = getMaxTimeStmt.executeQuery()) {
            while (rs.next()) {
                return rs.getLong("max"); // NON-NLS
            }
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE, "Failed to get MAX time.", ex); // NON-NLS
        } finally {
            DBLock.unlock();
        }
        return -1l;
    }

    /**
     * @return maximum time in seconds from unix epoch
     */
    Long getMinTime() {
        DBLock.lock();
        try (ResultSet rs = getMinTimeStmt.executeQuery()) {
            while (rs.next()) {
                return rs.getLong("min"); // NON-NLS
            }
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE, "Failed to get MIN time.", ex); // NON-NLS
        } finally {
            DBLock.unlock();
        }
        return -1l;
    }

    boolean getWasIngestRunning() {
        return getDBInfo(DBInfoKey.WAS_INGEST_RUNNING, 0) != 0;
    }

    /**
     * create the table and indices if they don't already exist
     *
     * @return the number of rows in the table , count > 0 indicating an
     *         existing table
     */
    final synchronized void initializeDB() {

        try {
            if (con == null || con.isClosed()) {
                con = DriverManager.getConnection("jdbc:sqlite:" + dbPath); // NON-NLS
            }
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE, "Failed to open connection to events.db", ex); // NON-NLS
            return;
        }
        try {
            configureDB();
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE, "problem accessing  database", ex); // NON-NLS
            return;
        }

        DBLock.lock();
        try {
            try (Statement stmt = con.createStatement()) {
                String sql = "CREATE TABLE if not exists db_info " // NON-NLS
                        + " ( key TEXT, " // NON-NLS
                        + " value INTEGER, " // NON-NLS
                        + "PRIMARY KEY (key))"; // NON-NLS
                stmt.execute(sql);
            } catch (SQLException ex) {
                LOGGER.log(Level.SEVERE, "problem creating db_info table", ex); // NON-NLS
            }

            try (Statement stmt = con.createStatement()) {
                String sql = "CREATE TABLE if not exists events " // NON-NLS
                        + " (event_id INTEGER PRIMARY KEY, " // NON-NLS
                        + " datasource_id INTEGER, " // NON-NLS
                        + " file_id INTEGER, " // NON-NLS
                        + " artifact_id INTEGER, " // NON-NLS
                        + " time INTEGER, " // NON-NLS
                        + " sub_type INTEGER, " // NON-NLS
                        + " base_type INTEGER, " // NON-NLS
                        + " full_description TEXT, " // NON-NLS
                        + " med_description TEXT, " // NON-NLS
                        + " short_description TEXT, " // NON-NLS
                        + " known_state INTEGER," //boolean // NON-NLS
                        + " hash_hit INTEGER," //boolean // NON-NLS
                        + " tagged INTEGER)"; //boolean // NON-NLS
                stmt.execute(sql);
            } catch (SQLException ex) {
                LOGGER.log(Level.SEVERE, "problem creating  database table", ex); // NON-NLS
            }

            if (hasDataSourceIDColumn() == false) {
                try (Statement stmt = con.createStatement()) {
                    String sql = "ALTER TABLE events ADD COLUMN datasource_id INTEGER"; // NON-NLS
                    stmt.execute(sql);
                } catch (SQLException ex) {
                    LOGGER.log(Level.SEVERE, "problem upgrading events table", ex); // NON-NLS
                }
            }
            if (hasTaggedColumn() == false) {
                try (Statement stmt = con.createStatement()) {
                    String sql = "ALTER TABLE events ADD COLUMN tagged INTEGER"; // NON-NLS
                    stmt.execute(sql);
                } catch (SQLException ex) {
                    LOGGER.log(Level.SEVERE, "problem upgrading events table", ex); // NON-NLS
                }
            }

            if (hasHashHitColumn() == false) {
                try (Statement stmt = con.createStatement()) {
                    String sql = "ALTER TABLE events ADD COLUMN hash_hit INTEGER"; // NON-NLS
                    stmt.execute(sql);
                } catch (SQLException ex) {
                    LOGGER.log(Level.SEVERE, "problem upgrading events table", ex); // NON-NLS
                }
            }

            try (Statement stmt = con.createStatement()) {
                String sql = "CREATE TABLE  if not exists hash_sets "
                        + "( hash_set_id INTEGER primary key,"
                        + " hash_set_name VARCHAR(255) UNIQUE NOT NULL)";
                stmt.execute(sql);
            } catch (SQLException ex) {
                LOGGER.log(Level.SEVERE, "problem creating hash_sets table", ex);
            }

            try (Statement stmt = con.createStatement()) {
                String sql = "CREATE TABLE  if not exists hash_set_hits "
                        + "(hash_set_id INTEGER REFERENCES hash_sets(hash_set_id) not null, "
                        + " event_id INTEGER REFERENCES events(event_id) not null, "
                        + " PRIMARY KEY (hash_set_id, event_id))";
                stmt.execute(sql);
            } catch (SQLException ex) {
                LOGGER.log(Level.SEVERE, "problem creating hash_set_hits table", ex);
            }

            initializeTagsTable();

            createIndex("events", Arrays.asList("datasource_id"));
            createIndex("events", Arrays.asList("event_id", "hash_hit"));
            createIndex("events", Arrays.asList("event_id", "tagged"));
            createIndex("events", Arrays.asList("file_id"));
            createIndex("events", Arrays.asList("file_id"));
            createIndex("events", Arrays.asList("artifact_id"));
            createIndex("events", Arrays.asList("time"));
            createIndex("events", Arrays.asList("sub_type", "time"));
            createIndex("events", Arrays.asList("base_type", "time"));
            createIndex("events", Arrays.asList("known_state"));

            try {
                insertRowStmt = prepareStatement(
                        "INSERT INTO events (datasource_id,file_id ,artifact_id, time, sub_type, base_type, full_description, med_description, short_description, known_state, hash_hit, tagged) " // NON-NLS
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"); // NON-NLS
                getHashSetNamesStmt = prepareStatement("SELECT hash_set_id, hash_set_name FROM hash_sets"); // NON-NLS
                getDataSourceIDsStmt = prepareStatement("SELECT DISTINCT datasource_id FROM events WHERE datasource_id != 0"); // NON-NLS
                getMaxTimeStmt = prepareStatement("SELECT Max(time) AS max FROM events"); // NON-NLS
                getMinTimeStmt = prepareStatement("SELECT Min(time) AS min FROM events"); // NON-NLS
                getEventByIDStmt = prepareStatement("SELECT * FROM events WHERE event_id =  ?"); // NON-NLS
                recordDBInfoStmt = prepareStatement("INSERT OR REPLACE INTO db_info (key, value) values (?, ?)"); // NON-NLS
                getDBInfoStmt = prepareStatement("SELECT value FROM db_info WHERE key = ?"); // NON-NLS
                insertHashSetStmt = prepareStatement("INSERT OR IGNORE INTO hash_sets (hash_set_name)  values (?)");
                selectHashSetStmt = prepareStatement("SELECT hash_set_id FROM hash_sets WHERE hash_set_name = ?");
                insertHashHitStmt = prepareStatement("INSERT OR IGNORE INTO hash_set_hits (hash_set_id, event_id) values (?,?)");
                insertTagStmt = prepareStatement("INSERT OR IGNORE INTO tags (tag_id, tag_name_id,tag_name_display_name, event_id) values (?,?,?,?)");
                deleteTagStmt = prepareStatement("DELETE FROM tags WHERE tag_id = ?");
                countAllEventsStmt = prepareStatement("SELECT count(*) AS count FROM events");
                dropEventsTableStmt = prepareStatement("DROP TABLE IF EXISTS events");
                dropHashSetHitsTableStmt = prepareStatement("DROP TABLE IF EXISTS hash_set_hits");
                dropHashSetsTableStmt = prepareStatement("DROP TABLE IF EXISTS hash_sets");
                dropTagsTableStmt = prepareStatement("DROP TABLE IF EXISTS tags");
                dropDBInfoTableStmt = prepareStatement("DROP TABLE IF EXISTS db_ino");
                selectNonArtifactEventIDsByObjectIDStmt = prepareStatement("SELECT event_id FROM events WHERE file_id == ? AND artifact_id IS NULL");
                selectEventIDsBYObjectAndArtifactIDStmt = prepareStatement("SELECT event_id FROM events WHERE file_id == ? AND artifact_id = ?");
            } catch (SQLException sQLException) {
                LOGGER.log(Level.SEVERE, "failed to prepareStatment", sQLException); // NON-NLS
            }
        } finally {
            DBLock.unlock();
        }
    }

    /**
     * create the tags table if it doesn't already exist. This is broken out as
     * a separate method so it can be used by {@link #reInitializeTags() }
     */
    private void initializeTagsTable() {
        try (Statement stmt = con.createStatement()) {
            String sql = "CREATE TABLE IF NOT EXISTS tags "
                    + "(tag_id INTEGER NOT NULL,"
                    + " tag_name_id INTEGER NOT NULL, "
                    + " tag_name_display_name TEXT NOT NULL, "
                    + " event_id INTEGER REFERENCES events(event_id) NOT NULL, "
                    + " PRIMARY KEY (event_id, tag_name_id))";
            stmt.execute(sql);
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE, "problem creating tags table", ex);
        }
    }

    /**
     *
     * @param tableName  the value of tableName
     * @param columnList the value of columnList
     */
    private void createIndex(final String tableName, final List<String> columnList) {
        String indexColumns = columnList.stream().collect(Collectors.joining(",", "(", ")"));
        String indexName = tableName + StringUtils.join(columnList, "_") + "_idx";
        try (Statement stmt = con.createStatement()) {

            String sql = "CREATE INDEX IF NOT EXISTS " + indexName + " ON " + tableName + indexColumns; // NON-NLS
            stmt.execute(sql);
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE, "problem creating index " + indexName, ex); // NON-NLS
        }
    }

    /**
     * @param dbColumn the value of dbColumn
     *
     * @return the boolean
     */
    private boolean hasDBColumn(@Nonnull final String dbColumn) {
        try (Statement stmt = con.createStatement()) {

            ResultSet executeQuery = stmt.executeQuery("PRAGMA table_info(events)");
            while (executeQuery.next()) {
                if (dbColumn.equals(executeQuery.getString("name"))) {
                    return true;
                }
            }
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE, "problem executing pragma", ex); // NON-NLS
        }
        return false;
    }

    private boolean hasDataSourceIDColumn() {
        return hasDBColumn("datasource_id");
    }

    private boolean hasTaggedColumn() {
        return hasDBColumn("tagged");
    }

    private boolean hasHashHitColumn() {
        return hasDBColumn("hash_hit");
    }

    void insertEvent(long time, EventType type, long datasourceID, long objID,
            Long artifactID, String fullDescription, String medDescription,
            String shortDescription, TskData.FileKnown known, Set<String> hashSets, List<? extends Tag> tags) {

        EventTransaction transaction = beginTransaction();
        insertEvent(time, type, datasourceID, objID, artifactID, fullDescription, medDescription, shortDescription, known, hashSets, tags, transaction);
        commitTransaction(transaction);
    }

    /**
     * use transactions to update files
     *
     * @param f
     * @param transaction
     */
    void insertEvent(long time, EventType type, long datasourceID, long objID,
            Long artifactID, String fullDescription, String medDescription,
            String shortDescription, TskData.FileKnown known, Set<String> hashSetNames,
            List<? extends Tag> tags, EventTransaction transaction) {

        if (transaction.isClosed()) {
            throw new IllegalArgumentException("can't update database with closed transaction"); // NON-NLS
        }
        int typeNum = RootEventType.allTypes.indexOf(type);
        int superTypeNum = type.getSuperType().ordinal();

        DBLock.lock();
        try {

            //"INSERT INTO events (datasource_id,file_id ,artifact_id, time, sub_type, base_type, full_description, med_description, short_description, known_state, hashHit, tagged) " 
            insertRowStmt.clearParameters();
            insertRowStmt.setLong(1, datasourceID);
            insertRowStmt.setLong(2, objID);
            if (artifactID != null) {
                insertRowStmt.setLong(3, artifactID);
            } else {
                insertRowStmt.setNull(3, Types.NULL);
            }
            insertRowStmt.setLong(4, time);

            if (typeNum != -1) {
                insertRowStmt.setInt(5, typeNum);
            } else {
                insertRowStmt.setNull(5, Types.INTEGER);
            }

            insertRowStmt.setInt(6, superTypeNum);
            insertRowStmt.setString(7, fullDescription);
            insertRowStmt.setString(8, medDescription);
            insertRowStmt.setString(9, shortDescription);

            insertRowStmt.setByte(10, known == null ? TskData.FileKnown.UNKNOWN.getFileKnownValue() : known.getFileKnownValue());

            insertRowStmt.setInt(11, hashSetNames.isEmpty() ? 0 : 1);
            insertRowStmt.setInt(12, tags.isEmpty() ? 0 : 1);

            insertRowStmt.executeUpdate();

            try (ResultSet generatedKeys = insertRowStmt.getGeneratedKeys()) {
                while (generatedKeys.next()) {
                    long eventID = generatedKeys.getLong("last_insert_rowid()");
                    for (String name : hashSetNames) {

                        // "insert or ignore into hash_sets (hash_set_name)  values (?)"
                        insertHashSetStmt.setString(1, name);
                        insertHashSetStmt.executeUpdate();

                        //TODO: use nested select to get hash_set_id rather than seperate statement/query ?
                        //"select hash_set_id from hash_sets where hash_set_name = ?"
                        selectHashSetStmt.setString(1, name);
                        try (ResultSet rs = selectHashSetStmt.executeQuery()) {
                            while (rs.next()) {
                                int hashsetID = rs.getInt("hash_set_id");
                                //"insert or ignore into hash_set_hits (hash_set_id, obj_id) values (?,?)";
                                insertHashHitStmt.setInt(1, hashsetID);
                                insertHashHitStmt.setLong(2, eventID);
                                insertHashHitStmt.executeUpdate();
                                break;
                            }
                        }
                    }
                    for (Tag tag : tags) {
                        //could this be one insert?  is there a performance win?
                        insertTag(tag, eventID);
                    }
                    break;
                }
            }

        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE, "failed to insert event", ex); // NON-NLS
        } finally {
            DBLock.unlock();
        }
    }

    /**
     * mark any events with the given object and artifact ids as tagged, and
     * record the tag it self.
     *
     * @param objectID   the obj_id that this tag applies to, the id of the
     *                   content that the artifact is derived from for artifact
     *                   tags
     * @param artifactID the artifact_id that this tag applies to, or null if
     *                   this is a content tag
     * @param tag        the tag that should be inserted
     *
     * @return the event ids that match the object/artifact pair
     */
    Set<Long> addTag(long objectID, @Nullable Long artifactID, Tag tag) {
        DBLock.lock();
        try {
            Set<Long> eventIDs = markEventsTagged(objectID, artifactID, true);
            for (Long eventID : eventIDs) {
                insertTag(tag, eventID);
            }
            return eventIDs;
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE, "failed to add tag to event", ex); // NON-NLS
        } finally {
            DBLock.unlock();
        }
        return Collections.emptySet();
    }

    /**
     * insert this tag into the db
     * <p>
     * NOTE: does not lock the db, must be called form inside a
     * DBLock.lock/unlock pair
     *
     * @param tag     the tag to insert
     * @param eventID the event id that this tag is applied to.
     *
     * @throws SQLException if there was a problem executing insert
     */
    private void insertTag(Tag tag, long eventID) throws SQLException {

        //"INSERT OR IGNORE INTO tags (tag_id, tag_name_id,tag_name_display_name, event_id) values (?,?,?,?)"
        insertTagStmt.clearParameters();
        insertTagStmt.setLong(1, tag.getId());
        insertTagStmt.setLong(2, tag.getName().getId());
        insertTagStmt.setString(3, tag.getName().getDisplayName());
        insertTagStmt.setLong(4, eventID);
        insertTagStmt.executeUpdate();
    }

    /**
     * mark any events with the given object and artifact ids as tagged, and
     * record the tag it self.
     *
     * @param objectID    the obj_id that this tag applies to, the id of the
     *                    content that the artifact is derived from for artifact
     *                    tags
     * @param artifactID  the artifact_id that this tag applies to, or null if
     *                    this is a content tag
     * @param tag         the tag that should be deleted
     * @param stillTagged true if there are other tags still applied to this
     *                    event in autopsy
     *
     * @return the event ids that match the object/artifact pair
     */
    Set<Long> deleteTag(long objectID, @Nullable Long artifactID, long tagID, boolean stillTagged) {
        DBLock.lock();
        try {
            //"DELETE FROM tags WHERE tag_id = ?
            deleteTagStmt.clearParameters();
            deleteTagStmt.setLong(1, tagID);
            deleteTagStmt.executeUpdate();

            return markEventsTagged(objectID, artifactID, stillTagged);
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE, "failed to add tag to event", ex); // NON-NLS
        } finally {
            DBLock.unlock();
        }
        return Collections.emptySet();
    }

    /**
     * mark any events with the given object and artifact ids as tagged, and
     * record the tag it self.
     * <p>
     * NOTE: does not lock the db, must be called form inside a
     * DBLock.lock/unlock pair
     *
     * @param objectID   the obj_id that this tag applies to, the id of the
     *                   content that the artifact is derived from for artifact
     *                   tags
     * @param artifactID the artifact_id that this tag applies to, or null if
     *                   this is a content tag
     * @param tagged     true to mark the matching events tagged, false to mark
     *                   them as untagged
     *
     * @return the event ids that match the object/artifact pair
     *
     * @throws SQLException if there is an error marking the events as
     *                      (un)taggedS
     */
    private Set<Long> markEventsTagged(long objectID, @Nullable Long artifactID, boolean tagged) throws SQLException {

        PreparedStatement selectStmt;
        if (Objects.isNull(artifactID)) {
            //"SELECT event_id FROM events WHERE file_id == ? AND artifact_id IS NULL"
            selectNonArtifactEventIDsByObjectIDStmt.clearParameters();
            selectNonArtifactEventIDsByObjectIDStmt.setLong(1, objectID);
            selectStmt = selectNonArtifactEventIDsByObjectIDStmt;
        } else {
            //"SELECT event_id FROM events WHERE file_id == ? AND artifact_id = ?"
            selectEventIDsBYObjectAndArtifactIDStmt.clearParameters();
            selectEventIDsBYObjectAndArtifactIDStmt.setLong(1, objectID);
            selectEventIDsBYObjectAndArtifactIDStmt.setLong(2, artifactID);
            selectStmt = selectEventIDsBYObjectAndArtifactIDStmt;
        }

        HashSet<Long> eventIDs = new HashSet<>();
        try (ResultSet executeQuery = selectStmt.executeQuery();) {
            while (executeQuery.next()) {
                eventIDs.add(executeQuery.getLong("event_id"));
            }
        }

        //update tagged state for all event with selected ids
        try (Statement updateStatement = con.createStatement();) {
            updateStatement.executeUpdate("UPDATE events SET tagged = " + (tagged ? 1 : 0)
                    + " WHERE event_id IN (" + StringUtils.join(eventIDs, ",") + ")");
        }

        return eventIDs;
    }

    void recordLastArtifactID(long lastArtfID) {
        recordDBInfo(DBInfoKey.LAST_ARTIFACT_ID, lastArtfID);
    }

    void recordLastObjID(Long lastObjID) {
        recordDBInfo(DBInfoKey.LAST_OBJECT_ID, lastObjID);
    }

    void recordWasIngestRunning(boolean wasIngestRunning) {
        recordDBInfo(DBInfoKey.WAS_INGEST_RUNNING, (wasIngestRunning ? 1 : 0));
    }

    void rollBackTransaction(EventTransaction trans) {
        trans.rollback();
    }

    private void closeStatements() throws SQLException {
        for (PreparedStatement pStmt : preparedStatements) {
            pStmt.close();
        }
    }

    private void configureDB() throws SQLException {
        DBLock.lock();
        //this should match Sleuthkit db setup
        try (Statement statement = con.createStatement()) {
            //reduce i/o operations, we have no OS crash recovery anyway
            statement.execute("PRAGMA synchronous = OFF;"); // NON-NLS
            //we don't use this feature, so turn it off for minimal speed up on queries
            //this is deprecated and not recomended
            statement.execute("PRAGMA count_changes = OFF;"); // NON-NLS
            //this made a big difference to query speed
            statement.execute("PRAGMA temp_store = MEMORY"); // NON-NLS
            //this made a modest improvement in query speeds
            statement.execute("PRAGMA cache_size = 50000"); // NON-NLS
            //we never delete anything so...
            statement.execute("PRAGMA auto_vacuum = 0"); // NON-NLS
            //allow to query while in transaction - no need read locks
            statement.execute("PRAGMA read_uncommitted = True;"); // NON-NLS
        } finally {
            DBLock.unlock();
        }

        try {
            LOGGER.log(Level.INFO, String.format("sqlite-jdbc version %s loaded in %s mode", // NON-NLS
                    SQLiteJDBCLoader.getVersion(), SQLiteJDBCLoader.isNativeMode()
                            ? "native" : "pure-java")); // NON-NLS
        } catch (Exception exception) {
            LOGGER.log(Level.SEVERE, "Failed to determine if sqlite-jdbc is loaded in native or pure-java mode.", exception);
        }
    }

    private TimeLineEvent constructTimeLineEvent(ResultSet rs) throws SQLException {
        return new TimeLineEvent(rs.getLong("event_id"),
                rs.getLong("datasource_id"),
                rs.getLong("file_id"),
                rs.getLong("artifact_id"),
                rs.getLong("time"), RootEventType.allTypes.get(rs.getInt("sub_type")),
                rs.getString("full_description"),
                rs.getString("med_description"),
                rs.getString("short_description"),
                TskData.FileKnown.valueOf(rs.getByte("known_state")),
                rs.getInt("hash_hit") != 0,
                rs.getInt("tagged") != 0);
    }

    /**
     * count all the events with the given options and return a map organizing
     * the counts in a hierarchy from date > eventtype> count
     *
     * @param startTime events before this time will be excluded (seconds from
     *                  unix epoch)
     * @param endTime   events at or after this time will be excluded (seconds
     *                  from unix epoch)
     * @param filter    only events that pass this filter will be counted
     * @param zoomLevel only events of this type or a subtype will be counted
     *                  and the counts will be organized into bins for each of
     *                  the subtypes of the given event type
     *
     * @return a map organizing the counts in a hierarchy from date > eventtype>
     *         count
     */
    private Map<EventType, Long> countEventsByType(Long startTime, Long endTime, RootFilter filter, EventTypeZoomLevel zoomLevel) {
        if (Objects.equals(startTime, endTime)) {
            endTime++;
        }

        Map<EventType, Long> typeMap = new HashMap<>();

        //do we want the root or subtype column of the databse
        final boolean useSubTypes = (zoomLevel == EventTypeZoomLevel.SUB_TYPE);

        //get some info about the range of dates requested
        final String queryString = "SELECT count(DISTINCT events.event_id) AS count, " + typeColumnHelper(useSubTypes)
                + " FROM events" + useHashHitTablesHelper(filter) + useTagTablesHelper(filter) + " WHERE time >= " + startTime + " AND time < " + endTime + " AND " + SQLHelper.getSQLWhere(filter) // NON-NLS
                + " GROUP BY " + typeColumnHelper(useSubTypes); // NON-NLS

        DBLock.lock();
        try (Statement stmt = con.createStatement();
                ResultSet rs = stmt.executeQuery(queryString);) {
            while (rs.next()) {
                EventType type = useSubTypes
                        ? RootEventType.allTypes.get(rs.getInt("sub_type"))
                        : BaseTypes.values()[rs.getInt("base_type")];

                typeMap.put(type, rs.getLong("count")); // NON-NLS
            }

        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "Error getting count of events from db.", ex); // NON-NLS
        } finally {
            DBLock.unlock();
        }
        return typeMap;
    }

    /**
     * get a list of {@link EventCluster}s, clustered according to the given
     * zoom paramaters.
     *
     * @param params the zoom params that determine the zooming, filtering and
     *               clustering.
     *
     * @return a list of aggregate events within the given timerange, that pass
     *         the supplied filter, aggregated according to the given event type
     *         and description zoom levels
     */
    List<EventCluster> getClusteredEvents(ZoomParams params) {
        //unpack params
        Interval timeRange = params.getTimeRange();
        RootFilter filter = params.getFilter();
        DescriptionLoD descriptionLOD = params.getDescriptionLOD();
        EventTypeZoomLevel typeZoomLevel = params.getTypeZoomLevel();

        //ensure length of querried interval is not 0
        long start = timeRange.getStartMillis() / 1000;
        long end = timeRange.getEndMillis() / 1000;
        if (start == end) {
            end++;
        }
        //get some info about the time range requested
        RangeDivisionInfo rangeInfo = RangeDivisionInfo.getRangeDivisionInfo(timeRange);

        //build dynamic parts of query
        String strfTimeFormat = SQLHelper.getStrfTimeFormat(rangeInfo);
        String descriptionColumn = SQLHelper.getDescriptionColumn(descriptionLOD);
        final boolean useSubTypes = typeZoomLevel.equals(EventTypeZoomLevel.SUB_TYPE);
        String timeZone = TimeLineController.getTimeZone().get().equals(TimeZone.getDefault()) ? ", 'localtime'" : "";  // NON-NLS
        String typeColumn = typeColumnHelper(useSubTypes);

        //compose query string, new-lines only for nicer formatting if printing the entire query
        String query = "SELECT strftime('" + strfTimeFormat + "',time , 'unixepoch'" + timeZone + ") AS interval," // NON-NLS
                + "\n group_concat(events.event_id) as event_ids,"
                + "\n group_concat(CASE WHEN hash_hit = 1 THEN events.event_id ELSE NULL END) as hash_hits,"
                + "\n group_concat(CASE WHEN tagged = 1 THEN events.event_id ELSE NULL END) as taggeds,"
                + "\n min(time), max(time),  " + typeColumn + ", " + descriptionColumn // NON-NLS
                + "\n FROM events" + useHashHitTablesHelper(filter) + useTagTablesHelper(filter) // NON-NLS
                + "\n WHERE time >= " + start + " AND time < " + end + " AND " + SQLHelper.getSQLWhere(filter) // NON-NLS
                + "\n GROUP BY interval, " + typeColumn + " , " + descriptionColumn // NON-NLS
                + "\n ORDER BY min(time)"; // NON-NLS

        System.out.println(query);
        // perform query and map results to AggregateEvent objects
        List<EventCluster> events = new ArrayList<>();

        DBLock.lock();

        try (Statement createStatement = con.createStatement();
                ResultSet rs = createStatement.executeQuery(query)) {
            while (rs.next()) {
                events.add(eventClusterHelper(rs, useSubTypes, descriptionLOD, filter.getTagsFilter()));
            }
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE, "Failed to get aggregate events with query: " + query, ex); // NON-NLS
        } finally {
            DBLock.unlock();
        }

        return mergeEventClusters(rangeInfo.getPeriodSize().getPeriod(), events);
    }

    /**
     * map a single row in a ResultSet to an EventCluster
     *
     * @param rs             the result set whose current row should be mapped
     * @param useSubTypes    use the sub_type column if true, else use the
     *                       base_type column
     * @param descriptionLOD the description level of detail for this event
     *
     * @return an AggregateEvent corresponding to the current row in the given
     *         result set
     *
     * @throws SQLException
     */
    private EventCluster eventClusterHelper(ResultSet rs, boolean useSubTypes, DescriptionLoD descriptionLOD, TagsFilter filter) throws SQLException {
        Interval interval = new Interval(rs.getLong("min(time)") * 1000, rs.getLong("max(time)") * 1000, TimeLineController.getJodaTimeZone());// NON-NLS
        String eventIDsString = rs.getString("event_ids");// NON-NLS
        Set<Long> eventIDs = SQLHelper.unGroupConcat(eventIDsString, Long::valueOf);
        String description = rs.getString(SQLHelper.getDescriptionColumn(descriptionLOD));
        EventType type = useSubTypes ? RootEventType.allTypes.get(rs.getInt("sub_type")) : BaseTypes.values()[rs.getInt("base_type")];// NON-NLS

        Set<Long> hashHits = SQLHelper.unGroupConcat(rs.getString("hash_hits"), Long::valueOf);
        Set<Long> tagged = SQLHelper.unGroupConcat(rs.getString("taggeds"), Long::valueOf);

        return new EventCluster(interval, type, eventIDs, hashHits, tagged,
                description, descriptionLOD);
    }

    /**
     * merge the events in the given list if they are within the same period
     * General algorithm is as follows:
     *
     * 1) sort them into a map from (type, description)-> List<aggevent>
     * 2) for each key in map, merge the events and accumulate them in a list to
     * return
     *
     * @param timeUnitLength
     * @param preMergedEvents
     *
     * @return
     */
    static private List<EventCluster> mergeEventClusters(Period timeUnitLength, List<EventCluster> preMergedEvents) {

        //effectively map from type to (map from description to events)
        Map<EventType, SetMultimap< String, EventCluster>> typeMap = new HashMap<>();

        for (EventCluster aggregateEvent : preMergedEvents) {
            typeMap.computeIfAbsent(aggregateEvent.getEventType(), eventType -> HashMultimap.create())
                    .put(aggregateEvent.getDescription(), aggregateEvent);
        }
        //result list to return
        ArrayList<EventCluster> aggEvents = new ArrayList<>();

        //For each (type, description) key, merge agg events
        for (SetMultimap<String, EventCluster> descrMap : typeMap.values()) {
            //for each description ...
            for (String descr : descrMap.keySet()) {
                //run through the sorted events, merging together adjacent events
                Iterator<EventCluster> iterator = descrMap.get(descr).stream()
                        .sorted(Comparator.comparing(event -> event.getSpan().getStartMillis()))
                        .iterator();
                EventCluster current = iterator.next();
                while (iterator.hasNext()) {
                    EventCluster next = iterator.next();
                    Interval gap = current.getSpan().gap(next.getSpan());

                    //if they overlap or gap is less one quarter timeUnitLength
                    //TODO: 1/4 factor is arbitrary. review! -jm
                    if (gap == null || gap.toDuration().getMillis() <= timeUnitLength.toDurationFrom(gap.getStart()).getMillis() / 4) {
                        //merge them
                        current = EventCluster.merge(current, next);
                    } else {
                        //done merging into current, set next as new current
                        aggEvents.add(current);
                        current = next;
                    }
                }
                aggEvents.add(current);
            }
        }
        return aggEvents;
    }

    private static String typeColumnHelper(final boolean useSubTypes) {
        return useSubTypes ? "sub_type" : "base_type";
    }

    private long getDBInfo(DBInfoKey key, long defaultValue) {
        DBLock.lock();
        try {
            getDBInfoStmt.setString(1, key.toString());

            try (ResultSet rs = getDBInfoStmt.executeQuery()) {
                long result = defaultValue;
                while (rs.next()) {
                    result = rs.getLong("value"); // NON-NLS
                }
                return result;
            } catch (SQLException ex) {
                LOGGER.log(Level.SEVERE, "failed to read key: " + key + " from db_info", ex); // NON-NLS
            } finally {
                DBLock.unlock();
            }
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE, "failed to set key: " + key + " on getDBInfoStmt ", ex); // NON-NLS
        }

        return defaultValue;
    }

    private PreparedStatement prepareStatement(String queryString) throws SQLException {
        PreparedStatement prepareStatement = con.prepareStatement(queryString);
        preparedStatements.add(prepareStatement);
        return prepareStatement;
    }

    private void recordDBInfo(DBInfoKey key, long value) {
        DBLock.lock();
        try {
            recordDBInfoStmt.setString(1, key.toString());
            recordDBInfoStmt.setLong(2, value);
            recordDBInfoStmt.executeUpdate();
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE, "failed to set dbinfo  key: " + key + " value: " + value, ex); // NON-NLS
        } finally {
            DBLock.unlock();

        }
    }

    /**
     * inner class that can reference access database connection
     */
    public class EventTransaction {

        private boolean closed = false;

        /**
         * factory creation method
         *
         * @param con the {@link  ava.sql.Connection}
         *
         * @return a LogicalFileTransaction for the given connection
         *
         * @throws SQLException
         */
        private EventTransaction() {

            //get the write lock, released in close()
            DBLock.lock();
            try {
                con.setAutoCommit(false);

            } catch (SQLException ex) {
                LOGGER.log(Level.SEVERE, "failed to set auto-commit to to false", ex); // NON-NLS
            }

        }

        private void rollback() {
            if (!closed) {
                try {
                    con.rollback();

                } catch (SQLException ex1) {
                    LOGGER.log(Level.SEVERE, "Exception while attempting to rollback!!", ex1); // NON-NLS
                } finally {
                    close();
                }
            }
        }

        private void commit() {
            if (!closed) {
                try {
                    con.commit();
                    // make sure we close before we update, bc they'll need locks
                    close();

                } catch (SQLException ex) {
                    LOGGER.log(Level.SEVERE, "Error commiting events.db.", ex); // NON-NLS
                    rollback();
                }
            }
        }

        private void close() {
            if (!closed) {
                try {
                    con.setAutoCommit(true);
                } catch (SQLException ex) {
                    LOGGER.log(Level.SEVERE, "Error setting auto-commit to true.", ex); // NON-NLS
                } finally {
                    closed = true;

                    DBLock.unlock();
                }
            }
        }

        public Boolean isClosed() {
            return closed;
        }
    }
}
