package org.dswarm.graph.gdm.parse;

import org.dswarm.graph.DMPGraphException;
import org.dswarm.graph.json.ResourceNode;
import org.dswarm.graph.model.GraphStatics;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.index.Index;
import org.neo4j.graphdb.index.IndexHits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author tgaengler
 */
public class Neo4jGDMHandler extends Neo4jBaseGDMHandler {

	private static final Logger			LOG	= LoggerFactory.getLogger(Neo4jGDMHandler.class);

	protected final Index<Relationship>	statementUUIDs;

	public Neo4jGDMHandler(final GraphDatabaseService database) throws DMPGraphException {

		super(database);

		try {

			statementUUIDs = database.index().forRelationships("statement_uuids");
		} catch (final Exception e) {

			tx.failure();
			tx.close();

			final String message = "couldn't load indices successfully";

			Neo4jGDMHandler.LOG.error(message, e);
			Neo4jGDMHandler.LOG.debug("couldn't finish write TX successfully");

			throw new DMPGraphException(message);
		}
	}

	@Override
	protected void addObjectToResourceWProvenanceIndex(final Node node, final String URI, final String provenanceURI) {

		if (provenanceURI != null) {

			resourcesWDataModel.add(node, GraphStatics.URI_W_DATA_MODEL, URI + provenanceURI);
		}
	}

	@Override
	protected void handleObjectProvenance(final Node node, final String provenanceURI) {

		if (provenanceURI != null) {

			node.setProperty(GraphStatics.PROVENANCE_PROPERTY, provenanceURI);
		}
	}

	@Override
	protected void handleSubjectProvenance(final Node node, String URI, final String provenanceURI) {

		if (provenanceURI != null) {

			node.setProperty(GraphStatics.PROVENANCE_PROPERTY, provenanceURI);
			resourcesWDataModel.add(node, GraphStatics.URI_W_DATA_MODEL, URI + provenanceURI);
		}
	}

	@Override
	protected void addStatementToIndex(final Relationship rel, final String statementUUID) {

		statementUUIDs.add(rel, GraphStatics.UUID, statementUUID);
	}

	@Override
	protected IndexHits<Node> getResourceNodeHits(final ResourceNode resource) {

		return resources.get(GraphStatics.URI, resource.getUri());
	}
}
