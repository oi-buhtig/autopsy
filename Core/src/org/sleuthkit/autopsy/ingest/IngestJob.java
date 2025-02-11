/*
 * Autopsy Forensic Browser
 * 
 * Copyright 2014-2015 Basis Technology Corp.
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
package org.sleuthkit.autopsy.ingest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.sleuthkit.datamodel.Content;

/**
 * Runs a collection of data sources through a set of ingest modules specified
 * via ingest job settings.
 * <p>
 * This class is thread-safe.
 */
public final class IngestJob {

    private static final AtomicLong nextId = new AtomicLong(0L);
    private final long id;
    private final Map<Long, DataSourceIngestJob> dataSourceJobs;
    private final AtomicInteger incompleteJobsCount;
    private boolean started;  // Guarded by this
    private volatile boolean cancelled;

    /**
     * Constructs an ingest job that runs a collection of data sources through a
     * set of ingest modules specified via ingest job settings.
     *
     * @param dataSources The data sources to be ingested.
     * @param settings    The ingest job settings.
     * @param doUI        Whether or not this job should use progress bars,
     *                    message boxes for errors, etc.
     */
    IngestJob(Collection<Content> dataSources, IngestJobSettings settings, boolean doUI) {
        this.id = IngestJob.nextId.getAndIncrement();
        this.dataSourceJobs = new ConcurrentHashMap<>();
        for (Content dataSource : dataSources) {
            DataSourceIngestJob dataSourceIngestJob = new DataSourceIngestJob(this, dataSource, settings, doUI);
            this.dataSourceJobs.put(dataSourceIngestJob.getId(), dataSourceIngestJob);
        }
        incompleteJobsCount = new AtomicInteger(dataSourceJobs.size());
    }

    /**
     * Gets the unique identifier assigned to this ingest job.
     *
     * @return The job identifier.
     */
    public long getId() {
        return this.id;
    }

    /**
     * Checks to see if this ingest job has at least one non-empty ingest module
     * pipeline (first or second stage data-source-level pipeline or file-level
     * pipeline).
     *
     * @return True or false.
     */
    boolean hasIngestPipeline() {
        /**
         * TODO: This could actually be done more simply by adding a method to
         * the IngestJobSettings to check for at least one enabled ingest module
         * template. The test could then be done in the ingest manager before
         * even constructing an ingest job.
         */
        for (DataSourceIngestJob dataSourceJob : this.dataSourceJobs.values()) {
            if (dataSourceJob.hasIngestPipeline()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Starts this ingest job by starting its ingest module pipelines and
     * scheduling the ingest tasks that make up the job.
     *
     * @return A collection of ingest module start up errors, empty on success.
     */
    synchronized List<IngestModuleError> start() {
        List<IngestModuleError> errors = new ArrayList<>();
        if (started) {
            errors.add(new IngestModuleError("IngestJob", new IllegalStateException("Job already started")));
            return errors;
        }
        started = true;

        List<DataSourceIngestJob> startedDataSourceJobs = new ArrayList<>();
        for (DataSourceIngestJob dataSourceJob : this.dataSourceJobs.values()) {
            errors.addAll(dataSourceJob.start());
            if (errors.isEmpty()) {
                IngestManager.getInstance().fireDataSourceAnalysisStarted(id, dataSourceJob.getId(), dataSourceJob.getDataSource());
                startedDataSourceJobs.add(dataSourceJob);
            } else {
                startedDataSourceJobs.stream().forEach((startedDataSourceJob) -> {
                    startedDataSourceJob.cancel();
                });
                break;
            }
        }
        return errors;
    }

    /**
     * Gets a snapshot of the progress of this ingest job.
     *
     * @return The snapshot.
     */
    public ProgressSnapshot getSnapshot() {
        return new ProgressSnapshot(true);
    }

    /**
     * Gets a snapshot of the progress of this ingest job.
     *
     * @return The snapshot.
     */
    public ProgressSnapshot getSnapshot(boolean getIngestTasksSnapshot) {
        return new ProgressSnapshot(getIngestTasksSnapshot);
    }

    /**
     * Gets snapshots of the progress of each of this ingest job's child data
     * source ingest jobs.
     *
     * @return A list of data source ingest job progress snapshots.
     */
    List<DataSourceIngestJob.Snapshot> getDataSourceIngestJobSnapshots() {
        List<DataSourceIngestJob.Snapshot> snapshots = new ArrayList<>();
        this.dataSourceJobs.values().stream().forEach((dataSourceJob) -> {
            snapshots.add(dataSourceJob.getSnapshot(true));
        });
        return snapshots;
    }

    /**
     * Requests cancellation of this ingest job, which means discarding
     * unfinished tasks and stopping the ingest pipelines. Returns immediately,
     * but there may be a delay before all of the ingest modules in the
     * pipelines respond by stopping processing.
     */
    synchronized public void cancel() {
        IngestManager ingestManager = IngestManager.getInstance();
        this.dataSourceJobs.values().stream().forEach((job) -> {
            job.cancel();
        });
        this.cancelled = true;
    }

    /**
     * Queries whether or not cancellation of this ingest job has been
     * requested.
     *
     * @return True or false.
     */
    public boolean isCancelled() {
        return this.cancelled;
    }

    /**
     * Provides a callback for completed data source ingest jobs, allowing this
     * ingest job to notify the ingest manager when it is complete.
     *
     * @param job A completed data source ingest job.
     */
    void dataSourceJobFinished(DataSourceIngestJob job) {
        IngestManager ingestManager = IngestManager.getInstance();
        if (!job.isCancelled()) {
            ingestManager.fireDataSourceAnalysisCompleted(id, job.getId(), job.getDataSource());
        } else {
            IngestManager.getInstance().fireDataSourceAnalysisCancelled(id, job.getId(), job.getDataSource());
        }
        if (incompleteJobsCount.decrementAndGet() == 0) {
            ingestManager.finishIngestJob(this);
        }
    }

    /**
     * A snapshot of the progress of an ingest job.
     */
    public final class ProgressSnapshot {

        private final List<DataSourceProcessingSnapshot> dataSourceProcessingSnapshots;
        private DataSourceIngestModuleHandle dataSourceModule;
        private boolean fileIngestRunning;
        private Date fileIngestStartTime;
        private final boolean jobCancelled;

        /**
         * A snapshot of the progress of an ingest job on the processing of a
         * data source.
         */
        public final class DataSourceProcessingSnapshot {

            private final DataSourceIngestJob.Snapshot snapshot;

            private DataSourceProcessingSnapshot(DataSourceIngestJob.Snapshot snapshot) {
                this.snapshot = snapshot;
            }

            /**
             * Gets the name of the data source that is the subject of this
             * snapshot.
             *
             * @return A data source name string.
             */
            public String getDataSource() {
                return snapshot.getDataSource();
            }

            /**
             * Indicates whether or not the processing of the data source that
             * is the subject of this snapshot was canceled.
             *
             * @return True or false.
             */
            public boolean isCancelled() {
                return snapshot.isCancelled();
            }

            /**
             * Gets a list of the display names of any canceled data source
             * level ingest modules.
             *
             * @return A list of canceled data source level ingest module
             *         display names, possibly empty.
             */
            public List<String> getCancelledDataSourceIngestModules() {
                return snapshot.getCancelledDataSourceIngestModules();
            }

        }

        /**
         * Constructs a snapshot of ingest job progress.
         */
        private ProgressSnapshot(boolean getIngestTasksSnapshot) {
            dataSourceModule = null;
            fileIngestRunning = false;
            fileIngestStartTime = null;
            dataSourceProcessingSnapshots = new ArrayList<>();
            for (DataSourceIngestJob dataSourceJob : dataSourceJobs.values()) {
                DataSourceIngestJob.Snapshot snapshot = dataSourceJob.getSnapshot(getIngestTasksSnapshot);
                dataSourceProcessingSnapshots.add(new DataSourceProcessingSnapshot(snapshot));
                if (null == dataSourceModule) {
                    DataSourceIngestPipeline.PipelineModule module = snapshot.getDataSourceLevelIngestModule();
                    if (null != module) {
                        dataSourceModule = new DataSourceIngestModuleHandle(dataSourceJobs.get(snapshot.getJobId()), module);
                    }
                }
                if (snapshot.fileIngestIsRunning()) {
                    fileIngestRunning = true;
                }
                Date childFileIngestStartTime = snapshot.fileIngestStartTime();
                if (null != childFileIngestStartTime && (null == fileIngestStartTime || childFileIngestStartTime.before(fileIngestStartTime))) {
                    fileIngestStartTime = childFileIngestStartTime;
                }
            }
            this.jobCancelled = cancelled;
        }

        /**
         * Gets a handle to the currently running data source level ingest
         * module at the time the snapshot was taken.
         *
         * @return The handle, may be null.
         */
        public DataSourceIngestModuleHandle runningDataSourceIngestModule() {
            return this.dataSourceModule;
        }

        /**
         * Queries whether or not file level ingest was running at the time the
         * snapshot was taken.
         *
         * @return True or false.
         */
        public boolean fileIngestIsRunning() {
            return this.fileIngestRunning;
        }

        /**
         * Gets the time that file level ingest started.
         *
         * @return The start time, may be null.
         */
        public Date fileIngestStartTime() {
            return new Date(this.fileIngestStartTime.getTime());
        }

        /**
         * Queries whether or not a cancellation request had been issued at the
         * time the snapshot was taken.
         *
         * @return True or false.
         */
        public boolean isCancelled() {
            return this.jobCancelled;
        }

        /**
         * Gets snapshots of the progress processing individual data sources.
         *
         * @return The list of snapshots.
         */
        public List<DataSourceProcessingSnapshot> getDataSourceSnapshots() {
            return Collections.unmodifiableList(this.dataSourceProcessingSnapshots);
        }

    }

    /**
     * A handle to a data source level ingest module that can be used to get
     * basic information about the module and to request cancellation of the
     * module.
     */
    public static class DataSourceIngestModuleHandle {

        private final DataSourceIngestJob job;
        private final DataSourceIngestPipeline.PipelineModule module;
        private final boolean cancelled;

        /**
         * Constructs a handle to a data source level ingest module that can be
         * used to get basic information about the module and to request
         * cancellation of the module.
         *
         * @param DataSourceIngestJob The data source ingest job that owns the
         *                            data source level ingest module.
         * @param module              The data source level ingest module.
         */
        private DataSourceIngestModuleHandle(DataSourceIngestJob job, DataSourceIngestPipeline.PipelineModule module) {
            this.job = job;
            this.module = module;
            this.cancelled = job.currentDataSourceIngestModuleIsCancelled();
        }

        /**
         * Gets the display name of the data source level ingest module
         * associated with this handle.
         *
         * @return The display name.
         */
        public String displayName() {
            return this.module.getDisplayName();
        }

        /**
         * Gets the time the data source level ingest module associated with
         * this handle began processing.
         *
         * @return The module processing start time.
         */
        public Date startTime() {
            return this.module.getProcessingStartTime();
        }

        /**
         * Queries whether or not cancellation of the data source level ingest
         * module associated with this handle has been requested.
         *
         * @return True or false.
         */
        public boolean isCancelled() {
            return this.cancelled;
        }

        /**
         * Requests cancellation of the ingest module associated with this
         * handle. Returns immediately, but there may be a delay before the
         * ingest module responds by stopping processing.
         */
        public void cancel() {
            /**
             * TODO: Cancellation needs to be more precise. The long-term
             * solution is to add a cancel() method to IngestModule and do away
             * with the cancellation queries of IngestJobContext. However, until
             * an API change is legal, a cancel() method can be added to the
             * DataSourceIngestModuleAdapter and FileIngestModuleAdapter classes
             * and an instanceof check can be used to call it, with this code as
             * the default implementation and the fallback. All of the ingest
             * modules participating in this workaround will need to consult the
             * cancelled flag in the adapters.
             */
            if (this.job.getCurrentDataSourceIngestModule() == this.module) {
                this.job.cancelCurrentDataSourceIngestModule();
            }
        }

    }

}
