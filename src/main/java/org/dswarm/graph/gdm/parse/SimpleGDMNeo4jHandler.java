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
package org.dswarm.graph.gdm.parse;

import org.dswarm.graph.DMPGraphException;
import org.dswarm.graph.gdm.GDMNeo4jProcessor;
import org.dswarm.graph.parse.SimpleNeo4jHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TODO: maybe we should add a general type for (bibliographic) resources (to easily identify the boundaries of the resources)
 *
 * @author tgaengler
 */
public class SimpleGDMNeo4jHandler extends GDMNeo4jHandler {

	private static final Logger	LOG	= LoggerFactory.getLogger(SimpleGDMNeo4jHandler.class);

	public SimpleGDMNeo4jHandler(final GDMNeo4jProcessor processorArg) throws DMPGraphException {

		super(new SimpleNeo4jHandler(processorArg.getProcessor()), processorArg);
	}
}
