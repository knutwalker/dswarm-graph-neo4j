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
package org.dswarm.graph.gdm.work;

import org.dswarm.graph.DMPGraphException;
import org.dswarm.graph.NodeType;
import org.dswarm.graph.delta.util.GraphDBUtil;
import org.dswarm.graph.model.GraphStatics;
import org.dswarm.graph.read.NodeHandler;
import org.dswarm.graph.utils.GraphUtils;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author tgaengler
 */
public class PropertyEnrichGDMWorker implements GDMWorker {

	private static final Logger						LOG	= LoggerFactory.getLogger(PropertyEnrichGDMWorker.class);

	private final HierarchyLevelNodeHandler			nodeHandler;
	private final NodeHandler						startNodeHandler;
	private final HierarchyLevelRelationshipHandler	relationshipHandler;

	private final String							resourceUri;

	private final GraphDatabaseService				database;

	public PropertyEnrichGDMWorker(final String resourceUriArg, final GraphDatabaseService databaseArg) {

		resourceUri = resourceUriArg;
		database = databaseArg;
		nodeHandler = new CBDNodeHandler();
		startNodeHandler = new CBDStartNodeHandler();
		relationshipHandler = new CBDRelationshipHandler();
	}

	@Override
	public void work() throws DMPGraphException {

		try(final Transaction tx = database.beginTx()) {

			PropertyEnrichGDMWorker.LOG.debug("start enrich GDM TX");

			final Node recordNode = GraphDBUtil.getResourceNode(database, resourceUri);

			if (recordNode == null) {

				PropertyEnrichGDMWorker.LOG.debug("couldn't find record for resource '" + resourceUri + "'");

				tx.success();

				PropertyEnrichGDMWorker.LOG.debug("finished enrich GDM TX successfully");

				return;
			}

			startNodeHandler.handleNode(recordNode);

			tx.success();

			PropertyEnrichGDMWorker.LOG.debug("finished enrich GDM TX successfully");
		} catch (final Exception e) {

			final String message = "couldn't finished enrich GDM TX successfully";

			PropertyEnrichGDMWorker.LOG.error(message, e);

			throw new DMPGraphException(message);
		}
	}

	private class CBDNodeHandler implements HierarchyLevelNodeHandler {

		@Override
		public void handleNode(final Node node, final int hierarchyLevel) throws DMPGraphException {

			if (node.hasProperty(GraphStatics.RESOURCE_PROPERTY) && node.getProperty(GraphStatics.RESOURCE_PROPERTY).equals(resourceUri)) {

				final Iterable<Relationship> relationships = node.getRelationships(Direction.OUTGOING);

				for (final Relationship relationship : relationships) {

					relationshipHandler.handleRelationship(relationship, hierarchyLevel);
				}
			}
		}
	}

	private class CBDStartNodeHandler implements NodeHandler {

		@Override
		public void handleNode(final Node node) throws DMPGraphException {

			if (node.hasProperty(GraphStatics.URI_PROPERTY)) {

				final Iterable<Relationship> relationships = node.getRelationships(Direction.OUTGOING);

				for (final Relationship relationship : relationships) {

					relationshipHandler.handleRelationship(relationship, 0);
				}
			}
		}
	}

	private class CBDRelationshipHandler implements HierarchyLevelRelationshipHandler {

		@Override
		public void handleRelationship(final Relationship rel, final int hierarchyLevel) throws DMPGraphException {

			final long statementId = rel.getId();

			// subject

			final Node subjectNode = rel.getStartNode();
			subjectNode.setProperty("__HIERARCHY_LEVEL__", hierarchyLevel);

			// predicate

			rel.setProperty("__HIERARCHY_LEVEL__", hierarchyLevel);

			// object

			final Node objectNode = rel.getEndNode();
			final NodeType objectNodeType = GraphUtils.determineNodeType(objectNode);

			switch (objectNodeType) {

				case Literal:
				case Resource:
				case TypeResource:

					objectNode.setProperty("__HIERARCHY_LEVEL__", hierarchyLevel + 1);

					break;
				default:

					// continue traversal with object node
					nodeHandler.handleNode(rel.getEndNode(), hierarchyLevel + 1);

					break;
			}
		}
	}
}
