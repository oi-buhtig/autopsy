/*
 * Autopsy Forensic Browser
 *
 * Copyright 2013-14 Basis Technology Corp.
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
package org.sleuthkit.autopsy.imagegallery.gui.navpanel;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.scene.control.TreeItem;
import org.apache.commons.lang3.StringUtils;
import org.sleuthkit.autopsy.coreutils.ThreadConfined;
import org.sleuthkit.autopsy.imagegallery.datamodel.grouping.DrawableGroup;

/**
 * A node in the nav/hash tree. Manages inserts and removals. Has parents and
 * children. Does not have graphical properties these are configured in
 * {@link GroupTreeCell}. Each GroupTreeItem has a TreeNode which has a path
 * segment and may or may not have a group
 */
class GroupTreeItem extends TreeItem<TreeNode> implements Comparable<GroupTreeItem> {

    static final Executor treeInsertTread = Executors.newSingleThreadExecutor();

    GroupTreeItem getTreeItemForGroup(DrawableGroup grouping) {
        if (Objects.equals(getValue().getGroup(), grouping)) {
            return this;
        } else {
            for (GroupTreeItem child : childMap.values()) {

                GroupTreeItem val = child.getTreeItemForGroup(grouping);
                if (val != null) {
                    return val;
                }
            }
        }
        return null;
    }

    /**
     * maps a path segment to the child item of this item with that path segment
     */
    private final Map<String, GroupTreeItem> childMap = new HashMap<>();
    /**
     * the comparator if any used to sort the children of this item
     */
    private TreeNodeComparators comp;

    public GroupTreeItem(String t, DrawableGroup g, TreeNodeComparators comp) {
        super(new TreeNode(t, g));
        this.comp = comp;
    }

    /**
     * Returns the full absolute path of this level in the tree
     *
     * @return the full absolute path of this level in the tree
     */
    public String getAbsolutePath() {
        if (getParent() != null) {
            return ((GroupTreeItem) getParent()).getAbsolutePath() + getValue().getPath() + "/";
        } else {
            return getValue().getPath() + "/";
        }
    }

    /**
     * Recursive method to add a grouping at a given path.
     *
     * @param path Full path (or subset not yet added) to add
     * @param g    Group to add
     * @param tree True if it is part of a tree (versus a list)
     */
    synchronized void insert(List<String> path, DrawableGroup g, Boolean tree) {
        if (tree) {
            // Are we at the end of the recursion?
            if (path.isEmpty()) {
                getValue().setGroup(g);
            } else {
                String prefix = path.get(0);

                GroupTreeItem prefixTreeItem = childMap.computeIfAbsent(prefix, (String t) -> {
                    final GroupTreeItem newTreeItem = new GroupTreeItem(t, null, comp);

                    Platform.runLater(() -> {
                        getChildren().add(newTreeItem);
                    });
                    return newTreeItem;
                });

                // recursively go into the path
                treeInsertTread.execute(() -> {
                    prefixTreeItem.insert(path.subList(1, path.size()), g, tree);
                });

            }
        } else {
            String join = StringUtils.join(path, "/");
            //flat list
            childMap.computeIfAbsent(join, (String t) -> {
                final GroupTreeItem newTreeItem = new GroupTreeItem(t, g, comp);
                newTreeItem.setExpanded(true);

                Platform.runLater(() -> {
                    getChildren().add(newTreeItem);
                    if (comp != null) {
                        FXCollections.sort(getChildren(), comp);
                    }
                });
                return newTreeItem;
            });
        }
    }

    @Override
    public int compareTo(GroupTreeItem o) {
        return comp.compare(this, o);
    }

    synchronized GroupTreeItem getTreeItemForPath(List<String> path) {

        if (path.isEmpty()) {
            // end of recursion
            return this;
        } else {
            String prefix = path.get(0);

            GroupTreeItem prefixTreeItem = childMap.get(prefix);
            if (prefixTreeItem == null) {
                // @@@ ERROR;
                return null;
            }

            // recursively go into the path
            return prefixTreeItem.getTreeItemForPath(path.subList(1, path.size()));
        }
    }

    synchronized void removeFromParent() {
        final GroupTreeItem parent = (GroupTreeItem) getParent();
        if (parent != null) {
            parent.childMap.remove(getValue().getPath());

            Platform.runLater(() -> {
                parent.getChildren().removeAll(Collections.singleton(GroupTreeItem.this));
            });

            if (parent.childMap.isEmpty()) {
                parent.removeFromParent();
            }
        }
    }

    /**
     * must be performed on fx thread because it manipualtes the tree directly.
     *
     * @param newComp
     */
    @ThreadConfined(type = ThreadConfined.ThreadType.JFX)
    synchronized void resortChildren(TreeNodeComparators newComp) {
        this.comp = newComp;
        FXCollections.sort(getChildren(), comp);
        for (GroupTreeItem ti : childMap.values()) {
            ti.resortChildren(comp);
        }
    }
}
