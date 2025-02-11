/*
 * Autopsy Forensic Browser
 *
 * Copyright 2014-15 Basis Technology Corp.
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
package org.sleuthkit.autopsy.timeline.actions;

import javafx.beans.binding.BooleanBinding;
import javafx.event.ActionEvent;
import org.controlsfx.control.action.Action;
import org.openide.util.NbBundle;
import org.sleuthkit.autopsy.timeline.TimeLineController;
import org.sleuthkit.autopsy.timeline.datamodel.FilteredEventsModel;

/**
 * Action that resets the filters to their initial/default state.
 */
public class ResetFilters extends Action {

    private FilteredEventsModel eventsModel;

    public ResetFilters(final TimeLineController controller) {
        super(NbBundle.getMessage(ResetFilters.class, "DefaultFilters.action.name.text"));
        eventsModel = controller.getEventsModel();
        disabledProperty().bind(new BooleanBinding() {
            {
                bind(eventsModel.zoomParametersProperty());
            }

            @Override
            protected boolean computeValue() {
                return eventsModel.zoomParametersProperty().getValue().getFilter().equals(eventsModel.getDefaultFilter());
            }
        });
        setEventHandler((ActionEvent t) -> {
            controller.applyDefaultFilters();
        });
    }
}
