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
package org.dswarm.graph.gdm.test;

import java.io.IOException;
import java.net.URL;
import java.util.Map;

import javax.ws.rs.core.MediaType;

import junit.framework.Assert;

import org.dswarm.graph.json.util.Util;
import org.dswarm.graph.test.BasicResourceTest;
import org.dswarm.graph.test.Neo4jDBWrapper;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.Maps;
import com.google.common.io.Resources;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.multipart.BodyPart;
import com.sun.jersey.multipart.MultiPart;

/**
 * @author tgaengler
 */
public abstract class GDMResourceTest extends BasicResourceTest {

	private static final Logger	LOG	= LoggerFactory.getLogger(GDMResourceTest.class);

	private static final String DEFAULT_GDM_FILE_NAME = "test-mabxml.gson";

	public GDMResourceTest(final Neo4jDBWrapper neo4jDBWrapper, final String dbTypeArg) {

		super(neo4jDBWrapper, "/gdm", dbTypeArg);
	}

	@Test
	public void writeGDMToDB() throws IOException {

		writeGDMToDBInternal("http://data.slub-dresden.de/resources/1", DEFAULT_GDM_FILE_NAME);
	}

	@Test
	public void writeGDMToDB2() throws IOException {

		writeGDMToDBInternal("http://data.slub-dresden.de/datamodel/4/data", "versioning/dd-854/example_1.task.result.json");
	}

	@Test
	public void writeGDMToDB3() throws IOException {

		writeGDMToDBInternal("http://data.slub-dresden.de/datamodel/5/data", "versioning/dd-854/example_2.task.result.json");
	}

	@Test
	public void writeGDMToDB4() throws IOException {

		writeGDMToDBInternal("http://data.slub-dresden.de/datamodel/4/data", "versioning/dd-854/example_1.gdm.json");
		writeGDMToDBInternal("http://data.slub-dresden.de/datamodel/5/data", "versioning/dd-854/example_2.gdm.json");
		writeGDMToDBInternal("http://data.slub-dresden.de/datamodel/2/data", "versioning/dd-854/example_1.task.result.json");
		writeGDMToDBInternal("http://data.slub-dresden.de/datamodel/2/data", "versioning/dd-854/example_2.task.result.json");
	}

	@Test
	public void testResourceTypeNodeUniqueness() throws IOException {

		writeGDMToDBInternal("http://data.slub-dresden.de/resources/1", DEFAULT_GDM_FILE_NAME);
		writeGDMToDBInternal("http://data.slub-dresden.de/resources/2", DEFAULT_GDM_FILE_NAME);

		final String typeQuery = "MATCH (n) WHERE n.__NODETYPE__ = \"__TYPE_RESOURCE__\" RETURN id(n) AS node_id, n.__URI__ AS node_uri;";

		final ObjectMapper objectMapper = Util.getJSONObjectMapper();

		final ObjectNode requestJson = objectMapper.createObjectNode();

		requestJson.put("query", typeQuery);

		final String requestJsonString = objectMapper.writeValueAsString(requestJson);

		final ClientResponse response = cypher().type(MediaType.APPLICATION_JSON_TYPE).accept(MediaType.APPLICATION_JSON)
				.post(ClientResponse.class, requestJsonString);

		Assert.assertEquals("expected 200", 200, response.getStatus());

		final String body = response.getEntity(String.class);

		final ObjectNode bodyJson = objectMapper.readValue(body, ObjectNode.class);

		Assert.assertNotNull(bodyJson);

		final JsonNode dataNode = bodyJson.get("data");

		Assert.assertNotNull(dataNode);
		Assert.assertTrue(dataNode.size() > 0);

		final Map<String, Long> resourceTypeMap = Maps.newHashMap();

		for (final JsonNode entry : dataNode) {

			final String resourceType = entry.get(1).textValue();
			final long nodeId = entry.get(0).longValue();

			if (resourceTypeMap.containsKey(resourceType)) {

				final Long existingNodeId = resourceTypeMap.get(resourceType);

				Assert.assertTrue("resource node map already contains a node for resource type '" + resourceType + "' with the id '" + existingNodeId
						+ "', but found another node with id '" + nodeId + "' for this resource type", false);
			}

			resourceTypeMap.put(resourceType, nodeId);
		}
	}

	@Test
	public void readGDMFromDBThatWasWrittenAsRDF() throws IOException {

		LOG.debug("start read test for GDM resource at " + dbType + " DB");

		writeRDFToDBInternal("http://data.slub-dresden.de/resources/1");

		final ObjectMapper objectMapper = Util.getJSONObjectMapper();
		objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
		final ObjectNode requestJson = objectMapper.createObjectNode();

		requestJson.put("record_class_uri", "http://www.openarchives.org/OAI/2.0/recordType");
		requestJson.put("data_model_uri", "http://data.slub-dresden.de/resources/1");

		final String requestJsonString = objectMapper.writeValueAsString(requestJson);

		// POST the request
		final ClientResponse response = target().path("/get").type(MediaType.APPLICATION_JSON_TYPE).accept(MediaType.APPLICATION_JSON)
				.post(ClientResponse.class, requestJsonString);

		Assert.assertEquals("expected 200", 200, response.getStatus());

		final String body = response.getEntity(String.class);

		final org.dswarm.graph.json.Model model = objectMapper.readValue(body, org.dswarm.graph.json.Model.class);

		LOG.debug("read '" + model.size() + "' statements");

		Assert.assertEquals("the number of statements should be 2601", 2601, model.size());

		LOG.debug("finished read test for GDM resource at " + dbType + " DB");
	}

	@Test
	public void readGDMFromDBThatWasWrittenAsGDM() throws IOException {

		LOG.debug("start read test for GDM resource at " + dbType + " DB");

		writeGDMToDBInternal("http://data.slub-dresden.de/resources/1", DEFAULT_GDM_FILE_NAME);

		final ObjectMapper objectMapper = Util.getJSONObjectMapper();
		objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
		final ObjectNode requestJson = objectMapper.createObjectNode();

		requestJson.put("record_class_uri", "http://www.ddb.de/professionell/mabxml/mabxml-1.xsd#datensatzType");
		requestJson.put("data_model_uri", "http://data.slub-dresden.de/resources/1");

		final String requestJsonString = objectMapper.writeValueAsString(requestJson);

		// POST the request
		final ClientResponse response = target().path("/get").type(MediaType.APPLICATION_JSON_TYPE).accept(MediaType.APPLICATION_JSON)
				.post(ClientResponse.class, requestJsonString);

		Assert.assertEquals("expected 200", 200, response.getStatus());

		final String body = response.getEntity(String.class);

		final org.dswarm.graph.json.Model model = objectMapper.readValue(body, org.dswarm.graph.json.Model.class);

		LOG.debug("read '" + model.size() + "' statements");

		Assert.assertEquals("the number of statements should be 191", 191, model.size());

		LOG.debug("finished read test for GDM resource at " + dbType + " DB");
	}

	private void writeRDFToDBInternal(final String dataModelURI) throws IOException {

		LOG.debug("start writing RDF statements for GDM resource at " + dbType + " DB");

		final URL fileURL = Resources.getResource("dmpf_bsp1.n3");
		final byte[] file = Resources.toByteArray(fileURL);

		// Construct a MultiPart with two body parts
		final MultiPart multiPart = new MultiPart();
		multiPart.bodyPart(new BodyPart(file, MediaType.APPLICATION_OCTET_STREAM_TYPE)).bodyPart(
				new BodyPart(dataModelURI, MediaType.TEXT_PLAIN_TYPE));

		// POST the request
		final ClientResponse response = service().path("/rdf/put").type("multipart/mixed").post(ClientResponse.class, multiPart);

		Assert.assertEquals("expected 200", 200, response.getStatus());

		multiPart.close();

		LOG.debug("finished writing RDF statements for GDM resource at " + dbType + " DB");
	}

	private void writeGDMToDBInternal(final String dataModelURI, final String fileName) throws IOException {

		LOG.debug("start writing GDM statements for GDM resource at " + dbType + " DB");

		final URL fileURL = Resources.getResource(fileName);
		final byte[] file = Resources.toByteArray(fileURL);

		// Construct a MultiPart with two body parts
		final MultiPart multiPart = new MultiPart();
		multiPart.bodyPart(new BodyPart(file, MediaType.APPLICATION_OCTET_STREAM_TYPE)).bodyPart(
				new BodyPart(dataModelURI, MediaType.TEXT_PLAIN_TYPE));

		// POST the request
		final ClientResponse response = target().path("/put").type("multipart/mixed").post(ClientResponse.class, multiPart);

		Assert.assertEquals("expected 200", 200, response.getStatus());

		multiPart.close();

		LOG.debug("finished writing GDM statements for GDM resource at " + dbType + " DB");
	}
}
