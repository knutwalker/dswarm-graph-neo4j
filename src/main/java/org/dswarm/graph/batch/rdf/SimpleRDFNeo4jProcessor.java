/**
 * This file is part of d:swarm graph extension.
 *
 * d:swarm graph extension is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * d:swarm graph extension is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with d:swarm graph extension.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.dswarm.graph.batch.rdf;

import org.dswarm.graph.DMPGraphException;
import org.dswarm.graph.batch.SimpleNeo4jProcessor;
import org.neo4j.unsafe.batchinsert.BatchInserter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author tgaengler
 */
public class SimpleRDFNeo4jProcessor extends RDFNeo4jProcessor {

	private static final Logger	LOG	= LoggerFactory.getLogger(SimpleRDFNeo4jProcessor.class);

	public SimpleRDFNeo4jProcessor(final BatchInserter inserter) throws DMPGraphException {

		super(new SimpleNeo4jProcessor(inserter));
	}
}
