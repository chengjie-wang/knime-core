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
package org.knime.core.data.container.blobs;

import java.util.ArrayList;
import java.util.Collection;

import org.knime.core.data.DataCell;
import org.knime.core.data.DataColumnSpecCreator;
import org.knime.core.data.DataTableSpec;
import org.knime.core.data.DataType;
import org.knime.core.data.collection.CollectionCellFactory;
import org.knime.core.data.collection.ListCell;
import org.knime.core.data.def.DefaultRow;
import org.knime.core.data.def.StringCell;
import org.knime.core.node.BufferedDataContainer;
import org.knime.core.node.BufferedDataTable;
import org.knime.core.node.ExecutionContext;
import org.knime.testing.data.blob.LargeBlobCell;

/**
 * Creates table with set cell, whereby some elements are singletons and others
 * are new blob cells.
 * @author wiswedel, University of Konstanz
 */
public class BlobsInSetCellPartiallySingletonsWorkflowTest extends AbstractBlobsInWorkflowTest {

    private final int ROW_COUNT = 20;
    
    /** {@inheritDoc} */
    @Override
    protected BufferedDataTable createBDT(final ExecutionContext exec) {
        DataType t = ListCell.getCollectionType(
                DataType.getType(DataCell.class));
        BufferedDataContainer c = exec.createDataContainer(
                new DataTableSpec(new DataColumnSpecCreator(
                        "Sequence", t).createSpec()));
        LargeBlobCell singleton = new LargeBlobCell("singleton");
        for (int i = 0; i < ROW_COUNT; i++) {
            String s = "someName_" + i;
            // every other a ordinary string cell
            Collection<DataCell> cells = new ArrayList<DataCell>();
            for (int j = 0; j < 4; j++) {
                String val = "Row_" + i + "; Cell index " + j;
                switch (j) {
                case 0:
                    cells.add(singleton);
                    break;
                case 1:
                case 3:
                    cells.add(new StringCell(val));
                    break;
                case 2:
                    cells.add(new LargeBlobCell(val));
                    break;
                default:
                    fail("invalid index");
                }
            }
            ListCell cell = CollectionCellFactory.createListCell(cells);
            c.addRowToTable(new DefaultRow(s, cell));
        }
        c.close();
        return c.getTable();
    }

    /** {@inheritDoc} */
    @Override
    protected long getApproximateSize() {
        return LargeBlobCell.SIZE_OF_CELL * (1 + ROW_COUNT);
    }

}
