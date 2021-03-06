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
package org.dswarm.graph.resources;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Map;
import java.util.Map.Entry;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.neo4j.cypher.javacompat.ExecutionEngine;
import org.neo4j.cypher.javacompat.ExecutionResult;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.index.Index;
import org.neo4j.graphdb.schema.IndexDefinition;
import org.neo4j.graphdb.schema.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;

import org.dswarm.graph.DMPGraphException;
import org.dswarm.graph.GraphIndexStatics;

/**
 * @author tgaengler
 */
@Path("/maintain")
public class MaintainResource {

	private static final Logger			LOG				= LoggerFactory.getLogger(MaintainResource.class);

	private static final long			chunkSize		= 50000;

	private static final String			DELETE_CYPHER	= "MATCH (a) WITH a LIMIT %d OPTIONAL MATCH (a)-[r]-() DELETE a,r RETURN COUNT(*) AS entity_count";

	private static final JsonFactory	jsonFactory		= new JsonFactory();

	public MaintainResource() {

	}

	@GET
	@Path("/ping")
	public String ping() {

		MaintainResource.LOG.debug("ping was called");

		return "pong";
	}

	/**
	 * note utilise this endpoint with care, because it cleans your complete db!
	 *
	 * @param database the graph database
	 */
	@DELETE
	@Path("/delete")
	@Produces("application/json")
	public Response cleanGraph(@Context final GraphDatabaseService database) throws IOException, DMPGraphException {

		MaintainResource.LOG.debug("start cleaning up the db");

		final long deleted = deleteSomeStatements(database);

		MaintainResource.LOG.debug("finished delete-all-entities TXs");

		MaintainResource.LOG.debug("start legacy indices clean-up");

		// TODO: maybe separate index clean-up + observe index clean-up
		// => maybe we also need to do a label + relationship types clean-up ... => this is not supported right now ...

		deleteSomeLegacyIndices(database);

		MaintainResource.LOG.debug("finished legacy indices clean-up");

		MaintainResource.LOG.debug("start schema indices clean-up");

		deleteSomeSchemaIndices(database);

		MaintainResource.LOG.debug("finished schema indices clean-up");

		MaintainResource.LOG.debug("finished cleaning up the db");

		final StringWriter out = new StringWriter();
		JsonGenerator generator = jsonFactory.createGenerator(out);

		generator.writeStartObject();
		generator.writeNumberField("deleted", deleted);
		generator.writeEndObject();
		generator.flush();
		generator.close();

		return Response.ok(out.toString(), MediaType.APPLICATION_JSON_TYPE).build();
	}

	private long deleteSomeStatements(final GraphDatabaseService database) throws DMPGraphException {


		final ExecutionEngine engine = new ExecutionEngine(database);

		final String deleteQuery = String.format(DELETE_CYPHER, MaintainResource.chunkSize);

		long deleted = 0;

		int i = 0;

		while (true) {

			i++;

			try(final Transaction tx = database.beginTx()) {

				MaintainResource.LOG
					.debug("try to delete up to " + MaintainResource.chunkSize + " nodes and their relationships for the " + i + ". time");

				final ExecutionResult result = engine.execute(deleteQuery);

				if (result == null) {

					MaintainResource.LOG.debug("there are no more results for removal available, i.e. result is empty");

					tx.success();

					break;
				}

				final ResourceIterator<Map<String, Object>> iterator = result.iterator();

				if (iterator == null) {

					MaintainResource.LOG.debug("there are no more results for removal available, i.e. result iterator is not available");

					tx.success();

					break;
				}

				if (!iterator.hasNext()) {

					MaintainResource.LOG.debug("there are no more results for removal available, i.e. result iterator is empty");

					iterator.close();
					tx.success();

					break;
				}

				final Map<String, Object> row = iterator.next();

				if (row == null || row.isEmpty()) {

					MaintainResource.LOG.debug("there are no more results for removal available, i.e. row map is empty");

					iterator.close();
					tx.success();

					break;
				}

				final Entry<String, Object> entry = row.entrySet().iterator().next();

				if (entry == null) {

					MaintainResource.LOG.debug("there are no more results for removal available, i.e. entry is not available");

					iterator.close();
					tx.success();

					break;
				}

				final Object value = entry.getValue();

				if (value == null) {

					MaintainResource.LOG.debug("there are no more results for removal available, i.e. value is not available");

					iterator.close();
					tx.success();

					break;
				}

				if (!entry.getKey().equals("entity_count")) {

					MaintainResource.LOG.debug("there are no more results for removal available, i.e. entity count is not available");

					iterator.close();
					tx.success();

					break;
				}

				final Long count = (Long) value;

				deleted += count;

				MaintainResource.LOG.debug("deleted " + count + " entities");

				if (count < chunkSize) {

					MaintainResource.LOG.debug("there are no more results for removal available, i.e. current result is smaller than chunk size");

					iterator.close();
					tx.success();

					break;
				}

				iterator.close();
				tx.success();
			} catch (final Exception e) {

				final String message = "couldn't finish delete-all-entities TX successfully";

				MaintainResource.LOG.error(message, e);

				throw new DMPGraphException(message);
			}
		}

		return deleted;
	}

	private void deleteSomeLegacyIndices(final GraphDatabaseService database) throws DMPGraphException {

		MaintainResource.LOG.debug("start delete legacy indices TX");

		try(final Transaction itx = database.beginTx()) {

			final Index<Node> resources = database.index().forNodes(GraphIndexStatics.RESOURCES_INDEX_NAME);
			final Index<Node> values = database.index().forNodes(GraphIndexStatics.VALUES_INDEX_NAME);
			final Index<Node> resourcesWDataModel = database.index().forNodes(GraphIndexStatics.RESOURCES_W_DATA_MODEL_INDEX_NAME);
			final Index<Node> resourceTypes = database.index().forNodes(GraphIndexStatics.RESOURCE_TYPES_INDEX_NAME);
			final Index<Relationship> statements = database.index().forRelationships("statements");
			final Index<Relationship> statementHashes = database.index().forRelationships(GraphIndexStatics.STATEMENT_HASHES_INDEX_NAME);
			final Index<Relationship> statementUUIDs = database.index().forRelationships(GraphIndexStatics.STATEMENT_UUIDS_INDEX_NAME);
			final Index<Relationship> statementUUIDsWDataModel = database.index().forRelationships(GraphIndexStatics.STATEMENT_UUIDS_W_DATA_MODEL_INDEX_NAME);

			if (resources != null) {

				MaintainResource.LOG.debug("delete " + GraphIndexStatics.RESOURCES_INDEX_NAME + " legacy index");

				resources.delete();
			}

			if (resourcesWDataModel != null) {

				MaintainResource.LOG.debug("delete " + GraphIndexStatics.RESOURCES_W_DATA_MODEL_INDEX_NAME + " legacy index");

				resourcesWDataModel.delete();
			}

			if (resourceTypes != null) {

				MaintainResource.LOG.debug("delete " + GraphIndexStatics.RESOURCE_TYPES_INDEX_NAME + " legacy index");

				resourceTypes.delete();
			}

			if (statements != null) {

				MaintainResource.LOG.debug("delete statements legacy index");

				statements.delete();
			}

			if (statementHashes != null) {

				MaintainResource.LOG.debug("delete " + GraphIndexStatics.STATEMENT_HASHES_INDEX_NAME + " legacy index");

				statementHashes.delete();
			}

			if (statementUUIDs != null) {

				MaintainResource.LOG.debug("delete " + GraphIndexStatics.STATEMENT_UUIDS_INDEX_NAME + " legacy index");

				statementUUIDs.delete();
			}

			if (statementUUIDsWDataModel != null) {

				MaintainResource.LOG.debug("delete " + GraphIndexStatics.STATEMENT_UUIDS_W_DATA_MODEL_INDEX_NAME + " legacy index");

				statementUUIDsWDataModel.delete();
			}

			if (values != null) {

				MaintainResource.LOG.debug("delete " + GraphIndexStatics.VALUES_INDEX_NAME + " legacy index");

				values.delete();
			}

			itx.success();
		} catch (final Exception e) {

			final String message = "couldn't finish delete legacy indices TX successfully";

			MaintainResource.LOG.error(message, e);

			throw new DMPGraphException(message);
		}

		MaintainResource.LOG.debug("finished delete legacy indices TX");
	}

	private void deleteSomeSchemaIndices(final GraphDatabaseService database) throws DMPGraphException {

		MaintainResource.LOG.debug("start delete schema indices TX");

		try(final Transaction itx = database.beginTx()) {

			final Schema schema = database.schema();

			if (schema == null) {

				MaintainResource.LOG.debug("no schema available");

				itx.success();

				return;
			}

			Iterable<IndexDefinition> indexDefinitions = schema.getIndexes();

			if (indexDefinitions == null) {

				MaintainResource.LOG.debug("no schema indices available");

				itx.success();

				return;
			}

			for (final IndexDefinition indexDefinition : indexDefinitions) {

				MaintainResource.LOG.debug("drop '" + indexDefinition.getLabel().name() + "' : '"
						+ indexDefinition.getPropertyKeys().iterator().next() + "' schema index");

				indexDefinition.drop();
			}

			itx.success();
		} catch (final Exception e) {

			final String message = "couldn't finish delete schema indices TX successfully";

			MaintainResource.LOG.error(message, e);

			throw new DMPGraphException(message);
		}

		MaintainResource.LOG.debug("finished delete schema indices TX");
	}
}
