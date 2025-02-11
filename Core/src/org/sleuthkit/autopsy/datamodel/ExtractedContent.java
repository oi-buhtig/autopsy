/*
 * Autopsy Forensic Browser
 * 
 * Copyright 2011-2015 Basis Technology Corp.
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
package org.sleuthkit.autopsy.datamodel;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Level;
import org.openide.nodes.ChildFactory;
import org.openide.nodes.Children;
import org.openide.nodes.Node;
import org.openide.nodes.Sheet;
import org.openide.util.NbBundle;
import org.openide.util.lookup.Lookups;
import org.sleuthkit.autopsy.casemodule.Case;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.autopsy.ingest.IngestManager;
import org.sleuthkit.autopsy.ingest.ModuleDataEvent;
import org.sleuthkit.datamodel.BlackboardArtifact;
import static org.sleuthkit.datamodel.BlackboardArtifact.ARTIFACT_TYPE.TSK_BLUETOOTH_PAIRING;
import static org.sleuthkit.datamodel.BlackboardArtifact.ARTIFACT_TYPE.TSK_CALENDAR_ENTRY;
import static org.sleuthkit.datamodel.BlackboardArtifact.ARTIFACT_TYPE.TSK_CALLLOG;
import static org.sleuthkit.datamodel.BlackboardArtifact.ARTIFACT_TYPE.TSK_CONTACT;
import static org.sleuthkit.datamodel.BlackboardArtifact.ARTIFACT_TYPE.TSK_DEVICE_ATTACHED;
import static org.sleuthkit.datamodel.BlackboardArtifact.ARTIFACT_TYPE.TSK_ENCRYPTION_DETECTED;
import static org.sleuthkit.datamodel.BlackboardArtifact.ARTIFACT_TYPE.TSK_EXT_MISMATCH_DETECTED;
import static org.sleuthkit.datamodel.BlackboardArtifact.ARTIFACT_TYPE.TSK_GPS_BOOKMARK;
import static org.sleuthkit.datamodel.BlackboardArtifact.ARTIFACT_TYPE.TSK_GPS_LAST_KNOWN_LOCATION;
import static org.sleuthkit.datamodel.BlackboardArtifact.ARTIFACT_TYPE.TSK_GPS_SEARCH;
import static org.sleuthkit.datamodel.BlackboardArtifact.ARTIFACT_TYPE.TSK_INSTALLED_PROG;
import static org.sleuthkit.datamodel.BlackboardArtifact.ARTIFACT_TYPE.TSK_MESSAGE;
import static org.sleuthkit.datamodel.BlackboardArtifact.ARTIFACT_TYPE.TSK_METADATA_EXIF;
import static org.sleuthkit.datamodel.BlackboardArtifact.ARTIFACT_TYPE.TSK_RECENT_OBJECT;
import static org.sleuthkit.datamodel.BlackboardArtifact.ARTIFACT_TYPE.TSK_SERVICE_ACCOUNT;
import static org.sleuthkit.datamodel.BlackboardArtifact.ARTIFACT_TYPE.TSK_SPEED_DIAL_ENTRY;
import static org.sleuthkit.datamodel.BlackboardArtifact.ARTIFACT_TYPE.TSK_WEB_BOOKMARK;
import static org.sleuthkit.datamodel.BlackboardArtifact.ARTIFACT_TYPE.TSK_WEB_COOKIE;
import static org.sleuthkit.datamodel.BlackboardArtifact.ARTIFACT_TYPE.TSK_WEB_DOWNLOAD;
import static org.sleuthkit.datamodel.BlackboardArtifact.ARTIFACT_TYPE.TSK_WEB_HISTORY;
import static org.sleuthkit.datamodel.BlackboardArtifact.ARTIFACT_TYPE.TSK_WEB_SEARCH_QUERY;
import org.sleuthkit.datamodel.SleuthkitCase;
import org.sleuthkit.datamodel.TskCoreException;
import org.sleuthkit.datamodel.TskException;

/**
 * Parent of the "extracted content" artifacts to be displayed in the tree.
 * Other artifacts are displayed under other more specific parents.
 */
public class ExtractedContent implements AutopsyVisitableItem {

    private SleuthkitCase skCase;   // set to null after case has been closed
    public static final String NAME = NbBundle.getMessage(RootNode.class, "ExtractedContentNode.name.text");

    public ExtractedContent(SleuthkitCase skCase) {
        this.skCase = skCase;
    }

    @Override
    public <T> T accept(AutopsyItemVisitor<T> v) {
        return v.visit(this);
    }

    public SleuthkitCase getSleuthkitCase() {
        return skCase;
    }

    public class RootNode extends DisplayableItemNode {

        public RootNode(SleuthkitCase skCase) {
            super(Children.create(new TypeFactory(), true), Lookups.singleton(NAME));
            super.setName(NAME);
            super.setDisplayName(NAME);
            this.setIconBaseWithExtension("org/sleuthkit/autopsy/images/extracted_content.png"); //NON-NLS
        }

        @Override
        public boolean isLeafTypeNode() {
            return false;
        }

        @Override
        public <T> T accept(DisplayableItemNodeVisitor<T> v) {
            return v.visit(this);
        }

        @Override
        protected Sheet createSheet() {
            Sheet s = super.createSheet();
            Sheet.Set ss = s.get(Sheet.PROPERTIES);
            if (ss == null) {
                ss = Sheet.createPropertiesSet();
                s.put(ss);
            }

            ss.put(new NodeProperty<>(NbBundle.getMessage(this.getClass(), "ExtractedContentNode.createSheet.name.name"),
                    NbBundle.getMessage(this.getClass(), "ExtractedContentNode.createSheet.name.displayName"),
                    NbBundle.getMessage(this.getClass(), "ExtractedContentNode.createSheet.name.desc"),
                    NAME));
            return s;
        }
    }

    /**
     * Creates the children for the ExtractedContent area of the results tree.
     * This area has all of the blackboard artifacts that are not displayed in a
     * more specific form elsewhere in the tree.
     */
    private class TypeFactory extends ChildFactory.Detachable<BlackboardArtifact.ARTIFACT_TYPE> {

        private final ArrayList<BlackboardArtifact.ARTIFACT_TYPE> doNotShow = new ArrayList<>();
        // maps the artifact type to its child node 
        private final HashMap<BlackboardArtifact.ARTIFACT_TYPE, TypeNode> typeNodeList = new HashMap<>();

        public TypeFactory() {
            super();

            // these are shown in other parts of the UI tree
            doNotShow.add(BlackboardArtifact.ARTIFACT_TYPE.TSK_GEN_INFO);
            doNotShow.add(BlackboardArtifact.ARTIFACT_TYPE.TSK_EMAIL_MSG);
            doNotShow.add(BlackboardArtifact.ARTIFACT_TYPE.TSK_HASHSET_HIT);
            doNotShow.add(BlackboardArtifact.ARTIFACT_TYPE.TSK_KEYWORD_HIT);
            doNotShow.add(BlackboardArtifact.ARTIFACT_TYPE.TSK_INTERESTING_FILE_HIT);
            doNotShow.add(BlackboardArtifact.ARTIFACT_TYPE.TSK_INTERESTING_ARTIFACT_HIT);
        }

        private final PropertyChangeListener pcl = (PropertyChangeEvent evt) -> {
            String eventType = evt.getPropertyName();
            if (eventType.equals(IngestManager.IngestModuleEvent.DATA_ADDED.toString())) {
                /**
                 * This is a stop gap measure until a different way of handling
                 * the closing of cases is worked out. Currently, remote events
                 * may be received for a case that is already closed.
                 */
                try {
                    Case.getCurrentCase();
                    /**
                     * Due to some unresolved issues with how cases are closed,
                     * it is possible for the event to have a null oldValue if
                     * the event is a remote event.
                     */
                    final ModuleDataEvent event = (ModuleDataEvent) evt.getOldValue();
                    if (null != event && doNotShow.contains(event.getArtifactType()) == false) {
                        refresh(true);
                    }
                } catch (IllegalStateException notUsed) {
                    /**
                     * Case is closed, do nothing.
                     */
                }
            } else if (eventType.equals(IngestManager.IngestJobEvent.COMPLETED.toString())
                    || eventType.equals(IngestManager.IngestJobEvent.CANCELLED.toString())) {
                /**
                 * This is a stop gap measure until a different way of handling
                 * the closing of cases is worked out. Currently, remote events
                 * may be received for a case that is already closed.
                 */
                try {
                    Case.getCurrentCase();
                    refresh(true);
                } catch (IllegalStateException notUsed) {
                    /**
                     * Case is closed, do nothing.
                     */
                }
            } else if (eventType.equals(Case.Events.CURRENT_CASE.toString())) {
                // case was closed. Remove listeners so that we don't get called with a stale case handle
                if (evt.getNewValue() == null) {
                    removeNotify();
                    skCase = null;
                }
            }
        };

        @Override
        protected void addNotify() {
            IngestManager.getInstance().addIngestJobEventListener(pcl);
            IngestManager.getInstance().addIngestModuleEventListener(pcl);
            Case.addPropertyChangeListener(pcl);
        }

        @Override
        protected void removeNotify() {
            IngestManager.getInstance().removeIngestJobEventListener(pcl);
            IngestManager.getInstance().removeIngestModuleEventListener(pcl);
            Case.removePropertyChangeListener(pcl);
            typeNodeList.clear();
        }

        @Override
        protected boolean createKeys(List<BlackboardArtifact.ARTIFACT_TYPE> list) {
            if (skCase != null) {
                try {
                    List<BlackboardArtifact.ARTIFACT_TYPE> inUse = skCase.getBlackboardArtifactTypesInUse();
                    inUse.removeAll(doNotShow);
                    Collections.sort(inUse,
                            new Comparator<BlackboardArtifact.ARTIFACT_TYPE>() {
                                @Override
                                public int compare(BlackboardArtifact.ARTIFACT_TYPE a, BlackboardArtifact.ARTIFACT_TYPE b) {
                                    return a.getDisplayName().compareTo(b.getDisplayName());
                                }
                            });
                    list.addAll(inUse);

                    // the create node method will get called only for new types
                    // refresh the counts if we already created them from a previous update
                    for (BlackboardArtifact.ARTIFACT_TYPE art : inUse) {
                        TypeNode node = typeNodeList.get(art);
                        if (node != null) {
                            node.updateDisplayName();
                        }
                    }
                } catch (TskCoreException ex) {
                    Logger.getLogger(TypeFactory.class.getName()).log(Level.SEVERE, "Error getting list of artifacts in use: " + ex.getLocalizedMessage()); //NON-NLS
                }
            }
            return true;
        }

        @Override
        protected Node createNodeForKey(BlackboardArtifact.ARTIFACT_TYPE key) {
            TypeNode node = new TypeNode(key);
            typeNodeList.put(key, node);
            return node;
        }
    }

    /**
     * Node encapsulating blackboard artifact type. This is used on the
     * left-hand navigation side of the Autopsy UI as the parent node for all of
     * the artifacts of a given type. Its children will be
     * BlackboardArtifactNode objects.
     */
    public class TypeNode extends DisplayableItemNode {

        private BlackboardArtifact.ARTIFACT_TYPE type;
        private long childCount = 0;

        TypeNode(BlackboardArtifact.ARTIFACT_TYPE type) {
            super(Children.create(new ArtifactFactory(type), true), Lookups.singleton(type.getDisplayName()));
            super.setName(type.getLabel());
            this.type = type;
            this.setIconBaseWithExtension("org/sleuthkit/autopsy/images/" + getIcon(type)); //NON-NLS
            updateDisplayName();
        }

        final void updateDisplayName() {
            if (skCase == null) {
                return;
            }

            // NOTE: This completely destroys our lazy-loading ideal
            //    a performance increase might be had by adding a 
            //    "getBlackboardArtifactCount()" method to skCase
            try {
                this.childCount = skCase.getBlackboardArtifactsTypeCount(type.getTypeID());
            } catch (TskException ex) {
                Logger.getLogger(TypeNode.class.getName())
                        .log(Level.WARNING, "Error getting child count", ex); //NON-NLS
            }
            super.setDisplayName(type.getDisplayName() + " (" + childCount + ")");
        }

        @Override
        protected Sheet createSheet() {
            Sheet s = super.createSheet();
            Sheet.Set ss = s.get(Sheet.PROPERTIES);
            if (ss == null) {
                ss = Sheet.createPropertiesSet();
                s.put(ss);
            }

            ss.put(new NodeProperty<>(NbBundle.getMessage(this.getClass(), "ArtifactTypeNode.createSheet.artType.name"),
                    NbBundle.getMessage(this.getClass(), "ArtifactTypeNode.createSheet.artType.displayName"),
                    NbBundle.getMessage(this.getClass(), "ArtifactTypeNode.createSheet.artType.desc"),
                    type.getDisplayName()));

            ss.put(new NodeProperty<>(NbBundle.getMessage(this.getClass(), "ArtifactTypeNode.createSheet.childCnt.name"),
                    NbBundle.getMessage(this.getClass(), "ArtifactTypeNode.createSheet.childCnt.displayName"),
                    NbBundle.getMessage(this.getClass(), "ArtifactTypeNode.createSheet.childCnt.desc"),
                    childCount));

            return s;
        }

        @Override
        public <T> T accept(DisplayableItemNodeVisitor<T> v) {
            return v.visit(this);
        }

        // @@@ TODO: Merge with BlackboartArtifactNode.getIcon()
        private String getIcon(BlackboardArtifact.ARTIFACT_TYPE type) {
            switch (type) {
                case TSK_WEB_BOOKMARK:
                    return "bookmarks.png"; //NON-NLS
                case TSK_WEB_COOKIE:
                    return "cookies.png"; //NON-NLS
                case TSK_WEB_HISTORY:
                    return "history.png"; //NON-NLS
                case TSK_WEB_DOWNLOAD:
                    return "downloads.png"; //NON-NLS
                case TSK_INSTALLED_PROG:
                    return "programs.png"; //NON-NLS
                case TSK_RECENT_OBJECT:
                    return "recent_docs.png"; //NON-NLS
                case TSK_DEVICE_ATTACHED:
                    return "usb_devices.png"; //NON-NLS
                case TSK_WEB_SEARCH_QUERY:
                    return "searchquery.png"; //NON-NLS
                case TSK_METADATA_EXIF:
                    return "camera-icon-16.png"; //NON-NLS
                case TSK_EMAIL_MSG:
                    return "mail-icon-16.png"; //NON-NLS
                case TSK_CONTACT:
                    return "contact.png"; //NON-NLS
                case TSK_MESSAGE:
                    return "message.png"; //NON-NLS
                case TSK_CALLLOG:
                    return "calllog.png"; //NON-NLS
                case TSK_CALENDAR_ENTRY:
                    return "calendar.png"; //NON-NLS
                case TSK_SPEED_DIAL_ENTRY:
                    return "speeddialentry.png"; //NON-NLS
                case TSK_BLUETOOTH_PAIRING:
                    return "bluetooth.png"; //NON-NLS
                case TSK_GPS_BOOKMARK:
                    return "gpsfav.png"; //NON-NLS
                case TSK_GPS_LAST_KNOWN_LOCATION:
                    return "gps-lastlocation.png"; //NON-NLS
                case TSK_GPS_SEARCH:
                    return "gps-search.png"; //NON-NLS
                case TSK_SERVICE_ACCOUNT:
                    return "account-icon-16.png"; //NON-NLS
                case TSK_ENCRYPTION_DETECTED:
                    return "encrypted-file.png"; //NON-NLS
                case TSK_EXT_MISMATCH_DETECTED:
                    return "mismatch-16.png"; //NON-NLS
                case TSK_OS_INFO:
                    return "computer.png"; //NON-NLS
                case TSK_FACE_DETECTED:
                    return "face.png"; //NON-NLS

            }
            return "artifact-icon.png"; //NON-NLS
        }

        @Override
        public boolean isLeafTypeNode() {
            return true;
        }
    }

    /**
     * Creates children for a given artifact type
     */
    private class ArtifactFactory extends ChildFactory.Detachable<BlackboardArtifact> {

        private BlackboardArtifact.ARTIFACT_TYPE type;

        public ArtifactFactory(BlackboardArtifact.ARTIFACT_TYPE type) {
            super();
            this.type = type;
        }

        private final PropertyChangeListener pcl = new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                String eventType = evt.getPropertyName();
                if (eventType.equals(IngestManager.IngestModuleEvent.DATA_ADDED.toString())) {
                    /**
                     * Checking for a current case is a stop gap measure until a
                     * different way of handling the closing of cases is worked
                     * out. Currently, remote events may be received for a case
                     * that is already closed.
                     */
                    try {
                        Case.getCurrentCase();
                        /**
                         * Even with the check above, it is still possible that
                         * the case will be closed in a different thread before
                         * this code executes. If that happens, it is possible
                         * for the event to have a null oldValue.
                         */
                        final ModuleDataEvent event = (ModuleDataEvent) evt.getOldValue();
                        if (null != event && event.getArtifactType() == type) {
                            refresh(true);
                        }
                    } catch (IllegalStateException notUsed) {
                        /**
                         * Case is closed, do nothing.
                         */
                    }
                } else if (eventType.equals(IngestManager.IngestJobEvent.COMPLETED.toString())
                        || eventType.equals(IngestManager.IngestJobEvent.CANCELLED.toString())) {
                    /**
                     * Checking for a current case is a stop gap measure until a
                     * different way of handling the closing of cases is worked
                     * out. Currently, remote events may be received for a case
                     * that is already closed.
                     */
                    try {
                        Case.getCurrentCase();
                        refresh(true);
                    } catch (IllegalStateException notUsed) {
                        /**
                         * Case is closed, do nothing.
                         */
                    }
                }
            }
        };

        @Override
        protected void addNotify() {
            IngestManager.getInstance().addIngestJobEventListener(pcl);
            IngestManager.getInstance().addIngestModuleEventListener(pcl);
        }

        @Override
        protected void removeNotify() {
            IngestManager.getInstance().removeIngestJobEventListener(pcl);
            IngestManager.getInstance().removeIngestModuleEventListener(pcl);
        }

        @Override
        protected boolean createKeys(List<BlackboardArtifact> list) {
            if (skCase != null) {
                try {
                    List<BlackboardArtifact> arts = skCase.getBlackboardArtifacts(type.getTypeID());
                    list.addAll(arts);
                } catch (TskException ex) {
                    Logger.getLogger(ArtifactFactory.class.getName()).log(Level.SEVERE, "Couldn't get blackboard artifacts from database", ex); //NON-NLS
                }
            }
            return true;
        }

        @Override
        protected Node createNodeForKey(BlackboardArtifact key) {
            return new BlackboardArtifactNode(key);
        }
    }
}
