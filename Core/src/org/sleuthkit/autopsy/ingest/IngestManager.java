/*
 * Autopsy Forensic Browser
 *
 * Copyright 2013-2014 Basis Technology Corp.
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

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import javax.swing.JOptionPane;
import org.netbeans.api.progress.ProgressHandle;
import org.netbeans.api.progress.ProgressHandleFactory;
import org.openide.util.Cancellable;
import org.openide.util.NbBundle;
import org.sleuthkit.autopsy.casemodule.Case;
import org.sleuthkit.autopsy.core.UserPreferences;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.autopsy.coreutils.MessageNotifyUtil;
import org.sleuthkit.datamodel.AbstractFile;
import org.sleuthkit.datamodel.Content;

/**
 * Manages the creation and execution of ingest jobs, i.e., the processing of
 * data sources by ingest modules.
 */
public class IngestManager {

    private static final Logger logger = Logger.getLogger(IngestManager.class.getName());
    private static IngestManager instance = null;

    /**
     * The ingest manager maintains a mapping of ingest job IDs to ingest jobs.
     */
    private final ConcurrentHashMap<Long, IngestJob> jobsById = new ConcurrentHashMap<>();

    /**
     * Each runnable/callable task the ingest manager submits to its thread
     * pools is given a unique thread/task ID.
     */
    // TODO: It is no longer necessary to have multiple thread pools.
    private final AtomicLong nextThreadId = new AtomicLong(0L);

    /**
     * Ingest jobs are started on a pool thread by ingest job starters. A
     * mapping of thread/task IDs to the result objects associated with each
     * ingest job starter is maintained to provide handles that can be used to
     * cancel the ingest job starter.
     */
    private final ConcurrentHashMap<Long, Future<Void>> ingestJobStarters = new ConcurrentHashMap<>();
    private final ExecutorService startIngestJobsThreadPool = Executors.newSingleThreadExecutor();

    /**
     * Ingest jobs use an ingest task scheduler to break themselves down into
     * data source level and file level tasks. The ingest scheduler puts these
     * ingest tasks into queues for execution on ingest manager pool threads by
     * ingest task executers. There is a single data source level ingest thread
     * and a user configurable number of file level ingest threads.
     */
    private static final int MIN_NUMBER_OF_FILE_INGEST_THREADS = 1;
    private static final int MAX_NUMBER_OF_FILE_INGEST_THREADS = 16;
    private static final int DEFAULT_NUMBER_OF_FILE_INGEST_THREADS = 2;
    private int numberOfFileIngestThreads = DEFAULT_NUMBER_OF_FILE_INGEST_THREADS;
    private final ExecutorService dataSourceIngestThreadPool = Executors.newSingleThreadExecutor();
    private final ExecutorService fileIngestThreadPool;

    /**
     * The ingest manager uses the property change feature from Java Beans as an
     * event publishing mechanism. There are two kinds of events, ingest job
     * events and ingest module events. Property changes are fired by ingest
     * event publishers on a pool thread.
     */
    private final PropertyChangeSupport ingestJobEventPublisher = new PropertyChangeSupport(IngestManager.class);
    private final PropertyChangeSupport ingestModuleEventPublisher = new PropertyChangeSupport(IngestManager.class);
    private final ExecutorService fireIngestEventsThreadPool = Executors.newSingleThreadExecutor();

    /**
     * The ingest manager uses an ingest monitor to determine when system
     * resources are under pressure. If the monitor detects such a situation, it
     * calls back to the ingest manager to cancel all ingest jobs in progress.
     */
    private final IngestMonitor ingestMonitor = new IngestMonitor();

    /**
     * The ingest manager provides access to a top component that is used by
     * ingest module to post messages for the user. A count of the posts is used
     * as a cap to avoid bogging down the application.
     */
    private static final int MAX_ERROR_MESSAGE_POSTS = 200;
    private volatile IngestMessageTopComponent ingestMessageBox;
    private final AtomicLong ingestErrorMessagePosts = new AtomicLong(0L);

    /**
     * The ingest manager supports reporting of ingest processing progress by
     * collecting snapshots of the activities of the ingest threads, ingest job
     * progress, and ingest module run times.
     */
    private final ConcurrentHashMap<Long, IngestThreadActivitySnapshot> ingestThreadActivitySnapshots = new ConcurrentHashMap<>(); // Maps ingest thread ids to progress ingestThreadActivitySnapshots.    
    private final ConcurrentHashMap<String, Long> ingestModuleRunTimes = new ConcurrentHashMap<>();

    /**
     * The ingest job creation capability of the ingest manager can be turned on
     * and off to support an orderly shut down of the application.
     */
    private volatile boolean jobCreationIsEnabled;

    /**
     * Ingest job events.
     */
    public enum IngestJobEvent {

        /**
         * Property change event fired when an ingest job is started. The old
         * value of the PropertyChangeEvent object is set to the ingest job id,
         * and the new value is set to null.
         */
        STARTED,
        /**
         * Property change event fired when an ingest job is completed. The old
         * value of the PropertyChangeEvent object is set to the ingest job id,
         * and the new value is set to null.
         */
        COMPLETED,
        /**
         * Property change event fired when an ingest job is canceled. The old
         * value of the PropertyChangeEvent object is set to the ingest job id,
         * and the new value is set to null.
         */
        CANCELLED,
    };

    /**
     * Ingest module events.
     */
    public enum IngestModuleEvent {

        /**
         * Property change event fired when an ingest module adds new data to a
         * case, usually by posting to the blackboard. The old value of the
         * PropertyChangeEvent is a ModuleDataEvent object, and the new value is
         * set to null.
         */
        DATA_ADDED,
        /**
         * Property change event fired when an ingest module adds new content to
         * a case or changes a recorded attribute of existing content. For
         * example, if a module adds an extracted or carved file to a case, the
         * module should fire this event. The old value of the
         * PropertyChangeEvent is a ModuleContentEvent object, and the new value
         * is set to null.
         */
        CONTENT_CHANGED,
        /**
         * Property change event fired when the ingest of a file is completed.
         * The old value of the PropertyChangeEvent is the Autopsy object ID of
         * the file. The new value is the AbstractFile for that ID.
         */
        FILE_DONE,
    };

    /**
     * Gets the manager of the creation and execution of ingest jobs, i.e., the
     * processing of data sources by ingest modules.
     *
     * @return A singleton ingest manager object.
     */
    public synchronized static IngestManager getInstance() {
        if (instance == null) {
            // Two stage construction to avoid allowing "this" reference to 
            // escape from the constructor via the property change listener.
            // This is to ensure that a partially constructed ingest manager is
            // not published to other threads.
            instance = new IngestManager();
            instance.subscribeToCaseEvents();
        }
        return instance;
    }

    /**
     * Gets the number of file ingest threads the ingest manager will use to do
     * ingest jobs.
     *
     * @return The number of file ingest threads.
     */
    public int getNumberOfFileIngestThreads() {
        return numberOfFileIngestThreads;
    }

    /**
     * Starts an ingest job, i.e., processing by ingest modules, for each data
     * source in a collection of data sources. Note that if the provide UI
     * argument is set to true, it is assumed this method is being called on the
     * EDT and a worker thread will be dispatched to start the job.
     *
     * @param dataSources The data sources to be processed.
     * @param settings The ingest job settings.
     * @param provideUI Whether or not to support user interaction, e.g.,
     * showing message boxes and reporting progress through the NetBeans
     * Progress API.
     * @return The ingest job that was started
     */
    public synchronized void startIngestJobs(Collection<Content> dataSources, IngestJobSettings settings, boolean provideUI) {
        if (!isIngestRunning()) {
            clearIngestMessageBox();
        }

        if (!ingestMonitor.isRunning()) {
            ingestMonitor.start();
        }

        long taskId = nextThreadId.incrementAndGet();
        Future<Void> task = startIngestJobsThreadPool.submit(new IngestJobsStarter(taskId, dataSources, settings, provideUI));
        ingestJobStarters.put(taskId, task);
    }

    /**
     * Queries whether or not any ingest jobs are in progress.
     *
     * @return True or false.
     */
    public boolean isIngestRunning() {
        return !this.jobsById.isEmpty();
    }

    /**
     * Cancels all ingest jobs in progress.
     */
    public void cancelAllIngestJobs() {
        // Stop creating new ingest jobs.
        for (Future<Void> handle : ingestJobStarters.values()) {
            handle.cancel(true);
        }

        // Cancel all the jobs already created.
        for (IngestJob job : this.jobsById.values()) {
            job.cancel();
        }
    }

    /**
     * Adds an ingest job event property change listener.
     *
     * @param listener The PropertyChangeListener to register.
     */
    public void addIngestJobEventListener(final PropertyChangeListener listener) {
        ingestJobEventPublisher.addPropertyChangeListener(listener);
    }

    /**
     * Removes an ingest job event property change listener.
     *
     * @param listener The PropertyChangeListener to unregister.
     */
    public void removeIngestJobEventListener(final PropertyChangeListener listener) {
        ingestJobEventPublisher.removePropertyChangeListener(listener);
    }

    /**
     * Adds an ingest module event property change listener.
     *
     * @param listener The PropertyChangeListener to register.
     */
    public void addIngestModuleEventListener(final PropertyChangeListener listener) {
        ingestModuleEventPublisher.addPropertyChangeListener(listener);
    }

    /**
     * Removes an ingest module event property change listener.
     *
     * @param listener The PropertyChangeListener to unregister.
     */
    public void removeIngestModuleEventListener(final PropertyChangeListener listener) {
        ingestModuleEventPublisher.removePropertyChangeListener(listener);
    }

    /**
     * Adds an ingest job and ingest module event property change listener.
     *
     * @param listener The PropertyChangeListener to register.
     * @deprecated Use addIngestJobEventListener() and/or
     * addIngestModuleEventListener().
     */
    @Deprecated
    public static void addPropertyChangeListener(final PropertyChangeListener listener) {
        instance.ingestJobEventPublisher.addPropertyChangeListener(listener);
        instance.ingestModuleEventPublisher.addPropertyChangeListener(listener);
    }

    /**
     * Removes an ingest job and ingest module event property change listener.
     *
     * @param listener The PropertyChangeListener to unregister.
     * @deprecated Use removeIngestJobEventListener() and/or
     * removeIngestModuleEventListener().
     */
    @Deprecated
    public static void removePropertyChangeListener(final PropertyChangeListener listener) {
        instance.ingestJobEventPublisher.removePropertyChangeListener(listener);
        instance.ingestModuleEventPublisher.removePropertyChangeListener(listener);
    }

    /**
     * Constructs a manager of the creation and execution of ingest jobs, i.e.,
     * the processing of data sources by ingest modules. The manager immediately
     * submits ingest task executers (Callable objects) to the data source level
     * ingest and file level ingest thread pools. The ingest task executers are
     * simple consumers that will normally run as long as the application runs.
     */
    private IngestManager() {
        startDataSourceIngestThread();

        numberOfFileIngestThreads = UserPreferences.numberOfFileIngestThreads();
        if ((numberOfFileIngestThreads < MIN_NUMBER_OF_FILE_INGEST_THREADS) || (numberOfFileIngestThreads > MAX_NUMBER_OF_FILE_INGEST_THREADS)) {
            numberOfFileIngestThreads = DEFAULT_NUMBER_OF_FILE_INGEST_THREADS;
            UserPreferences.setNumberOfFileIngestThreads(numberOfFileIngestThreads);
        }
        fileIngestThreadPool = Executors.newFixedThreadPool(numberOfFileIngestThreads);
        for (int i = 0; i < numberOfFileIngestThreads; ++i) {
            startFileIngestThread();
        }
    }

    /**
     * Called by the custom installer for this package once the window system is
     * initialized, allowing the ingest manager to get the top component used to
     * display ingest messages.
     */
    void initIngestMessageInbox() {
        ingestMessageBox = IngestMessageTopComponent.findInstance();
    }

    /**
     * Submits an ingest task executer Callable to the data source level ingest
     * thread pool.
     */
    private void startDataSourceIngestThread() {
        long threadId = nextThreadId.incrementAndGet();
        dataSourceIngestThreadPool.submit(new IngestTaskExecuter(threadId, IngestTasksScheduler.getInstance().getDataSourceIngestTaskQueue()));
        ingestThreadActivitySnapshots.put(threadId, new IngestThreadActivitySnapshot(threadId));
    }

    /**
     * Submits a ingest task executer Callable to the file level ingest thread
     * pool.
     */
    private void startFileIngestThread() {
        long threadId = nextThreadId.incrementAndGet();
        fileIngestThreadPool.submit(new IngestTaskExecuter(threadId, IngestTasksScheduler.getInstance().getFileIngestTaskQueue()));
        ingestThreadActivitySnapshots.put(threadId, new IngestThreadActivitySnapshot(threadId));
    }

    private void subscribeToCaseEvents() {
        Case.addPropertyChangeListener(new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent event) {
                if (event.getPropertyName().equals(Case.Events.CURRENT_CASE.toString())) {
                    if (event.getNewValue() != null) {
                        handleCaseOpened();
                    } else {
                        handleCaseClosed();
                    }
                }
            }
        });
    }

    void handleCaseOpened() {
        this.jobCreationIsEnabled = true;
        clearIngestMessageBox();
    }

    void handleCaseClosed() {
        this.jobCreationIsEnabled = false;
        cancelAllIngestJobs();
        clearIngestMessageBox();
    }

    private void clearIngestMessageBox() {
        if (ingestMessageBox != null) {
            ingestMessageBox.clearMessages();
        }
        ingestErrorMessagePosts.set(0);
    }

    /**
     * Starts an ingest job for a collection of data sources.
     *
     * @param dataSource The data sources to ingest.
     * @param settings The settings for the job.
     * @return A collection of ingest module start up errors, empty on success.
     */
    private List<IngestModuleError> startJob(Collection<Content> dataSources, IngestJobSettings settings) {
        List<IngestModuleError> errors = new ArrayList<>();
        if (this.jobCreationIsEnabled) {
            IngestJob job = new IngestJob(dataSources, settings);
            long jobId = job.getId();
            this.jobsById.put(jobId, job);
            errors = job.start();
            if (errors.isEmpty() && job.hasIngestPipeline()) {
                this.fireIngestJobStarted(jobId);
                IngestManager.logger.log(Level.INFO, "Ingest job {0} started", jobId);
            } else {
                this.jobsById.remove(jobId);
            }
        }
        return errors;
    }

    void finishJob(IngestJob job) {
        long jobId = job.getId();
        this.jobsById.remove(jobId);
        if (!job.isCancelled()) {
            IngestManager.logger.log(Level.INFO, "Ingest job {0} completed", jobId);
            this.fireIngestJobCompleted(jobId);
        } else {
            IngestManager.logger.log(Level.INFO, "Ingest job {0} cancelled", jobId);
            this.fireIngestJobCancelled(jobId);
        }
    }

    /**
     * Called each time a module in a data source pipeline starts
     *
     * @param task
     * @param ingestModuleDisplayName
     */
    void setIngestTaskProgress(DataSourceIngestTask task, String ingestModuleDisplayName) {
        ingestThreadActivitySnapshots.put(task.getThreadId(), new IngestThreadActivitySnapshot(task.getThreadId(), task.getIngestJob().getId(), ingestModuleDisplayName, task.getDataSource()));
    }

    /**
     * Called each time a module in a file ingest pipeline starts
     *
     * @param task
     * @param ingestModuleDisplayName
     */
    void setIngestTaskProgress(FileIngestTask task, String ingestModuleDisplayName) {
        IngestThreadActivitySnapshot prevSnap = ingestThreadActivitySnapshots.get(task.getThreadId());
        IngestThreadActivitySnapshot newSnap = new IngestThreadActivitySnapshot(task.getThreadId(), task.getIngestJob().getId(), ingestModuleDisplayName, task.getDataSource(), task.getFile());
        ingestThreadActivitySnapshots.put(task.getThreadId(), newSnap);

        incrementModuleRunTime(prevSnap.getActivity(), newSnap.getStartTime().getTime() - prevSnap.getStartTime().getTime());
    }

    /**
     * Called each time a data source ingest task completes
     *
     * @param task
     */
    void setIngestTaskProgressCompleted(DataSourceIngestTask task) {
        ingestThreadActivitySnapshots.put(task.getThreadId(), new IngestThreadActivitySnapshot(task.getThreadId()));
    }

    /**
     * Called when a file ingest pipeline is complete for a given file
     *
     * @param task
     */
    void setIngestTaskProgressCompleted(FileIngestTask task) {
        IngestThreadActivitySnapshot prevSnap = ingestThreadActivitySnapshots.get(task.getThreadId());
        IngestThreadActivitySnapshot newSnap = new IngestThreadActivitySnapshot(task.getThreadId());
        ingestThreadActivitySnapshots.put(task.getThreadId(), newSnap);
        incrementModuleRunTime(prevSnap.getActivity(), newSnap.getStartTime().getTime() - prevSnap.getStartTime().getTime());
    }

    /**
     * Internal method to update the times associated with each module.
     *
     * @param moduleName
     * @param duration
     */
    private void incrementModuleRunTime(String moduleName, Long duration) {
        if (moduleName.equals("IDLE")) {
            return;
        }

        synchronized (ingestModuleRunTimes) {
            Long prevTimeL = ingestModuleRunTimes.get(moduleName);
            long prevTime = 0;
            if (prevTimeL != null) {
                prevTime = prevTimeL;
            }
            prevTime += duration;
            ingestModuleRunTimes.put(moduleName, prevTime);
        }
    }

    /**
     * Return the list of run times for each module
     *
     * @return Map of module name to run time (in milliseconds)
     */
    Map<String, Long> getModuleRunTimes() {
        synchronized (ingestModuleRunTimes) {
            Map<String, Long> times = new HashMap<>(ingestModuleRunTimes);
            return times;
        }
    }

    /**
     * Get the stats on current state of each thread
     *
     * @return
     */
    List<IngestThreadActivitySnapshot> getIngestThreadActivitySnapshots() {
        return new ArrayList<>(ingestThreadActivitySnapshots.values());
    }

    /**
     * Fire an ingest event signifying an ingest job started.
     *
     * @param ingestJobId The ingest job id.
     */
    void fireIngestJobStarted(long ingestJobId) {
        fireIngestEventsThreadPool.submit(new IngestEventPublisher(ingestJobEventPublisher, IngestJobEvent.STARTED, ingestJobId, null));
    }

    /**
     * Fire an ingest event signifying an ingest job finished.
     *
     * @param ingestJobId The ingest job id.
     */
    void fireIngestJobCompleted(long ingestJobId) {
        fireIngestEventsThreadPool.submit(new IngestEventPublisher(ingestJobEventPublisher, IngestJobEvent.COMPLETED, ingestJobId, null));
    }

    /**
     * Fire an ingest event signifying an ingest job was canceled.
     *
     * @param ingestJobId The ingest job id.
     */
    void fireIngestJobCancelled(long ingestJobId) {
        fireIngestEventsThreadPool.submit(new IngestEventPublisher(ingestJobEventPublisher, IngestJobEvent.CANCELLED, ingestJobId, null));
    }

    /**
     * Fire an ingest event signifying the ingest of a file is completed.
     *
     * @param file The file that is completed.
     */
    void fireFileIngestDone(AbstractFile file) {
        fireIngestEventsThreadPool.submit(new IngestEventPublisher(ingestModuleEventPublisher, IngestModuleEvent.FILE_DONE, file.getId(), file));
    }

    /**
     * Fire an event signifying a blackboard post by an ingest module.
     *
     * @param moduleDataEvent A ModuleDataEvent with the details of the posting.
     */
    void fireIngestModuleDataEvent(ModuleDataEvent moduleDataEvent) {
        fireIngestEventsThreadPool.submit(new IngestEventPublisher(ingestModuleEventPublisher, IngestModuleEvent.DATA_ADDED, moduleDataEvent, null));
    }

    /**
     * Fire an event signifying discovery of additional content by an ingest
     * module.
     *
     * @param moduleDataEvent A ModuleContentEvent with the details of the new
     * content.
     */
    void fireIngestModuleContentEvent(ModuleContentEvent moduleContentEvent) {
        fireIngestEventsThreadPool.submit(new IngestEventPublisher(ingestModuleEventPublisher, IngestModuleEvent.CONTENT_CHANGED, moduleContentEvent, null));
    }

    /**
     * Post a message to the ingest messages in box.
     *
     * @param message The message to be posted.
     */
    void postIngestMessage(IngestMessage message) {
        if (ingestMessageBox != null) {
            if (message.getMessageType() != IngestMessage.MessageType.ERROR && message.getMessageType() != IngestMessage.MessageType.WARNING) {
                ingestMessageBox.displayMessage(message);
            } else {
                long errorPosts = ingestErrorMessagePosts.incrementAndGet();
                if (errorPosts <= MAX_ERROR_MESSAGE_POSTS) {
                    ingestMessageBox.displayMessage(message);
                } else if (errorPosts == MAX_ERROR_MESSAGE_POSTS + 1) {
                    IngestMessage errorMessageLimitReachedMessage = IngestMessage.createErrorMessage(
                            NbBundle.getMessage(this.getClass(), "IngestManager.IngestMessage.ErrorMessageLimitReached.title"),
                            NbBundle.getMessage(this.getClass(), "IngestManager.IngestMessage.ErrorMessageLimitReached.subject"),
                            NbBundle.getMessage(this.getClass(), "IngestManager.IngestMessage.ErrorMessageLimitReached.msg", MAX_ERROR_MESSAGE_POSTS));
                    ingestMessageBox.displayMessage(errorMessageLimitReachedMessage);
                }
            }
        }
    }

    /**
     * Get the free disk space of the drive where to which ingest data is being
     * written, as reported by the ingest monitor.
     *
     * @return Free disk space, -1 if unknown
     */
    long getFreeDiskSpace() {
        if (ingestMonitor != null) {
            return ingestMonitor.getFreeSpace();
        } else {
            return -1;
        }
    }

    /**
     * Gets snapshots of the state of all running ingest jobs.
     *
     * @return A list of ingest job state snapshots.
     */
    List<DataSourceIngestJob.Snapshot> getIngestJobSnapshots() {
        List<DataSourceIngestJob.Snapshot> snapShots = new ArrayList<>();
        for (IngestJob job : this.jobsById.values()) {
            snapShots.addAll(job.getDetailedSnapshot());
        }
        return snapShots;
    }

    /**
     * Creates and starts an ingest job, i.e., processing by ingest modules, for
     * each data source in a collection of data sources.
     */
    private final class IngestJobsStarter implements Callable<Void> {

        private final long threadId;
        private final Collection<Content> dataSources;
        private final IngestJobSettings settings;
        private final boolean doStartupErrorsMsgBox;
        private ProgressHandle progress;

        IngestJobsStarter(long threadId, Collection<Content> dataSources, IngestJobSettings settings, boolean doMessageDialogs) {
            this.threadId = threadId;
            this.dataSources = dataSources;
            this.settings = settings;
            this.doStartupErrorsMsgBox = doMessageDialogs;
        }

        @Override
        public Void call() {
            try {
                /**
                 * Bail out if there is nothing to do or cancellation has been
                 * requested.
                 */
                if (this.dataSources.isEmpty() || Thread.currentThread().isInterrupted()) {
                    return null;
                }

                /**
                 * Set up a progress bar.
                 */
                final String displayName = NbBundle.getMessage(this.getClass(),
                        "IngestManager.StartIngestJobsTask.run.displayName");
                progress = ProgressHandleFactory.createHandle(displayName, new Cancellable() {
                    @Override
                    public boolean cancel() {
                        if (progress != null) {
                            progress.setDisplayName(NbBundle.getMessage(this.getClass(),
                                    "IngestManager.StartIngestJobsTask.run.cancelling",
                                    displayName));
                        }
                        Future<?> handle = ingestJobStarters.remove(threadId);
                        handle.cancel(true);
                        return true;
                    }
                });
                progress.start();

                /**
                 * Try to start the ingest job.
                 */
                List<IngestModuleError> errors = IngestManager.this.startJob(this.dataSources, this.settings);
                if (!errors.isEmpty() && this.doStartupErrorsMsgBox) {
                    StringBuilder moduleStartUpErrors = new StringBuilder();
                    for (IngestModuleError error : errors) {
                        String moduleName = error.getModuleDisplayName();
                        moduleStartUpErrors.append(moduleName);
                        moduleStartUpErrors.append(": ");
                        moduleStartUpErrors.append(error.getModuleError().getLocalizedMessage());
                        moduleStartUpErrors.append("\n");
                    }
                    StringBuilder notifyMessage = new StringBuilder();
                    notifyMessage.append(NbBundle.getMessage(this.getClass(),
                            "IngestManager.StartIngestJobsTask.run.startupErr.dlgMsg"));
                    notifyMessage.append("\n");
                    notifyMessage.append(NbBundle.getMessage(this.getClass(),
                            "IngestManager.StartIngestJobsTask.run.startupErr.dlgSolution"));
                    notifyMessage.append("\n");
                    notifyMessage.append(NbBundle.getMessage(this.getClass(),
                            "IngestManager.StartIngestJobsTask.run.startupErr.dlgErrorList",
                            moduleStartUpErrors.toString()));
                    notifyMessage.append("\n\n");
                    JOptionPane.showMessageDialog(null, notifyMessage.toString(),
                            NbBundle.getMessage(this.getClass(),
                                    "IngestManager.StartIngestJobsTask.run.startupErr.dlgTitle"), JOptionPane.ERROR_MESSAGE);
                }

                return null;

            } finally {
                if (null != progress) {
                    progress.finish();
                }
                ingestJobStarters.remove(threadId);
            }
        }

    }

    /**
     * A consumer for an ingest task queue.
     */
    private final class IngestTaskExecuter implements Runnable {

        private final long threadId;
        private final IngestTaskQueue tasks;

        IngestTaskExecuter(long threadId, IngestTaskQueue tasks) {
            this.threadId = threadId;
            this.tasks = tasks;
        }

        @Override
        public void run() {
            while (true) {
                try {
                    IngestTask task = tasks.getNextTask(); // Blocks.
                    task.execute(threadId);
                } catch (InterruptedException ex) {
                    break;
                }
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
            }
        }
    }

    /**
     * Fires ingest events to ingest manager property change listeners.
     */
    private static final class IngestEventPublisher implements Runnable {

        private final PropertyChangeSupport publisher;
        private final IngestJobEvent jobEvent;
        private final IngestModuleEvent moduleEvent;
        private final Object oldValue;
        private final Object newValue;

        IngestEventPublisher(PropertyChangeSupport publisher, IngestJobEvent event, Object oldValue, Object newValue) {
            this.publisher = publisher;
            this.jobEvent = event;
            this.moduleEvent = null;
            this.oldValue = oldValue;
            this.newValue = newValue;
        }

        IngestEventPublisher(PropertyChangeSupport publisher, IngestModuleEvent event, Object oldValue, Object newValue) {
            this.publisher = publisher;
            this.jobEvent = null;
            this.moduleEvent = event;
            this.oldValue = oldValue;
            this.newValue = newValue;
        }

        @Override
        public void run() {
            try {
                publisher.firePropertyChange((jobEvent != null ? jobEvent.toString() : moduleEvent.toString()), oldValue, newValue);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Ingest manager listener threw exception", e); //NON-NLS
                MessageNotifyUtil.Notify.show(NbBundle.getMessage(IngestManager.class, "IngestManager.moduleErr"),
                        NbBundle.getMessage(IngestManager.class, "IngestManager.moduleErr.errListenToUpdates.msg"),
                        MessageNotifyUtil.MessageType.ERROR);
            }
        }
    }

    static final class IngestThreadActivitySnapshot {

        private final long threadId;
        private final Date startTime;
        private final String activity;
        private final String dataSourceName;
        private final String fileName;
        private final long jobId;

        // nothing is running on the thread
        IngestThreadActivitySnapshot(long threadId) {
            this.threadId = threadId;
            startTime = new Date();
            this.activity = NbBundle.getMessage(this.getClass(), "IngestManager.IngestThreadActivitySnapshot.idleThread");
            this.dataSourceName = "";
            this.fileName = "";
            this.jobId = 0;
        }

        // data souce thread
        IngestThreadActivitySnapshot(long threadId, long jobId, String activity, Content dataSource) {
            this.threadId = threadId;
            this.jobId = jobId;
            startTime = new Date();
            this.activity = activity;
            this.dataSourceName = dataSource.getName();
            this.fileName = "";
        }

        // file ingest thread
        IngestThreadActivitySnapshot(long threadId, long jobId, String activity, Content dataSource, AbstractFile file) {
            this.threadId = threadId;
            this.jobId = jobId;
            startTime = new Date();
            this.activity = activity;
            this.dataSourceName = dataSource.getName();
            this.fileName = file.getName();
        }

        long getJobId() {
            return jobId;
        }

        long getThreadId() {
            return threadId;
        }

        Date getStartTime() {
            return startTime;
        }

        String getActivity() {
            return activity;
        }

        String getDataSourceName() {
            return dataSourceName;
        }

        String getFileName() {
            return fileName;
        }
    }

}
