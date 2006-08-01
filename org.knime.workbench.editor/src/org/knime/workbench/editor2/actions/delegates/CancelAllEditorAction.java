/* 
 * -------------------------------------------------------------------
 * This source code, its documentation and all appendant files
 * are protected by copyright law. All rights reserved.
 * 
 * Copyright, 2003 - 2004
 * Universitaet Konstanz, Germany.
 * Lehrstuhl fuer Angewandte Informatik
 * Prof. Dr. Michael R. Berthold
 * 
 * You may not modify, publish, transmit, transfer or sell, reproduce,
 * create derivative works from, distribute, perform, display, or in
 * any way exploit any of the content, in whole or in part, except as
 * otherwise expressly permitted in writing by the copyright owner.
 * -------------------------------------------------------------------
 * 
 * History
 *   10.11.2005 (Florian Georg): created
 */
package org.knime.workbench.editor2.actions.delegates;

import org.knime.workbench.editor2.WorkflowEditor;
import org.knime.workbench.editor2.actions.AbstractNodeAction;
import org.knime.workbench.editor2.actions.CancelAllAction;

/**
 * Editor action for canceling all running nodes.
 * 
 * @author Christoph Sieb, University of Konstanz
 */
public class CancelAllEditorAction extends AbstractEditorAction {

    /**
     * @see org.knime.workbench.editor2.actions.delegates.AbstractEditorAction
     *      #createAction(org.knime.workbench.editor2.WorkflowEditor)
     */
    @Override
    protected AbstractNodeAction createAction(final WorkflowEditor editor) {
        return new CancelAllAction(editor);
    }

}
