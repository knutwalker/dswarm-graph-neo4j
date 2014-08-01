package org.dswarm.graph.delta.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;

import ch.lambdaj.Lambda;
import ch.lambdaj.group.Group;
import org.dswarm.graph.NodeType;
import org.dswarm.graph.delta.Attribute;
import org.dswarm.graph.delta.AttributePath;
import org.dswarm.graph.delta.ContentSchema;
import org.dswarm.graph.delta.DMPStatics;
import org.dswarm.graph.delta.evaluator.EntityEvaluator;
import org.dswarm.graph.delta.match.model.CSEntity;
import org.dswarm.graph.delta.match.model.KeyEntity;
import org.dswarm.graph.delta.match.model.ValueEntity;
import org.dswarm.graph.model.GraphStatics;
import org.neo4j.cypher.javacompat.ExecutionEngine;
import org.neo4j.cypher.javacompat.ExecutionResult;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.ResourceIterable;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.index.Index;
import org.neo4j.graphdb.index.IndexHits;
import org.neo4j.graphdb.traversal.Evaluation;
import org.neo4j.graphdb.traversal.Evaluator;
import org.neo4j.graphdb.traversal.Evaluators;
import org.neo4j.kernel.Traversal;
import org.neo4j.tooling.GlobalGraphOperations;

import com.google.common.collect.Lists;

/**
 * Created by tgaengler on 29/07/14.
 */
public final class GraphDBUtil {

	/**
	 * note: should be run in transaction scope
	 * 
	 * @param graphDB
	 * @param resourceURI
	 * @return
	 */
	public static Node getResourceNode(final GraphDatabaseService graphDB, final String resourceURI) {

		final Index<Node> resources = graphDB.index().forNodes("resources");

		if (resources == null) {

			return null;
		}

		final IndexHits<Node> hits = resources.get(GraphStatics.URI, resourceURI);

		if (hits == null || !hits.hasNext()) {

			return null;
		}

		return hits.next();
	}

	public static void printNodes(final GraphDatabaseService graphDB) {

		Transaction tx = graphDB.beginTx();

		final Iterable<Node> nodes = GlobalGraphOperations.at(graphDB).getAllNodes();

		for (final Node node : nodes) {

			final Iterable<Label> labels = node.getLabels();

			for (final Label label : labels) {

				System.out.println("node = '" + node.getId() + "' :: label = '" + label.name());
			}

			final Iterable<String> propertyKeys = node.getPropertyKeys();

			for (final String propertyKey : propertyKeys) {

				final Object value = node.getProperty(propertyKey);

				System.out.println("node = '" + node.getId() + "' :: key = '" + propertyKey + "' :: value = '" + value + "'");
			}
		}

		tx.success();
		tx.close();
	}

	public static void printRelationships(final GraphDatabaseService graphDB) {

		Transaction tx = graphDB.beginTx();

		final Iterable<Relationship> relationships = GlobalGraphOperations.at(graphDB).getAllRelationships();

		for (final Relationship relationship : relationships) {

			final RelationshipType type = relationship.getType();

			System.out.println("relationship = '" + relationship.getId() + "' :: relationship type = '" + type.name());

			final Iterable<String> propertyKeys = relationship.getPropertyKeys();

			for (final String propertyKey : propertyKeys) {

				final Object value = relationship.getProperty(propertyKey);

				System.out.println("relationship = '" + relationship.getId() + "' :: key = '" + propertyKey + "' :: value = '" + value + "'");
			}
		}

		tx.success();
		tx.close();
	}

	/**
	 * note: should be run in transaction scope
	 *
	 * @param graphDB
	 * @param resourceURI
	 * @return
	 */
	public static Iterable<Path> getResourcePaths(final GraphDatabaseService graphDB, final String resourceURI) {

		final Node resourceNode = getResourceNode(graphDB, resourceURI);

		final Iterable<Path> paths = graphDB.traversalDescription().depthFirst().evaluator(new Evaluator() {

			@Override
			public Evaluation evaluate(final Path path) {

				final boolean hasLeafLabel = path.endNode().hasLabel(DMPStatics.LEAF_LABEL);

				if (hasLeafLabel) {

					return Evaluation.INCLUDE_AND_PRUNE;
				}

				return Evaluation.EXCLUDE_AND_CONTINUE;
			}
		}).traverse(resourceNode);

		return paths;
	}

	public static void printPaths(final GraphDatabaseService graphDB, final String resourceURI) {

		final Transaction tx = graphDB.beginTx();

		final Iterable<Path> paths = getResourcePaths(graphDB, resourceURI);
		final Traversal.PathDescriptor<Path> pathPrinter = new PathPrinter();

		for (final Path path : paths) {

			final String pathString = Traversal.pathToString(path, pathPrinter);

			System.out.println(pathString);
		}

		tx.success();
		tx.close();
	}

	public static Collection<CSEntity> getCSEntities(final GraphDatabaseService graphDB, final String resourceURI, final AttributePath commonAttributePath, final ContentSchema contentSchema) {

		final Transaction tx = graphDB.beginTx();

		final Node resourceNode = getResourceNode(graphDB, resourceURI);

		// determine CS entity nodes
		final ResourceIterable<Node> csEntityNodes = graphDB.traversalDescription().breadthFirst()
				.evaluator(Evaluators.toDepth(commonAttributePath.getAttributes().size())).evaluator(new EntityEvaluator(commonAttributePath.getAttributes()))
				.traverse(resourceNode).nodes();

		if(csEntityNodes == null) {

			return null;
		}

		final Map<Long, CSEntity> csEntities = new LinkedHashMap<>();

		for(final Node node : csEntityNodes) {

			final CSEntity csEntity = new CSEntity(node.getId());
			csEntities.put(csEntity.getNodeId(), csEntity);
		}

		final ArrayList<Node> csEntityNodesList = Lists.newArrayList(csEntityNodes);
		final Node[] csEntityNodesArray = new Node[csEntityNodesList.size()];
		csEntityNodesList.toArray(csEntityNodesArray);

		if(contentSchema.getKeyAttributePaths() != null) {

			// determine key entities
			determineKeyEntities(graphDB, commonAttributePath, contentSchema, csEntities, csEntityNodesArray);
		}

		if(contentSchema.getValueAttributePath() != null) {

			// determine value entities
			determineValueEntities(graphDB, commonAttributePath, contentSchema, csEntities, csEntityNodesArray);
		}

		tx.success();
		tx.close();

		final Collection<CSEntity> csEntitiesCollection = csEntities.values();

		// determine cs entity order
		determineCSEntityOrder(csEntitiesCollection);

		return csEntitiesCollection;
	}

	public static String determineRecordIdentifier(final GraphDatabaseService graphDB, final AttributePath recordIdentifierAP, final String recordURI) {

		final String query = buildGetRecordIdentifierQuery(recordIdentifierAP, recordURI);

		return executeQueryWithSingleResult(query, "record_identifier", graphDB);
	}

	public static String determineRecordUri(final String recordId, final AttributePath recordIdentifierAP, final String resourceGraphUri,
			final GraphDatabaseService graphDB) {

		final String query = buildGetRecordUriQuery(recordId, recordIdentifierAP, resourceGraphUri);

		return executeQueryWithSingleResult(query, "record_uri", graphDB);
	}

	private static void determineKeyEntities(final GraphDatabaseService graphDB, final AttributePath commonAttributePath,
			final ContentSchema contentSchema, final Map<Long, CSEntity> csEntities, final Node[] csEntityNodesArray) {

		for (final AttributePath keyAttributePath : contentSchema.getKeyAttributePaths()) {

			final LinkedList<Attribute> relativeKeyAttributePath = determineRelativeAttributePath(keyAttributePath, commonAttributePath);

			final Iterable<Path> relativeKeyPaths = graphDB.traversalDescription().depthFirst()
					.evaluator(Evaluators.toDepth(relativeKeyAttributePath.size())).evaluator(new EntityEvaluator(relativeKeyAttributePath))
					.traverse(csEntityNodesArray);

			for (final Path relativeKeyPath : relativeKeyPaths) {

				final Node keyNode = relativeKeyPath.endNode();
				final Node csEntityNode = relativeKeyPath.startNode();
				final String keyValue = (String) keyNode.getProperty(GraphStatics.VALUE_PROPERTY, null);
				final KeyEntity keyEntity = new KeyEntity(keyNode.getId(), keyValue);
				csEntities.get(csEntityNode.getId()).addKeyEntity(keyEntity);
			}
		}
	}

	private static void determineValueEntities(final GraphDatabaseService graphDB, final AttributePath commonAttributePath,
			final ContentSchema contentSchema, final Map<Long, CSEntity> csEntities, final Node[] csEntityNodesArray) {

		final LinkedList<Attribute> relativeValueAttributePath = determineRelativeAttributePath(contentSchema.getValueAttributePath(),
				commonAttributePath);

		final Iterable<Path> relativeValuePaths = graphDB.traversalDescription().depthFirst()
				.evaluator(Evaluators.toDepth(relativeValueAttributePath.size())).evaluator(new EntityEvaluator(relativeValueAttributePath))
				.traverse(csEntityNodesArray);

		for (final Path relativeValuePath : relativeValuePaths) {

			final Node valueNode = relativeValuePath.endNode();
			final Node csEntityNode = relativeValuePath.startNode();
			final String valueValue = (String) valueNode.getProperty(GraphStatics.VALUE_PROPERTY, null);
			final Long valueOrder = (Long) relativeValuePath.lastRelationship().getProperty(GraphStatics.ORDER_PROPERTY, null);
			final ValueEntity valueEntity = new ValueEntity(valueNode.getId(), valueValue, valueOrder);
			csEntities.get(csEntityNode.getId()).addValueEntity(valueEntity);
		}
	}

	private static LinkedList<Attribute> determineRelativeAttributePath(final AttributePath attributePath, final AttributePath commonAttributePath) {

		final Iterator<Attribute> apIter = attributePath.getAttributes().iterator();
		final Iterator<Attribute> commonAPIter = commonAttributePath.getAttributes().iterator();

		while(apIter.hasNext()) {

			if(!commonAPIter.hasNext()) {

				break;
			}

			final Attribute apAttribute = apIter.next();
			final Attribute commonAttribute = commonAPIter.next();

			if(!apAttribute.equals(commonAttribute)) {

				break;
			}
		}

		if(!apIter.hasNext()) {

			return null;
		}

		final LinkedList<Attribute> relativeAttributePath = new LinkedList<>();

		while(apIter.hasNext()) {

			relativeAttributePath.add(apIter.next());
		}

		return relativeAttributePath;
	}

	private static void determineCSEntityOrder(final Collection<CSEntity> csEntities) {

		final Group<CSEntity> keyGroup = Lambda.group(csEntities, Lambda.by(Lambda.on(CSEntity.class).getKey()));

		for (final Group<CSEntity> csEntityKeyGroup : keyGroup.subgroups()) {

			int i = 1;

			for (final CSEntity csEntity : csEntityKeyGroup.findAll()) {

				csEntity.setEntityOrder((long) i);
				i++;
			}
		}
	}

	private static String buildGetRecordIdentifierQuery(final AttributePath recordIdentifierAP, final String recordURI) {

		// START n=node:resources(__URI__="http://data.slub-dresden.de/datamodels/7/records/a1280f78-5f96-4fe6-b916-5e38e5d620d3")
		// MATCH (n)-[r:`http://www.ddb.de/professionell/mabxml/mabxml-1.xsd#id`]->(o)
		// WHERE n.__NODETYPE__ = "__RESOURCE__" AND
		// o.__NODETYPE__ = "__LITERAL__"
		// RETURN o.__VALUE__ AS record_identifier;

		final StringBuilder sb = new StringBuilder();

		sb.append("START n=node:resources(").append(GraphStatics.URI).append("=\"").append(recordURI).append("\")\nMATCH (n)");

		int i = 1;
		for (final Attribute attribute : recordIdentifierAP.getAttributes()) {

			sb.append("-[:`").append(attribute.getUri()).append("`]->");

			if (i < recordIdentifierAP.getAttributes().size()) {

				sb.append("()");
			}
		}

		sb.append("(o)\n").append("WHERE n.").append(GraphStatics.NODETYPE_PROPERTY).append(" = \"").append(NodeType.Resource).append("\" AND\no.")
				.append(GraphStatics.NODETYPE_PROPERTY).append(" = \"").append(NodeType.Literal).append("\"\nRETURN o.")
				.append(GraphStatics.VALUE_PROPERTY).append(" AS record_identifier");

		return sb.toString();
	}

	private static String buildGetRecordUriQuery(final String recordId, final AttributePath recordIdentifierAP, final String resourceGraphUri) {

		// START n=node:values(__VALUE__="a1280f78-5f96-4fe6-b916-5e38e5d620d3")
		// MATCH (n)-[r:`http://www.ddb.de/professionell/mabxml/mabxml-1.xsd#id`]->(o)
		// WHERE n.__NODETYPE__ = "__RESOURCE__" AND
		// o.__NODETYPE__ = "__LITERAL__"
		// RETURN o.__URI__ AS record_uri;

		final StringBuilder sb = new StringBuilder();

		sb.append("START o=node:values(").append(GraphStatics.VALUE).append("=\"").append(recordId).append("\")\nMATCH (n)");

		int i = 1;
		for (final Attribute attribute : recordIdentifierAP.getAttributes()) {

			sb.append("-[:`").append(attribute.getUri()).append("`]->");

			if (i < recordIdentifierAP.getAttributes().size()) {

				sb.append("()");
			}
		}

		sb.append("(o)\n").append("WHERE n.").append(GraphStatics.NODETYPE_PROPERTY).append(" = \"").append(NodeType.Resource).append("\" AND\nn.")
				.append(GraphStatics.PROVENANCE_PROPERTY).append(" = \"").append(resourceGraphUri).append("\" AND\no.")
				.append(GraphStatics.NODETYPE_PROPERTY).append(" = \"").append(NodeType.Literal).append("\"\nRETURN n.")
				.append(GraphStatics.URI_PROPERTY).append(" AS record_uri");

		return sb.toString();
	}

	private static String executeQueryWithSingleResult(final String query, final String resultVariableName, final GraphDatabaseService graphDB) {

		final ExecutionEngine engine = new ExecutionEngine(graphDB);

		final ExecutionResult result;
		String resultValue = null;

		try (final Transaction ignored = graphDB.beginTx()) {

			result = engine.execute(query);

			if(result != null) {

				for (final Map<String, Object> row : result) {

					for (final Map.Entry<String, Object> column : row.entrySet()) {

						if (column.getValue() != null) {

							if (column.getKey().equals(resultVariableName)) {

								resultValue = column.getValue().toString();
							}
						}

						break;
					}

					break;
				}
			}

		} catch (final Exception e) {

			// TODO: log something
		}

		return resultValue;
	}
}
