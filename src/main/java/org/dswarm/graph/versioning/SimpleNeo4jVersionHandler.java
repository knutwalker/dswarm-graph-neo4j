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
package org.dswarm.graph.versioning;

import org.dswarm.graph.DMPGraphException;
import org.dswarm.graph.Neo4jProcessor;

/**
 * @author tgaengler
 */
public class SimpleNeo4jVersionHandler extends Neo4jVersionHandler {

	public SimpleNeo4jVersionHandler(final Neo4jProcessor processorArg) throws DMPGraphException {

		super(processorArg);
	}

	@Override
	protected int retrieveLatestVersion() {

		// TODO:

		return 0;
	}

	@Override
	public void updateLatestVersion() throws DMPGraphException {

		// TODO:
	}
}
