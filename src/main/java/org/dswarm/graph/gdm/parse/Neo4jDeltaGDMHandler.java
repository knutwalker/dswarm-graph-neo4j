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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.dswarm.graph.DMPGraphException;
import org.dswarm.graph.GraphIndexStatics;
import org.dswarm.graph.NodeType;
import org.dswarm.graph.delta.DMPStatics;
import org.dswarm.graph.json.LiteralNode;
import org.dswarm.graph.json.Resource;
import org.dswarm.graph.json.ResourceNode;
import org.dswarm.graph.json.Statement;
import org.dswarm.graph.model.GraphStatics;
import org.dswarm.graph.parse.Neo4jHandler;

import org.neo4j.graphdb.DynamicLabel;
import org.neo4j.graphdb.DynamicRelationshipType;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.index.Index;
import org.neo4j.graphdb.index.IndexHits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hp.hpl.jena.vocabulary.RDF;
import com.hp.hpl.jena.vocabulary.RDFS;

/**
 * TODO: re-factor usage of CommonHandler
 *
 * @author tgaengler
 */
public class Neo4jDeltaGDMHandler implements GDMHandler {

	private static final Logger			LOG					= LoggerFactory.getLogger(Neo4jDeltaGDMHandler.class);

	private int							totalTriples		= 0;
	private int							addedNodes			= 0;
	private int							addedLabels			= 0;
	private int							addedRelationships	= 0;
	private int							sinceLastCommit		= 0;
	private int							i					= 0;
	private int							literals			= 0;

	private long						tick				= System.currentTimeMillis();
	private final GraphDatabaseService	database;
	private final Index<Node>			resources;
	private final Index<Node>			resourceTypes;
	private final Index<Node>			values;
	private final Map<String, Node>		bnodes;
	private final Index<Relationship>	statementHashes;
	private final Index<Relationship>	statementUUIDs;
	private final Map<Long, String>		nodeResourceMap;

	private Transaction					tx;

	public Neo4jDeltaGDMHandler(final GraphDatabaseService database) throws DMPGraphException {

		this.database = database;
		tx = database.beginTx();

		try {

			LOG.debug("start write TX");

			resources = database.index().forNodes(GraphIndexStatics.RESOURCES_INDEX_NAME);
			resourceTypes = database.index().forNodes(GraphIndexStatics.RESOURCE_TYPES_INDEX_NAME);
			values = database.index().forNodes(GraphIndexStatics.VALUES_INDEX_NAME);
			bnodes = new HashMap<>();
			statementHashes = database.index().forRelationships(GraphIndexStatics.STATEMENT_HASHES_INDEX_NAME);
			statementUUIDs = database.index().forRelationships(GraphIndexStatics.STATEMENT_UUIDS_INDEX_NAME);
			nodeResourceMap = new HashMap<>();
		} catch (final Exception e) {

			tx.failure();
			tx.close();

			final String message = "couldn't load indices successfully";

			Neo4jDeltaGDMHandler.LOG.error(message, e);
			Neo4jDeltaGDMHandler.LOG.debug("couldn't finish write TX successfully");

			throw new DMPGraphException(message);
		}
	}

	@Override
	public void handleStatement(final Statement st, final Resource r, final long index) throws DMPGraphException {

		// utilise r for the resource property

		i++;

		// System.out.println("handle statement " + i + ": " + st.toString());

		try {

			final org.dswarm.graph.json.Node subject = st.getSubject();

			final org.dswarm.graph.json.Predicate predicate = st.getPredicate();
			final String predicateName = predicate.getUri();

			final org.dswarm.graph.json.Node object = st.getObject();

			final String statementUUID = st.getUUID();
			final Long order = st.getOrder();

			// Check index for subject
			// TODO: what should we do, if the subject is a resource type?
			Node subjectNode = determineNode(subject, false);

			if (subjectNode == null) {

				subjectNode = database.createNode();

				if (subject instanceof ResourceNode) {

					final String subjectURI = ((ResourceNode) subject).getUri();

					subjectNode.setProperty(GraphStatics.URI_PROPERTY, subjectURI);
					subjectNode.setProperty(GraphStatics.NODETYPE_PROPERTY, NodeType.Resource.toString());
					resources.add(subjectNode, GraphStatics.URI, subjectURI);
				} else {

					// subject is a blank node

					// note: can I expect an id here?
					bnodes.put("" + subject.getId(), subjectNode);
					subjectNode.setProperty(GraphStatics.NODETYPE_PROPERTY, NodeType.BNode.toString());
				}

				addedNodes++;
			}

			if (object instanceof LiteralNode) {

				literals++;

				final LiteralNode literal = (LiteralNode) object;
				final String value = literal.getValue();
				final Node objectNode = database.createNode(DMPStatics.LEAF_LABEL);
				objectNode.setProperty(GraphStatics.VALUE_PROPERTY, value);
				objectNode.setProperty(GraphStatics.NODETYPE_PROPERTY, NodeType.Literal.toString());
				objectNode.setProperty("__LEAF__", true);
				values.add(objectNode, GraphStatics.VALUE, value);

				final String resourceUri = addResourceProperty(subjectNode, subject, objectNode, r);

				addedNodes++;

				addRelationship(subjectNode, predicateName, objectNode, resourceUri, subject, r, statementUUID, order, index, subject.getType(),
						object.getType());
			} else { // must be Resource
						// Make sure object exists

				boolean isType = false;

				// add Label if this is a type entry
				if (predicateName.equals(RDF.type.getURI())) {

					addLabel(subjectNode, ((ResourceNode) object).getUri());

					isType = true;
				}

				// Check index for object
				Node objectNode = determineNode(object, isType);
				String resourceUri = null;

				if (objectNode == null) {

					if (object instanceof ResourceNode) {

						// object is a resource node

						objectNode = database.createNode(DMPStatics.LEAF_LABEL);
						objectNode.setProperty("__LEAF__", true);

						final String objectURI = ((ResourceNode) object).getUri();

						objectNode.setProperty(GraphStatics.URI_PROPERTY, objectURI);

						if (!isType) {

							objectNode.setProperty(GraphStatics.NODETYPE_PROPERTY, NodeType.Resource.toString());
						} else {

							objectNode.setProperty(GraphStatics.NODETYPE_PROPERTY, NodeType.TypeResource.toString());
							addLabel(objectNode, RDFS.Class.getURI());

							resourceTypes.add(objectNode, GraphStatics.URI, objectURI);
						}

						resources.add(objectNode, GraphStatics.URI, objectURI);
					} else {

						// object is a blank node

						objectNode = database.createNode();

						bnodes.put("" + object.getId(), objectNode);

						if (!isType) {

							objectNode.setProperty(GraphStatics.NODETYPE_PROPERTY, NodeType.BNode.toString());
							resourceUri = addResourceProperty(subjectNode, subject, objectNode, r);
						} else {

							objectNode.setProperty(GraphStatics.NODETYPE_PROPERTY, NodeType.TypeBNode.toString());
							addLabel(objectNode, RDFS.Class.getURI());
						}
					}

					addedNodes++;
				}

				addRelationship(subjectNode, predicateName, objectNode, resourceUri, subject, r, statementUUID, order, index, subject.getType(),
						object.getType());
			}

			totalTriples++;

			final long nodeDelta = totalTriples - sinceLastCommit;
			final long timeDelta = (System.currentTimeMillis() - tick) / 1000;

			if (nodeDelta >= 50000 || timeDelta >= 30) { // Commit every 50k operations or every 30 seconds

				tx.success();
				tx.close();
				tx = database.beginTx();

				sinceLastCommit = totalTriples;

				LOG.debug(totalTriples + " triples @ ~" + (double) nodeDelta / timeDelta + " triples/second.");

				tick = System.currentTimeMillis();
			}
		} catch (final Exception e) {

			final String message = "couldn't finish write TX successfully";

			LOG.error(message, e);

			tx.failure();
			tx.close();

			throw new DMPGraphException(message);
		}
	}

	@Override public Neo4jHandler getHandler() {

		// nothing TODO here ...

		return null;
	}

	public void closeTransaction() {

		LOG.debug("close write TX finally");

		tx.success();
		tx.close();
	}

	public int getCountedStatements() {

		return totalTriples;
	}

	public int getNodesAdded() {

		return addedNodes;
	}

	public int getRelationShipsAdded() {

		return addedRelationships;
	}

	public int getCountedLiterals() {

		return literals;
	}

	private void addLabel(final Node node, final String labelString) {

		final Label label = DynamicLabel.label(labelString);
		boolean hit = false;
		final Iterable<Label> labels = node.getLabels();
		final List<Label> labelList = new LinkedList<Label>();

		for (final Label lbl : labels) {

			if (label.equals(lbl)) {

				hit = true;
				break;
			}

			labelList.add(lbl);
		}

		if (!hit) {

			labelList.add(label);
			node.addLabel(label);
			addedLabels++;
		}
	}

	private Relationship addRelationship(final Node subjectNode, final String predicateName, final Node objectNode, final String resourceUri,
			final org.dswarm.graph.json.Node subject, final Resource resource, final String statementUUID, final Long order, final long index,
			final org.dswarm.graph.json.NodeType subjectNodeType, final org.dswarm.graph.json.NodeType objectNodeType) throws DMPGraphException {

		final StringBuffer sb = new StringBuffer();

		final String subjectIdentifier = getIdentifier(subjectNode, subjectNodeType);
		final String objectIdentifier = getIdentifier(objectNode, objectNodeType);

		sb.append(subjectNodeType.toString()).append(":").append(subjectIdentifier).append(" ").append(predicateName).append(" ")
				.append(objectNodeType.toString()).append(":").append(objectIdentifier).append(" ");

		MessageDigest messageDigest = null;

		try {
			messageDigest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {

			throw new DMPGraphException("couldn't instantiate hash algo");
		}
		messageDigest.update(sb.toString().getBytes());
		final String hash = new String(messageDigest.digest());

		final Relationship rel;

		IndexHits<Relationship> hits = statementHashes.get(GraphStatics.HASH, hash);

		if (hits == null || !hits.hasNext()) {

			final RelationshipType relType = DynamicRelationshipType.withName(predicateName);
			rel = subjectNode.createRelationshipTo(objectNode, relType);

			final String finalStatementUUID;

			if (statementUUID == null) {

				finalStatementUUID = UUID.randomUUID().toString();
			} else {

				finalStatementUUID = statementUUID;
			}

			rel.setProperty(GraphStatics.UUID_PROPERTY, finalStatementUUID);

			if (order != null) {

				rel.setProperty(GraphStatics.ORDER_PROPERTY, order);
			}

			rel.setProperty(GraphStatics.INDEX_PROPERTY, index);

			statementHashes.add(rel, GraphStatics.HASH, hash);
			statementUUIDs.add(rel, GraphStatics.UUID, finalStatementUUID);

			addedRelationships++;

			addResourceProperty(subjectNode, subject, rel, resourceUri, resource);
		} else {

			rel = hits.next();
		}

		if(hits != null) {

			hits.close();
		}

		return rel;
	}

	private Node determineNode(final org.dswarm.graph.json.Node resource, final boolean isType) {

		final Node node;

		if (resource instanceof ResourceNode) {

			// resource node

			final IndexHits<Node> hits;

			if (!isType) {

				hits = resources.get(GraphStatics.URI, ((ResourceNode) resource).getUri());
			} else {

				hits = resourceTypes.get(GraphStatics.URI, ((ResourceNode) resource).getUri());
			}

			if (hits != null && hits.hasNext()) {

				// node exists

				node = hits.next();

				hits.close();

				return node;
			}

			if(hits != null) {

				hits.close();
			}

			return null;
		}

		if (resource instanceof LiteralNode) {

			// literal node - should never be the case

			return null;
		}

		// resource must be a blank node

		node = bnodes.get("" + resource.getId());

		return node;
	}

	private String addResourceProperty(final Node subjectNode, final org.dswarm.graph.json.Node subject, final Node objectNode,
			final Resource resource) {

		final String resourceUri = determineResourceUri(subjectNode, subject, resource);

		if (resourceUri == null) {

			return null;
		}

		objectNode.setProperty(GraphStatics.RESOURCE_PROPERTY, resourceUri);

		return resourceUri;
	}

	private String addResourceProperty(final Node subjectNode, final org.dswarm.graph.json.Node subject, final Relationship rel,
			final String resourceUri, final Resource resource) {

		final String finalResourceUri;

		if (resourceUri != null) {

			finalResourceUri = resourceUri;
		} else {

			finalResourceUri = determineResourceUri(subjectNode, subject, resource);
		}

		rel.setProperty(GraphStatics.RESOURCE_PROPERTY, finalResourceUri);

		return finalResourceUri;
	}

	private String determineResourceUri(final Node subjectNode, final org.dswarm.graph.json.Node subject, final Resource resource) {

		final Long nodeId = subjectNode.getId();

		final String resourceUri;

		if (nodeResourceMap.containsKey(nodeId)) {

			resourceUri = nodeResourceMap.get(nodeId);
		} else {

			if (subject instanceof ResourceNode) {

				resourceUri = ((ResourceNode) subject).getUri();
			} else {

				resourceUri = resource.getUri();
			}

			nodeResourceMap.put(nodeId, resourceUri);
		}

		return resourceUri;
	}

	private String getIdentifier(final Node node, final org.dswarm.graph.json.NodeType nodeType) {

		final String identifier;

		switch (nodeType) {

			case Resource:

				identifier = (String) node.getProperty(GraphStatics.URI_PROPERTY, null);

				break;
			case BNode:

				identifier = "" + node.getId();

				break;
			case Literal:

				identifier = (String) node.getProperty(GraphStatics.VALUE_PROPERTY, null);

				break;
			default:

				identifier = null;

				break;
		}

		return identifier;
	}
}
