/*
 * ------------------------------------------------------------------------
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
 * ------------------------------------------------------------------------
 */
package org.knime.core.node.workflow;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.awaitility.Awaitility;
import org.junit.Test;
import org.knime.core.node.workflow.NodeID.NodeIDSuffix;
import org.knime.core.node.workflow.WebResourceController.WizardPageContent;

/**
 * Test for the (partial) single page re-execution of pages in a workflow which
 * is in wizard execution.
 * 
 * @author Martin Horn, KNIME GmbH, Konstanz, Germany
 */
public class EnhWEBP674_PartialWizardPageReexecution extends WorkflowTestCase {

	/**
	 * Tests general partial re-execution of a wizard page, including exception
	 * handling etc.
	 * 
	 * @throws Exception
	 */
	@Test
	public void testPartialWizardPageReexecution() throws Exception {
		loadAndSetWorkflow();
		WorkflowManager wfm = getManager();
		SubNodeContainer page1 = (SubNodeContainer) wfm.getNodeContainer(wfm.getID().createChild(7));
		SubNodeContainer page2 = (SubNodeContainer) wfm.getNodeContainer(wfm.getID().createChild(6));

		WizardExecutionController wec = wfm.setAndGetWizardExecutionController();

		IllegalStateException exception = assertThrows(IllegalStateException.class,
				() -> wec.getSinglePageControllerForCurrentPage());
		assertThat(exception.getMessage(), is("No current wizard page"));

		// advance to first page
		wec.stepFirst();
		waitForPage(page1);
		assertThat(wec.hasCurrentWizardPage(), is(true));

		// check simple single page re-execution
		SinglePageWebResourceController sec = wec.getSinglePageControllerForCurrentPage();
		sec.reexecuteSinglePage(createNodeIDSuffix(7), createWizardPageInput(0), false);
		waitForPage(page1);
		assertThat(wec.hasCurrentWizardPage(), is(true));
		WizardPageContent pageContent = wec.getCurrentWizardPage();
		assertThat(pageContent.getPageMap().size(), is(4));
		assertThat(pageContent.getInfoMap().size(), is(4));
		// make sure that sec and wec return the same wizard page
		assertThat(sec.getWizardPage().getPageMap(), is(pageContent.getPageMap()));

		// check single page re-execution with the current page still containing
		// executing nodes
		sec.reexecuteSinglePage(createNodeIDSuffix(7), createWizardPageInput(400000), false);
		assertThat(wec.hasCurrentWizardPage(), is(true));
		Awaitility.await().atMost(5, TimeUnit.SECONDS).pollInterval(100, TimeUnit.MILLISECONDS).untilAsserted(() -> {
			WizardPageContent pc = wec.getCurrentWizardPage();
			assertThat(pc.getPageMap().size(), is(3));
		});
		pageContent = wec.getCurrentWizardPage();
		assertThat(pageContent.getInfoMap().size(), is(4));
		assertTrue(pageContent.getInfoMap().get(createNodeIDSuffix(7, 0, 3)).getNodeState().isWaitingToBeExecuted());

		// try to re-execute single page, load new values and step next while the
		// single-page re-execution of the current page is still in progress
		exception = assertThrows(IllegalStateException.class,
				() -> sec.reexecuteSinglePage(createNodeIDSuffix(7), createWizardPageInput(0), false));
		assertThat(exception.getMessage(), is("Page can't be re-executed: execution in progress"));
		exception = assertThrows(IllegalStateException.class,
				() -> wec.loadValuesIntoCurrentPage(createWizardPageInput(0)));
		assertThat(exception.getMessage(), is("Action not allowed. Single page re-execution is in progress."));
		exception = assertThrows(IllegalStateException.class, () -> wec.stepNext());
		assertThat(exception.getMessage(), is("Action not allowed. Single page re-execution is in progress."));

		// just cancel execution and bring the current page into an executed state
		wfm.cancelExecution();
		waitForSinglePageNotExecutingAnymore(sec);
		sec.reexecuteSinglePage(createNodeIDSuffix(7), createWizardPageInput(0), false);
		waitForSinglePageNotExecutingAnymore(sec);

		// check that the single page execution controller for the last page is invalid
		wec.loadValuesIntoCurrentPage(createWizardPageInput(0));
		wec.stepNext();
		waitForPage(page2);
		exception = assertThrows(IllegalStateException.class,
				() -> sec.reexecuteSinglePage(new NodeIDSuffix(new int[] { 7 }), createWizardPageInput(0), false));
		assertThat(exception.getMessage(), is(
				"Invalid single page controller. Page doesn't match with the current page of the associated wizard executor."));

		// check reset to previous page while in single page re-execution
		SinglePageWebResourceController sec2 = wec.getSinglePageControllerForCurrentPage();
		sec2.reexecuteSinglePage(createNodeIDSuffix(6), Collections.emptyMap(), false);
		Awaitility.await().atMost(5, TimeUnit.SECONDS).pollInterval(100, TimeUnit.MILLISECONDS).until(() -> {
			WizardPageContent pc = wec.getCurrentWizardPage();
			return pc.getInfoMap().keySet().contains(createNodeIDSuffix(6, 0, 5));
		});
		wfm.cancelExecution();
		waitForSinglePageNotExecutingAnymore(sec2);
		wec.stepBack();
		pageContent = wec.getCurrentWizardPage();
		assertTrue(pageContent.getInfoMap().containsKey(createNodeIDSuffix(7, 0, 3)));
		assertThat(pageContent.getPageMap().size(), is(4));
	}

	/**
	 * @param intInput the intInput controls the num seconds of a 'Wait ...' node
	 */
	private static Map<String, String> createWizardPageInput(int intInput) {
		Map<String, String> res = new HashMap<>();
		res.put("7:0:7", "{\"@class\":\"org.knime.js.base.node.base.input.integer.IntegerNodeValue\",\"integer\":"
				+ intInput + "}");
		res.put("7:0:3",
				"{\"@class\":\"org.knime.js.base.node.viz.pagedTable.PagedTableViewValue\",\"publishSelection\":true,\"subscribeSelection\":true,\"publishFilter\":true,\"subscribeFilter\":true,\"pageSize\":0,\"currentPage\":0,\"hideUnselected\":false,\"selection\":null,\"selectAll\":false,\"selectAllIndeterminate\":false,\"filterString\":null,\"columnFilterStrings\":null,\"currentOrder\":[]}");
		res.put("7:0:2",
				"{\"@class\":\"org.knime.js.base.node.base.input.string.StringNodeValue\",\"string\":\"test\"}}");
		return res;
	}

	private static void waitForPage(SubNodeContainer page) {
		Awaitility.await().atMost(5, TimeUnit.SECONDS).pollInterval(10, TimeUnit.MILLISECONDS)
				.until(() -> page.getNodeContainerState().isExecuted());
	}

	private static void waitForSinglePageNotExecutingAnymore(SinglePageWebResourceController sec) {
		Awaitility.await().atMost(5, TimeUnit.SECONDS).pollInterval(100, TimeUnit.MILLISECONDS)
				.until(() -> !sec.isPageReexecutionInProgress());
	}

	private static NodeIDSuffix createNodeIDSuffix(int... ids) {
		return new NodeIDSuffix(ids);
	}

	@Override
	public void tearDown() throws Exception {
		getManager().cancelExecution();
		super.tearDown();
	}

}
