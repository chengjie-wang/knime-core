/*
 * ------------------------------------------------------------------------
 *
 *  Copyright by KNIME AG, Zurich, Switzerland
 *  Website: http://www.knime.com; Email: contact@knime.com
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License, Version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, see <http://www.gnu.org/licenses>.
 *
 *  Additional permission under GNU GPL version 3 section 7:
 *
 *  KNIME interoperates with ECLIPSE solely via ECLIPSE's plug-in APIs.
 *  Hence, KNIME and ECLIPSE are both independent programs and are not
 *  derived from each other. Should, however, the interpretation of the
 *  GNU GPL Version 3 ("License") under any applicable laws result in
 *  KNIME and ECLIPSE being a combined program, KNIME AG herewith grants
 *  you the additional permission to use and propagate KNIME together with
 *  ECLIPSE with only the license terms in place for ECLIPSE applying to
 *  ECLIPSE and the GNU GPL Version 3 applying for KNIME, provided the
 *  license terms of ECLIPSE themselves allow for the respective use and
 *  propagation of ECLIPSE together with KNIME.
 *
 *  Additional permission relating to nodes for KNIME that extend the Node
 *  Extension (and in particular that are based on subclasses of NodeModel,
 *  NodeDialog, and NodeView) and that only interoperate with KNIME through
 *  standard APIs ("Nodes"):
 *  Nodes are deemed to be separate and independent programs and to not be
 *  covered works.  Notwithstanding anything to the contrary in the
 *  License, the License does not apply to Nodes, you are not required to
 *  license Nodes under the License, and you are granted a license to
 *  prepare and propagate Nodes, in each case even if such Nodes are
 *  propagated with or for interoperation with KNIME.  The owner of a Node
 *  may freely choose the license terms applicable to such Node, including
 *  when such Node is propagated with or for interoperation with KNIME.
 * ---------------------------------------------------------------------
 *
 * History
 *   24 Mar 2017 (albrecht): created
 */
package org.knime.core.node.workflow;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.knime.core.node.CanceledExecutionException;
import org.knime.core.node.ExecutionMonitor;
import org.knime.core.node.interactive.ViewRequestHandlingException;
import org.knime.core.node.util.CheckUtils;
import org.knime.core.node.web.ValidationError;
import org.knime.core.node.web.WebViewContent;
import org.knime.core.node.wizard.WizardViewResponse;
import org.knime.core.node.workflow.NodeID.NodeIDSuffix;

/**
 * A utility class received from the workflow manager that allows controlling wizard execution and combined view creation on a single subnode.
 *
 * <p>Do not use, no public API.
 *
 * @author Christian Albrecht, KNIME.com GmbH, Konstanz, Germany
 * @since 3.4
 *
 * @noreference This class is not intended to be referenced by clients.
 * @noinstantiate This class is not intended to be instantiated by clients.
 * @noextend This class is not intended to be subclassed by clients.
 */
public class SinglePageWebResourceController extends WebResourceController {

    private final NodeID m_nodeID;
    private final boolean m_isInWizardExecution;
    private boolean m_isInvalid = false;

    /**
     * Creates a new controller.
     *
     * @param manager the workflow this controller is created for
     * @param nodeID the id of the component representing the page this controller is created for
     */
    public SinglePageWebResourceController(final WorkflowManager manager, final NodeID nodeID) {
        this(manager, nodeID, false);
    }

    /**
     * Creates a new controller.
     *
     * @param manager the workflow this controller is created for
     * @param nodeID the id of the component representing the page this controller is created for
     * @param isInWizardExecution if <code>true</code> this controller is created for a page which is also part of a
     *            workflow which is in wizard execution; <code>false</code> if this controller is created for a single
     *            page which is not part of a workflow in wizard execution
     */
    SinglePageWebResourceController(final WorkflowManager manager, final NodeID nodeID,
        final boolean isInWizardExecution) {
        super(manager);
        m_nodeID = nodeID;
        m_isInWizardExecution = isInWizardExecution;
    }

    /**
     * Checks different criteria to determine if a combined page view is available for a given subnode.
     * @return true, if a view on the subnode is available, false otherwise
     */
    public boolean isSubnodeViewAvailable() {
        return super.isSubnodeViewAvailable(m_nodeID);
    }

    /**
     * Gets the wizard page for a given node id. Throws exception if no wizard page available.
     * @return The wizard page for the given node id
     */
    public WizardPageContent getWizardPage() {
        WorkflowManager manager = m_manager;
        try (WorkflowLock lock = manager.lock()) {
            NodeContext.pushContext(manager);
            try {
                return getWizardPageInternal(m_nodeID);
            } finally {
                NodeContext.removeLastContext();
            }
        }
    }

    /**
     * Retrieves all available view values from the available wizard nodes for the given node id.
     * @return a map from NodeID to view value for all appropriate wizard nodes.
     */
    public Map<NodeIDSuffix, WebViewContent> getWizardPageViewValueMap() {
        WorkflowManager manager = m_manager;
        try (WorkflowLock lock = manager.lock()) {
            NodeContext.pushContext(manager);
            try {
                return getWizardPageViewValueMapInternal(m_nodeID);
            } finally {
                NodeContext.removeLastContext();
            }
        }
    }

    /**
     * Tries to load a map of view values to all appropriate views contained in the given subnode.
     * @param viewContentMap the values to validate
     * @param validate true, if validation is supposed to be done before applying the values, false otherwise
     * @param useAsDefault true, if the given value map is supposed to be applied as new node defaults (overwrite node settings), false otherwise (apply temporarily)
     * @return an empty map if validation succeeds, map of errors otherwise
     */
    public Map<String, ValidationError> loadValuesIntoPage(final Map<String, String> viewContentMap, final boolean validate, final boolean useAsDefault) {
        WorkflowManager manager = m_manager;
        try (WorkflowLock lock = manager.lock()) {
            doBeforePageChange();
            NodeContext.pushContext(manager);
            try {
                return loadValuesIntoPageInternal(viewContentMap, m_nodeID, validate, useAsDefault);
            } finally {
                NodeContext.removeLastContext();
            }
        }
    }

    /**
     * Processes a request issued by a view by calling the appropriate methods on the corresponding node
     * model and returns the rendered response.
     *
     * @param nodeID The node id of the node that the request belongs to.
     * @param viewRequest The JSON serialized view request
     * @param exec the execution monitor to set progress and check possible cancellation
     * @return a {@link CompletableFuture} object, which can resolve a {@link WizardViewResponse}.
     * @throws ViewRequestHandlingException If the request handling or response generation fails for any
     * reason.
     * @throws InterruptedException If the thread handling the request is interrupted.
     * @throws CanceledExecutionException If the handling of the request was canceled e.g. by user
     * intervention.
     * @since 3.7
     */
    public WizardViewResponse processViewRequest(final String nodeID, final String viewRequest,
        final ExecutionMonitor exec)
        throws ViewRequestHandlingException, InterruptedException, CanceledExecutionException {
        WorkflowManager manager = m_manager;
        try (WorkflowLock lock = manager.lock()) {
            doBeforePageChange();
            NodeContext.pushContext(manager);
            try {
                return processViewRequestInternal(m_nodeID, nodeID, viewRequest, exec);
            } finally {
                NodeContext.removeLastContext();
            }
        }
    }

    @Override
    boolean isResetDownstreamNodesWhenApplyingViewValue() {
        return true;
    }

    @Override
    void stateCheckDownstreamNodesWhenApplyingViewValues(final SubNodeContainer snc, final NodeContainer downstreamNC) {
        // no check needed, done in #stateCheckWhenApplyingViewValues
    }

    @Override
    void stateCheckWhenApplyingViewValues(final SubNodeContainer snc) {
        if (!m_isInWizardExecution) {
            NodeID id = snc.getID();
            WorkflowManager parent = snc.getParent();
            CheckUtils.checkState(parent.canResetNode(id), "Can't reset component%s",
                parent.hasSuccessorInProgress(id) ? " - some downstream nodes are still executing" : "");
        }
    }

    /**
     * Validates a given set of serialized view values for the given subnode.
     * @param viewContentMap the values to validate
     * @return an empty map if validation succeeds, map of errors otherwise
     */
    public Map<String, ValidationError> validateViewValuesInPage(final Map<String, String> viewContentMap) {
        WorkflowManager manager = m_manager;
        try (WorkflowLock lock = manager.lock()) {
            doBeforePageChange();
            NodeContext.pushContext(manager);
            try {
                return validateViewValuesInternal(viewContentMap, m_nodeID, getWizardNodeSetForVerifiedID(m_nodeID));
            } finally {
                NodeContext.removeLastContext();
            }
        }
    }

    /**
     * Triggers workflow execution up until the given subnode.
     *
     * @deprecated for real re-execution use {@link #reexecuteSinglePage(Map, boolean, boolean)}.
     */
    @Deprecated
    public void reexecuteSinglePage() {
        final WorkflowManager manager = m_manager;
        try (WorkflowLock lock = manager.lock()) {
            checkDiscard();
            m_manager.executeUpToHere(m_nodeID);
        }
    }

    /**
     * Re-executes the page associated with this controller. I.e. resets, loads the provided values and triggers
     * workflow execution up until the given page (i.e. component). Note: if validation is desired and it fails, the
     * page won't be re-executed.
     *
     * @param valueMap the values to load before re-execution, a map from {@link NodeIDSuffix} strings to parsed view
     *            values
     * @param validate if <code>true</code>, validation will be done before applying the values, otherwise
     *            <code>false</code>
     * @param useAsDefault true, if values are supposed to be applied as new defaults, false if applied temporarily
     *
     * @return empty map if validation succeeds, map of errors otherwise
     */
    public Map<String, ValidationError> reexecuteSinglePage(final Map<String, String> valueMap,
        final boolean validate, final boolean useAsDefault) {
        final WorkflowManager manager = m_manager;
        try (WorkflowLock lock = manager.lock()) {
            doBeforePageChange();
            Map<String, ValidationError> validationResult = loadValuesIntoPage(valueMap, validate, useAsDefault);
            if (validationResult.isEmpty()) {
                m_manager.executeUpToHere(m_nodeID);
            }
            return validationResult;
        }
    }

    /**
     * Re-executes the page associated with this controller. I.e. resets, loads the provided values and triggers
     * workflow execution up until the given page (i.e. component). Note: if validation is desired and it fails, the
     * page won't be re-executed.
     *
     * @param viewToReset the view on the wizard page that shall be reset
     * @param valueMap the values to load before re-execution, a map from {@link NodeIDSuffix} strings to parsed view
     *            values
     * @param validate if <code>true</code>, validation will be done before applying the values, otherwise
     *            <code>false</code>
     * @throws IllegalArgumentException if the provided view node-id is not part of the wizard page
     *
     * @return empty map if validation succeeds, map of errors otherwise
     */
    public Map<String, ValidationError> reexecuteSinglePage(final NodeIDSuffix viewToReset,
        final Map<String, String> valueMap, final boolean validate) {
        try (WorkflowLock lock = m_manager.lock()) {
            doBeforePageChange();
            WorkflowManager pageWfm = ((SubNodeContainer)m_manager.getNodeContainer(m_nodeID)).getWorkflowManager();
            NodeContainer nodeToReset = pageWfm.getNodeContainer(viewToReset.prependParent(pageWfm.getID()));
            Collection<NodeContainer> nodesToBeReexecuted = getNodesToBeReexecuted(nodeToReset);
            Map<String, String> filteredViewValues =
                filterViewValues(nodesToBeReexecuted, m_manager.getID(), valueMap);
            Map<String, ValidationError> validationResult = loadValuesIntoPage(filteredViewValues, validate, false);
            if (validationResult.isEmpty()) {
                m_manager.executeUpToHere(m_nodeID);
            }
            return validationResult;
        }
    }

    private static Collection<NodeContainer> getNodesToBeReexecuted(final NodeContainer nodeToReset) {
        return nodeToReset.getParent().getNodeContainers(Collections.singleton(nodeToReset.getID()), nc -> false, false,
            true);
    }

    private static Map<String, String> filterViewValues(final Collection<NodeContainer> nodesToBeReexecuted,
        final NodeID parentID, final Map<String, String> viewValues) {
        HashSet<String> ids =
            nodesToBeReexecuted.stream().map(nc -> NodeIDSuffix.create(parentID, nc.getID()).toString())
                .collect(HashSet::new, HashSet::add, HashSet::addAll);
        return viewValues.entrySet().stream().filter(e -> ids.contains(e.getKey()))
            .collect(Collectors.toMap(Entry::getKey, Entry::getValue));
    }

    private void doBeforePageChange() {
        checkDiscard();
        if (m_isInWizardExecution) {
            if (m_isInvalid) {
                throw new IllegalStateException(
                    "Invalid single page controller. Page doesn't match with the current page of the associated wizard executor.");
            }
            if (isPageReexecutionInProgress()) {
                throw new IllegalStateException("Page can't be re-executed: execution in progress");
            }
        }
    }

    /**
     * Determines whether the controlled page is currently in re-execution.
     *
     * @return <code>true</code> if the page is in re-execution, otherwise <code>false</code>
     */
    boolean isPageReexecutionInProgress() {
        return m_manager.getNodeContainer(m_nodeID).getNodeContainerState().isExecutionInProgress();
    }

    /**
     * Invalidates the controller. Only has an effect if the controller is in a workflow which is in wizard execution
     * (see {@link #SinglePageWebResourceController(WorkflowManager, NodeID, boolean)}.
     */
    void invalidate() {
        m_isInvalid = true;
    }

}
