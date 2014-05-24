/*
 * ------------------------------------------------------------------------
 *  Copyright by KNIME GmbH, Konstanz, Germany
 *  Website: http://www.knime.org; Email: contact@knime.org
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
 *  KNIME and ECLIPSE being a combined program, KNIME GMBH herewith grants
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
 * ------------------------------------------------------------------------
 */
package org.knime.core.node.workflow;

import static org.knime.core.node.workflow.InternalNodeContainerState.CONFIGURED;
import static org.knime.core.node.workflow.InternalNodeContainerState.EXECUTED;
import static org.knime.core.node.workflow.InternalNodeContainerState.IDLE;
/**
 *
 * @author wiswedel, University of Konstanz
 */
public class Bug4185_ResetComplexFlow extends WorkflowTestCase {

    private NodeID m_javaEditStart;
    private NodeID m_javaEditEnd;
    private NodeID m_metaMiddle;

    /** {@inheritDoc} */
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        NodeID baseID = loadAndSetWorkflow();
        m_javaEditStart = new NodeID(baseID, 610);
        m_javaEditEnd = new NodeID(baseID, 705);
        m_metaMiddle = new NodeID(baseID, 704);
    }

    public void testExecuteAllAndReset() throws Exception {
        executeAllAndWait();
        checkState(getManager(), EXECUTED);
        getManager().getParent().resetAndConfigureNode(getManager().getID());
        checkState(getManager(), CONFIGURED);
        checkState(m_javaEditStart, CONFIGURED);
        checkState(m_javaEditEnd, CONFIGURED);
    }

    public void testExecuteAllResetStart() throws Exception {
        executeAllAndWait();
        checkState(getManager(), EXECUTED);
        getManager().resetAndConfigureNode(m_javaEditStart);
        checkState(getManager(), CONFIGURED);
        checkState(m_javaEditStart, CONFIGURED);
        checkState(m_javaEditEnd, CONFIGURED);
    }
    
    public void testDeleteConnectionToMetaExecuteFirst() throws Exception {
        internalTestDeleteConnectionToMeta(true);
    }
        
    public void testDeleteConnectionToMetaDontExecute() throws Exception {
        internalTestDeleteConnectionToMeta(false);
    }
    
    private void internalTestDeleteConnectionToMeta(final boolean executeFirst) throws Exception {
        if (executeFirst) {
            executeAllAndWait();
            checkState(getManager(), EXECUTED);
        }
        deleteConnection(m_metaMiddle, 0);
        checkState(getManager(), IDLE);
        checkState(m_javaEditStart, CONFIGURED, EXECUTED);
        checkState(m_javaEditEnd, IDLE);
    }
    

}
